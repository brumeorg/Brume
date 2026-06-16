package com.fungle.brume.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.BrumeRuntimeContext;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.StrategyUsage;
import com.fungle.brume.report.TableStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JSON shape contract of {@link BrumeJsonOutput} (ADR-0030):
 * null fields are omitted, present fields surface with their expected names.
 */
class BrumeJsonOutputTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("planOnly serialization includes 'plan', omits 'execution' and 'comparison'")
    void planOnlyOmitsNulls() throws Exception {
        PlanSummary plan = new PlanSummary("test_brume", List.of(), List.of(), Instant.now());
        BrumeJsonOutput wrapper = BrumeJsonOutput.planOnly(plan);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(wrapper));
        assertThat(node.has("plan")).isTrue();
        assertThat(node.has("execution")).isFalse();
        assertThat(node.has("comparison")).isFalse();
        assertThat(node.get("plan").get("sourceSchema").asText()).isEqualTo("test_brume");
    }

    @Test
    @DisplayName("planAndExecution serialization omits 'comparison'")
    void planAndExecutionOmitsComparison() throws Exception {
        // Build a minimal ExecutionSummary by reflection-free serialization: construct a real
        // PlanSummary + null ComparisonSummary, and pass a no-arg helper for execution-less
        // scenarios. We use the static factory which sets comparison=null explicitly.
        PlanSummary plan = new PlanSummary("test_brume", List.of(), List.of(), Instant.now());
        BrumeJsonOutput wrapper = BrumeJsonOutput.planAndExecution(plan, null);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(wrapper));
        assertThat(node.has("plan")).isTrue();
        // execution is null (we passed null), so it's omitted per @JsonInclude(NON_NULL)
        assertThat(node.has("execution")).isFalse();
        assertThat(node.has("comparison")).isFalse();
    }

    @Test
    @DisplayName("full serialization with all three null-checked fields present in JSON")
    void fullPresentOrAbsent() throws Exception {
        PlanSummary plan = new PlanSummary("test_brume", List.of(), List.of(), Instant.now());
        BrumeJsonOutput wrapper = BrumeJsonOutput.full(plan, null, null);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(wrapper));
        // Only 'plan' surfaces because the other two are null
        assertThat(node.has("plan")).isTrue();
        assertThat(node.has("execution")).isFalse();
        assertThat(node.has("comparison")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Q6 — runtimeContext exposed additively (cf. v1-integration-plan.md §5.Q6)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("plan JSON surfaces runtimeContext with its four fields (additive change)")
    void planRuntimeContextSurfaces() throws Exception {
        BrumeRuntimeContext ctx = new BrumeRuntimeContext(
                "0.14.2", "eu-pii.yaml", "fr", "LENIENT", "execute");
        PlanSummary plan = new PlanSummary(
                "test_brume",
                List.of(),
                List.of(),
                List.of(),
                ctx,
                Instant.parse("2026-05-13T10:00:00Z"));

        JsonNode root = mapper.readTree(mapper.writeValueAsString(BrumeJsonOutput.planOnly(plan)));
        JsonNode runtimeContext = root.get("plan").get("runtimeContext");

        assertThat(runtimeContext).isNotNull();
        assertThat(runtimeContext.get("brumeVersion").asText()).isEqualTo("0.14.2");
        assertThat(runtimeContext.get("configPath").asText()).isEqualTo("eu-pii.yaml");
        assertThat(runtimeContext.get("fakerLocale").asText()).isEqualTo("fr");
        assertThat(runtimeContext.get("ddlErrorMode").asText()).isEqualTo("LENIENT");
    }

    @Test
    @DisplayName("execution JSON surfaces runtimeContext alongside the existing fields")
    void executionRuntimeContextSurfaces() throws Exception {
        BrumeRuntimeContext ctx = new BrumeRuntimeContext(
                "0.14.2", "eu-pii.yaml", "fr", "STRICT", "dry-run");
        ExecutionSummary exec = new ExecutionSummary(
                "src", "dst", true, null,
                new PhaseTimings(100, 100, 100, 300),
                List.of(new TableStats("users", 10, 0, 10, 0, 0)),
                List.<StrategyUsage>of(),
                null, null, null,
                ctx,
                Instant.parse("2026-05-13T10:00:00Z"));
        PlanSummary plan = new PlanSummary("src", List.of(), List.of(), Instant.now());

        JsonNode root = mapper.readTree(
                mapper.writeValueAsString(BrumeJsonOutput.planAndExecution(plan, exec)));
        JsonNode runtimeContext = root.get("execution").get("runtimeContext");

        assertThat(runtimeContext).isNotNull();
        assertThat(runtimeContext.get("brumeVersion").asText()).isEqualTo("0.14.2");
        assertThat(runtimeContext.get("ddlErrorMode").asText()).isEqualTo("STRICT");
    }

    @Test
    @DisplayName("legacy field names remain unchanged after runtimeContext addition (back-compat)")
    void legacyFieldsUnchanged() throws Exception {
        PlanSummary plan = new PlanSummary("test_brume", List.of(), List.of(), Instant.now());
        JsonNode root = mapper.readTree(mapper.writeValueAsString(BrumeJsonOutput.planOnly(plan)));
        JsonNode planNode = root.get("plan");

        // The pre-existing schema (ADR-0030) is preserved — these keys must still be there.
        assertThat(planNode.has("sourceSchema")).isTrue();
        assertThat(planNode.has("tableStats")).isTrue();
        assertThat(planNode.has("piiWarnings")).isTrue();
        assertThat(planNode.has("quasiIdWarnings")).isTrue();
        assertThat(planNode.has("estimatedAt")).isTrue();
    }
}
