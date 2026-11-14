package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan and execution reports of a run (#127e) : HTML rendered by Thymeleaf and JSON by
 * Jackson — both resolved by reflection, the classic blind spot of a native image. A rendering
 * failure is only a WARN with exit 0 ({@code ReplicationAgent}), so the content is checked,
 * not just the presence of the files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportsE2E {

    private static final String[] TABLES = {"users", "products", "orders", "order_items", "audit_logs"};

    private final E2eProject project = E2eProject.create("reports");
    private Map<String, Integer> planned;

    @BeforeAll
    void runPlanThenExecute() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));
        BrumeCli.Result plan = project.run("plan", "--json");
        assertThat(plan.exitCode()).as(plan.describe()).isZero();
        planned = E2eProject.plannedRows(plan);
        BrumeCli.Result execute = project.run("execute");
        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertThat(execute.stdout()).doesNotContain("renderPlan").doesNotContain("render failed");
    }

    @Test
    @DisplayName("JSON execution report : one entry per table, inserted rows = planned rows")
    void jsonExecutionReport() throws IOException {
        JsonNode report = new ObjectMapper().readTree(project.file("reports/brume-execution-report.json").toFile());

        Map<String, Integer> inserted = new TreeMap<>();
        report.path("tableStats").forEach(t -> inserted.put(t.path("table").asText(), t.path("inserted").asInt()));
        assertThat(inserted).containsOnlyKeys(TABLES).containsExactlyInAnyOrderEntriesOf(planned);
    }

    @Test
    @DisplayName("HTML execution report : headline, every section and every table rendered")
    void htmlExecutionReport() throws IOException {
        int total = planned.values().stream().mapToInt(Integer::intValue).sum();
        String html = Files.readString(project.file("reports/brume-execution-report.html"));

        assertThat(html).startsWith("<!DOCTYPE html>")
                .contains("5 table(s) anonymisée(s), " + total + " lignes insérées.")
                .contains("Durées par phase", "Résultats par table", "Plan vs Réel", "Stratégies appliquées")
                .contains(TABLES)
                .doesNotContain("th:text").doesNotContain("${");
    }

    @Test
    @DisplayName("HTML plan report : headline, sections and planned tables rendered")
    void htmlPlanReport() throws IOException {
        String html = Files.readString(project.file("reports/brume-plan-report.html"));

        assertThat(html).startsWith("<!DOCTYPE html>")
                .contains("5 table(s) planifiée(s)")
                .contains("Tables planifiées", "PII &amp; quasi-identifiants")
                .contains(TABLES)
                .doesNotContain("th:text").doesNotContain("${");
    }
}
