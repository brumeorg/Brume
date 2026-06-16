package com.fungle.brume.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.model.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReportRenderer} and {@link HtmlReportRenderer}.
 *
 * <p>No Spring context — all components are instantiated directly.
 */
class ReportRendererTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private BrumeProperties props(String jsonFile, String htmlFile, String planHtmlFile) {
        return new BrumeProperties(
                "config.yaml", "secret", "key",
                "HmacSHA256", "fr", 0.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties(jsonFile, htmlFile, planHtmlFile)
        );
    }

    // -------------------------------------------------------------------------
    // Helper — builds a hand-crafted ExecutionSummary
    // -------------------------------------------------------------------------

    private ExecutionSummary buildSuccessSummary() {
        PhaseTimings timings = new PhaseTimings(2104, 831, 3412, 7143);
        List<TableStats> tableStats = List.of(
                new TableStats("users", 847, 0, 847, 0, 0),
                new TableStats("products", 312, 0, 309, 3, 0),
                new TableStats("orders", 3204, 0, 3204, 0, 0)
        );
        List<StrategyUsage> strategyUsages = List.of(
                new StrategyUsage("users", "email", "FAKE", 847),
                new StrategyUsage("users", "id", "FPE_ID", 847),
                new StrategyUsage("products", "name", "HASH", 312)
        );
        return new ExecutionSummary(
                "test_brume", "test_brume_anon",
                true, null,
                timings, tableStats, strategyUsages,
                Instant.parse("2026-04-20T14:32:11Z")
        );
    }

    private ExecutionSummary buildFailureSummary() {
        PhaseTimings timings = new PhaseTimings(0, 0, 0, 150);
        return new ExecutionSummary(
                "test_brume", "test_brume_anon",
                false, "timeout",
                timings, List.of(), List.of(),
                Instant.parse("2026-04-20T14:32:11Z")
        );
    }

    private ExecutionSummary buildSummaryWithConflicts(int conflicts) {
        PhaseTimings timings = new PhaseTimings(100, 50, 200, 400);
        List<TableStats> tableStats = List.of(
                new TableStats("orders", 100, 0, 91, conflicts, 0)
        );
        return new ExecutionSummary(
                "src", "dst",
                true, null,
                timings, tableStats, List.of(),
                Instant.now()
        );
    }

    private ExecutionSummary buildSummaryWithTimings(PhaseTimings timings) {
        return new ExecutionSummary(
                "src", "dst",
                true, null,
                timings, List.of(), List.of(),
                Instant.parse("2026-04-20T14:32:11Z")
        );
    }

    private ExecutionSummary buildSummaryWithSubstitutionDict() {
        return new ExecutionSummary(
                "src", "dst",
                true, null,
                new PhaseTimings(100, 50, 200, 400),
                List.of(new TableStats("users", 2, 0, 2, 0, 0)),
                List.of(),
                new SubstitutionDictStats(
                        3,
                        10,
                        List.of(
                                new SubstitutionDictStats.TopContributor("users.email", 2),
                                new SubstitutionDictStats.TopContributor("orders.notes", 1)
                        )
                ),
                Instant.parse("2026-04-20T14:32:11Z")
        );
    }

    private ExecutionSummary buildSummaryWithHeapStats() {
        return new ExecutionSummary(
                "src", "dst",
                true, null,
                new PhaseTimings(100, 50, 200, 400),
                List.of(new TableStats("users", 2, 0, 2, 0, 0)),
                List.of(),
                SubstitutionDictStats.empty(),
                new HeapStats(128L * 1024 * 1024, 256L * 1024 * 1024, true, 85),
                Instant.parse("2026-04-20T14:32:11Z")
        );
    }

    // -------------------------------------------------------------------------
    // OBS-1 — ASCII text rendering
    // -------------------------------------------------------------------------

    @Test
    void shouldRenderTextWithSuccessStatus() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderText(buildSuccessSummary());

        assertThat(text).contains("SUCCÈS");
        assertThat(text).contains("users");
        assertThat(text).contains("products");
        assertThat(text).contains("orders");
        assertThat(text).contains("TOTAL");
        assertThat(text).contains("test_brume");
    }

    @Test
    void shouldRenderTextWithFailureStatus() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderText(buildFailureSummary());

        assertThat(text).contains("ÉCHEC");
        assertThat(text).contains("timeout");
    }

    @Test
    void shouldIncludeWarningWhenConflictsExist() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderText(buildSummaryWithConflicts(9));

        assertThat(text).contains("[WARN]");
        assertThat(text).contains("9 conflit(s)");
    }

    @Test
    void shouldNotIncludeWarningWhenNoConflicts() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderText(buildSummaryWithConflicts(0));

        assertThat(text).doesNotContain("[WARN]");
    }

    @Test
    void shouldRenderDdlIgnoredSectionWhenLenientModeDroppedStatements() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        ExecutionReport report = new ExecutionReport("src", "dst");
        report.captureDdlExecution(new DdlExecutionResult(5, 2, List.of(
                new DdlFailure(2, "CREATE EXTENSION pgcrypto", "extension is not available"),
                new DdlFailure(4, "ALTER TABLE t ADD CONSTRAINT fk_x", "referenced table missing")
        )));
        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 100));

        String text = renderer.renderText(summary);

        assertThat(text)
                .as("explicit count of dropped statements + LENIENT context — invisible pre-#28")
                .contains("[WARN] 2 DDL statement(s) ignorés (LENIENT mode)")
                .as("each failure surfaces its index, sql preview and the Postgres error")
                .contains("#2 CREATE EXTENSION pgcrypto")
                .contains("extension is not available")
                .contains("#4 ALTER TABLE t ADD CONSTRAINT fk_x")
                .contains("referenced table missing");
    }

    @Test
    void shouldOmitDdlIgnoredSectionWhenNoFailures() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderText(buildSuccessSummary());

        assertThat(text)
                .as("no LENIENT failures → no DDL section in the rapport")
                .doesNotContain("DDL statement(s) ignorés");
    }

    @Test
    void shouldRenderHeapSectionWhenAvailable() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderText(buildSummaryWithHeapStats());

        assertThat(text).contains("Heap JVM");
        assertThat(text).contains("128.0 MiB / 256.0 MiB");
        assertThat(text).contains("seuil 85%");
    }

    // -------------------------------------------------------------------------
    // OBS-4 — JSON output
    // -------------------------------------------------------------------------

    @Test
    void shouldWriteJsonFileWhenConfigured() throws IOException {
        Path jsonPath = tempDir.resolve("report.json");
        BrumeProperties props = props(jsonPath.toString(), "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        renderer.render(buildSuccessSummary());

        assertThat(jsonPath).exists();
        String content = Files.readString(jsonPath);
        JsonNode json = objectMapper.readTree(content);
        assertThat(json.has("sourceSchema")).isTrue();
        assertThat(json.get("sourceSchema").asText()).isEqualTo("test_brume");
        assertThat(json.has("tableStats")).isTrue();
        assertThat(json.has("startedAt")).isTrue();
    }

    @Test
    void shouldSerializeInstantAsIsoString() throws IOException {
        Path jsonPath = tempDir.resolve("report-instant.json");
        BrumeProperties props = props(jsonPath.toString(), "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        renderer.render(buildSuccessSummary());

        String content = Files.readString(jsonPath);
        JsonNode json = objectMapper.readTree(content);
        String startedAt = json.get("startedAt").asText();
        // Must be an ISO-8601 string, not an array or numeric timestamp
        assertThat(startedAt).contains("2026-04-20");
        assertThat(startedAt).contains("T");
        assertThat(json.get("startedAt").isTextual()).isTrue();
    }

    @Test
    void shouldNotWriteJsonFileWhenPathIsBlank() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        // Should not throw and no file should be created in tempDir
        renderer.render(buildSuccessSummary());

        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void shouldIncludeStrategyUsagesInJson() throws IOException {
        Path jsonPath = tempDir.resolve("report-strategy.json");
        BrumeProperties props = props(jsonPath.toString(), "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        renderer.render(buildSuccessSummary());

        JsonNode json = objectMapper.readTree(jsonPath.toFile());
        JsonNode usages = json.get("strategyUsages");
        assertThat(usages.isArray()).isTrue();
        assertThat(usages.size()).isGreaterThan(0);
        JsonNode first = usages.get(0);
        assertThat(first.has("table")).isTrue();
        assertThat(first.has("column")).isTrue();
        assertThat(first.has("strategy")).isTrue();
        assertThat(first.has("count")).isTrue();
    }

    @Test
    void shouldIncludePhaseTimingsInJson() throws IOException {
        Path jsonPath = tempDir.resolve("report-timings.json");
        BrumeProperties props = props(jsonPath.toString(), "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        renderer.render(buildSuccessSummary());

        JsonNode json = objectMapper.readTree(jsonPath.toFile());
        JsonNode timings = json.get("timings");
        assertThat(timings.has("extractionMs")).isTrue();
        assertThat(timings.has("anonymizationMs")).isTrue();
        assertThat(timings.has("writeMs")).isTrue();
        assertThat(timings.has("totalMs")).isTrue();
        assertThat(timings.get("extractionMs").asLong()).isEqualTo(2104L);
        assertThat(timings.get("anonymizationMs").asLong()).isEqualTo(831L);
        assertThat(timings.get("writeMs").asLong()).isEqualTo(3412L);
    }

    @Test
    void shouldIncludeSubstitutionDictStatsInJson() throws IOException {
        Path jsonPath = tempDir.resolve("report-substitution-dict.json");
        BrumeProperties props = props(jsonPath.toString(), "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        renderer.render(buildSummaryWithSubstitutionDict());

        JsonNode json = objectMapper.readTree(jsonPath.toFile());
        JsonNode substitutionDict = json.get("substitutionDict");
        assertThat(substitutionDict).isNotNull();
        assertThat(substitutionDict.get("entries").asLong()).isEqualTo(3L);
        assertThat(substitutionDict.get("limit").asLong()).isEqualTo(10L);
        assertThat(substitutionDict.get("topContributors").isArray()).isTrue();
        assertThat(substitutionDict.get("topContributors").get(0).get("semanticKey").asText())
                .isEqualTo("users.email");
    }

    // -------------------------------------------------------------------------
    // OBS-5 — HTML output
    // -------------------------------------------------------------------------

    @Test
    void shouldGenerateHtmlContainingTableNames() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String html = htmlRenderer.renderHtml(buildSuccessSummary());

        assertThat(html).contains("users");
        assertThat(html).contains("orders");
        // V2: success status doubled by class + text — DS v1 badge wording is "Succès".
        assertThat(html).contains("hero success");
        assertThat(html).contains("Succès");
        assertThat(html).contains("Brume");
    }

    @Test
    void shouldEscapeHtmlInSchemaName() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        PhaseTimings timings = new PhaseTimings(10, 10, 10, 30);
        ExecutionSummary summary = new ExecutionSummary(
                "<script>xss</script>", "target",
                true, null,
                timings, List.of(), List.of(),
                Instant.now()
        );

        String html = htmlRenderer.renderHtml(summary);

        // Raw <script> tag must NOT appear — it must be HTML-escaped
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void shouldProduceSeparateSegmentsForEachPhase() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String html = htmlRenderer.renderHtml(buildSuccessSummary());

        assertThat(html).contains("Extraction");
        assertThat(html).contains("Anonymisation");
        assertThat(html).contains("Écriture");
    }

    @Test
    void shouldInlineSharedCssWithoutExternalStylesheet() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String html = htmlRenderer.renderHtml(buildSuccessSummary());

        assertThat(html).contains("<style>");
        // V2: OKLCH status tokens. .timing-bar still exists in DS v1 CSS.
        assertThat(html).contains("--ok");
        assertThat(html).contains(".timing-bar");
        assertThat(html).doesNotContain("<link rel=\"stylesheet\"");
    }

    @Test
    void shouldRenderFailureCauseInExecutionHtml() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String html = htmlRenderer.renderHtml(buildFailureSummary());

        // V2: failure rendered via .hero.failure class + badge "Échec" + cause woven
        // into the headline prose. Action-required callout points to logs.
        assertThat(html).contains("hero failure");
        assertThat(html).contains("Échec");
        assertThat(html).contains("timeout");
    }

    @Test
    void shouldRenderSubstitutionDictSectionInExecutionHtml() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String html = htmlRenderer.renderHtml(buildSummaryWithSubstitutionDict());

        // V2 dict card — heading + top contributors + usage indicator.
        assertThat(html).contains("Dictionnaire de substitution");
        assertThat(html).contains("users.email");
        assertThat(html).contains("orders.notes");
        assertThat(html).contains("utilisé");
    }

    @Test
    void shouldRenderCompactNaturalDurationsInExecutionHtml() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String html = htmlRenderer.renderHtml(buildSuccessSummary());

        // V2: hero uses compactClock (m:ss.d s) ; timing legend uses formatDurationCompact.
        // totalMs=7143 → hero "0:07.1 s" ; extractionMs=2104 → legend "2,1 s" ;
        // anonymizationMs=831 → "831 ms" ; writeMs=3412 → "3,4 s".
        assertThat(html).contains("0:07.1 s");
        assertThat(html).contains("2,1 s");
        assertThat(html).contains("831 ms");
        assertThat(html).contains("3,4 s");
        assertThat(html).doesNotContain("7 143 ms");
    }

    @Test
    void shouldRenderDurationThresholdsUsingCompactNaturalFormat() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String underSecondHtml = htmlRenderer.renderHtml(buildSummaryWithTimings(new PhaseTimings(999, 0, 0, 999)));
        String decimalSecondHtml = htmlRenderer.renderHtml(buildSummaryWithTimings(new PhaseTimings(1_000, 0, 0, 1_000)));
        String secondsHtml = htmlRenderer.renderHtml(buildSummaryWithTimings(new PhaseTimings(59_000, 0, 0, 59_000)));
        String minutesHtml = htmlRenderer.renderHtml(buildSummaryWithTimings(new PhaseTimings(60_000, 0, 0, 60_000)));
        String hoursHtml = htmlRenderer.renderHtml(buildSummaryWithTimings(new PhaseTimings(3_600_000, 0, 0, 3_600_000)));

        assertThat(underSecondHtml).contains("999 ms");
        assertThat(decimalSecondHtml).contains("1,0 s");
        assertThat(secondsHtml).contains("59 s");
        assertThat(minutesHtml).contains("1 min 0 s");
        assertThat(hoursHtml).contains("1 h 0 min 0 s");
    }

    // -------------------------------------------------------------------------
    // Plan HTML rendering
    // -------------------------------------------------------------------------

    @Test
    void shouldGeneratePlanHtmlWithTableNames() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        PlanSummary plan = new PlanSummary("test_brume",
                List.of(
                        new PlanTableStats("orders", 8, 0, "direct"),
                        new PlanTableStats("users", 5, 0, "fk-parent")
                ),
                List.of(),
                Instant.now());

        String html = htmlRenderer.renderPlanHtml(plan);

        // Apostrophe is HTML-escaped (&#39;) by Thymeleaf in attribute / text contexts.
        assertThat(html).contains("Plan d&#39;exécution");
        assertThat(html).contains("orders");
        assertThat(html).contains("users");
        assertThat(html).contains("test_brume");
        // V2 plan hero badge — DS v1 wording "Pré-vol — décision go / no-go".
        assertThat(html).contains("Pré-vol");
    }

    @Test
    void shouldGeneratePlanHtmlWithPiiWarnings() {
        BrumeProperties props = props("", "", "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        PlanSummary plan = new PlanSummary("test_brume",
                List.of(new PlanTableStats("orders", 8, 0, "direct")),
                List.of(new PiiWarning("users", "email", "email")),
                Instant.now());

        String html = htmlRenderer.renderPlanHtml(plan);

        // V2 plan warnings — single block with category column ("PII") + qualified column.
        // The original verbose RGPD definition lives in the legacy pii-warnings.html
        // fragment, no longer included in the plan template under chantier B.
        assertThat(html).contains("PII");
        assertThat(html).contains("users.email");
    }

    @Test
    void shouldEscapeHtmlInPlanAndComparisonTemplates() {
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "secret", "key",
                "HmacSHA256", "fr", 0.0,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", "")
        );
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        PlanSummary plan = new PlanSummary("<script>schema</script>",
                List.of(new PlanTableStats("orders", 8, 0, "direct")),
                List.of(new PiiWarning("users", "<script>email</script>", "email")),
                Instant.parse("2026-04-20T14:32:11Z"));

        String planHtml = htmlRenderer.renderPlanHtml(plan);
        assertThat(planHtml).doesNotContain("<script>schema</script>");
        assertThat(planHtml).contains("&lt;script&gt;schema&lt;/script&gt;");
        assertThat(planHtml).doesNotContain("<script>email</script>");
        assertThat(planHtml).contains("&lt;script&gt;email&lt;/script&gt;");

        ComparisonSummary comparison = new ComparisonSummary(
                plan,
                new ExecutionSummary("src", "dst", true, null,
                        new PhaseTimings(10, 10, 10, 30),
                        List.of(new TableStats("safe", 1, 0, 1, 0, 0)),
                        List.of(),
                        Instant.parse("2026-04-20T14:32:11Z")),
                List.of(new ComparisonRow("<script>orders</script>", 1, 1, 1, 0, 0.0)),
                List.of(new PiiWarning("users", "<script>phone</script>", "phone")),
                List.of(new Insight(Insight.Level.WARN, "<script>alert(1)</script>", "Global"))
        );

        String comparisonHtml = htmlRenderer.renderComparisonSection(comparison);
        assertThat(comparisonHtml).doesNotContain("<script>alert(1)</script>");
        assertThat(comparisonHtml).contains("&lt;script&gt;orders&lt;/script&gt;");
        assertThat(comparisonHtml).contains("&lt;script&gt;phone&lt;/script&gt;");
        assertThat(comparisonHtml).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void shouldWritePlanHtmlFileWhenConfigured() throws IOException {
        Path planPath = tempDir.resolve("plan.html");
        BrumeProperties props = props("", "", planPath.toString());
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        PlanSummary plan = new PlanSummary("test_brume",
                List.of(new PlanTableStats("orders", 8, 0, "direct")),
                List.of(),
                Instant.now());

        htmlRenderer.renderPlan(plan);

        assertThat(planPath).exists();
        assertThat(Files.readString(planPath)).contains("Plan d&#39;exécution");
    }

    // -------------------------------------------------------------------------
    // OBS-7 — Plan vs Réel comparison
    // -------------------------------------------------------------------------

    private ComparisonSummary buildComparisonNoDeltas() {
        PlanTableStats pts1 = new PlanTableStats("orders", 3204, 0, "direct");
        PlanTableStats pts2 = new PlanTableStats("users",  847,  0, "fk-parent");
        PlanSummary plan = new PlanSummary("test_brume",
                List.of(pts1, pts2), List.of(), Instant.now());

        PhaseTimings timings = new PhaseTimings(100, 50, 200, 400);
        List<TableStats> tableStats = List.of(
                new TableStats("orders", 3204, 0, 3204, 0, 0),
                new TableStats("users",  847,  0,  847, 0, 0)
        );
        ExecutionSummary exec = new ExecutionSummary(
                "test_brume", "test_brume_anon",
                true, null, timings, tableStats, List.of(), Instant.now());

        List<ComparisonRow> rows = List.of(
                new ComparisonRow("orders", 3204, 3204, 3204, 0, 0.0),
                new ComparisonRow("users",  847,  847,  847, 0, 0.0)
        );
        return new ComparisonSummary(plan, exec, rows, List.of(), List.of());
    }

    private ComparisonSummary buildComparisonWithConflicts() {
        PlanTableStats pts = new PlanTableStats("products", 312, 0, "direct");
        PlanSummary plan = new PlanSummary("test_brume",
                List.of(pts), List.of(), Instant.now());

        PhaseTimings timings = new PhaseTimings(100, 50, 200, 400);
        List<TableStats> tableStats = List.of(
                new TableStats("products", 312, 0, 303, 9, 0)
        );
        ExecutionSummary exec = new ExecutionSummary(
                "test_brume", "test_brume_anon",
                true, null, timings, tableStats, List.of(), Instant.now());

        List<ComparisonRow> rows = List.of(
                new ComparisonRow("products", 312, 312, 303, 9, 0.0)
        );
        return new ComparisonSummary(plan, exec, rows, List.of(), List.of());
    }

    @Test
    void shouldRenderComparisonWithNoDeltas() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderComparisonText(buildComparisonNoDeltas());

        assertThat(text).contains("Plan vs Réel");
        assertThat(text).contains("✓");
        assertThat(text).contains("orders");
        assertThat(text).contains("users");
    }

    @Test
    void shouldRenderComparisonWithConflicts() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        String text = renderer.renderComparisonText(buildComparisonWithConflicts());

        assertThat(text).contains("⚠");
        assertThat(text).contains("9");
    }

    @Test
    void shouldGenerateHtmlComparisonSection() {
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "secret", "key",
                "HmacSHA256", "fr", 0.0,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", "")
        );
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        String html = htmlRenderer.renderComparisonSection(buildComparisonNoDeltas());

        assertThat(html).contains("Plan vs Réel");
        assertThat(html).contains("Planifié");
        assertThat(html).contains("Δ");
        assertThat(html).contains("orders");
    }

    @Test
    void shouldRewriteExecutionHtmlWithComparisonAndFooterAtEnd() throws IOException {
        Path htmlPath = tempDir.resolve("execution-report.html");
        BrumeProperties props = props("", htmlPath.toString(), "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        htmlRenderer.render(buildSuccessSummary());
        htmlRenderer.renderComparison(buildComparisonNoDeltas());

        String html = Files.readString(htmlPath);
        assertThat(html).contains("Plan vs Réel");
        // V2 footer — "généré <timestamp>" rendered by execution-footer-v2 fragment.
        assertThat(html).contains("généré");
        assertThat(html).doesNotContain("BRUME_FOOTER_PLACEHOLDER");
        assertThat(html.indexOf("Plan vs Réel")).isLessThan(html.lastIndexOf("généré"));
    }

    @Test
    void shouldEscapeComparisonContentWhenEmbeddedInExecutionDocument() throws IOException {
        Path htmlPath = tempDir.resolve("execution-comparison-report.html");
        BrumeProperties props = props("", htmlPath.toString(), "");
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer(props);

        PlanSummary plan = new PlanSummary("safe_schema",
                List.of(new PlanTableStats("orders", 1, 0, "direct")),
                List.of(),
                Instant.parse("2026-04-20T14:32:11Z"));
        ComparisonSummary comparison = new ComparisonSummary(
                plan,
                buildSuccessSummary(),
                List.of(new ComparisonRow("<script>orders</script>", 1, 1, 1, 0, 0.0)),
                List.of(new PiiWarning("users", "<script>email</script>", "email")),
                List.of(new Insight(Insight.Level.WARN, "<script>alert(1)</script>", "Global"))
        );

        htmlRenderer.render(buildSuccessSummary());
        htmlRenderer.renderComparison(comparison);

        String html = Files.readString(htmlPath);
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).doesNotContain("<script>orders</script>");
        assertThat(html).contains("&lt;script&gt;orders&lt;/script&gt;");
        assertThat(html).contains("&lt;script&gt;email&lt;/script&gt;");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    // -------------------------------------------------------------------------
    // #21c — Quasi-identifier section in renderPlanText
    // -------------------------------------------------------------------------

    @Test
    void renderPlanTextIncludesQuasiIdSectionWhenWarnings() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        PlanSummary plan = new PlanSummary("test_brume",
                List.of(new PlanTableStats("users", 5, 0, "direct")),
                List.of(),
                List.of(
                        new QuasiIdWarning("users", "birth_date", "date", null, "birth"),
                        new QuasiIdWarning("users", "salary_eur", "numeric", Strategy.HASH, "salary")
                ),
                Instant.parse("2026-05-12T10:00:00Z"));

        String text = renderer.renderPlanText(plan);

        assertThat(text).contains("[QUASI-ID]");
        assertThat(text).contains("users.birth_date");
        assertThat(text).contains("date");
        assertThat(text).contains("KEEP implicite");
        assertThat(text).contains("users.salary_eur");
        assertThat(text).contains("HASH");
        assertThat(text).contains("pattern: salary");
    }

    @Test
    void renderPlanTextOmitsQuasiIdSectionWhenNoWarnings() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        PlanSummary plan = new PlanSummary("test_brume",
                List.of(new PlanTableStats("users", 5, 0, "direct")),
                List.of(),
                List.of(),
                Instant.parse("2026-05-12T10:00:00Z"));

        String text = renderer.renderPlanText(plan);

        assertThat(text).doesNotContain("[QUASI-ID]");
    }

    @Test
    void renderPlanTextRendersBothPiiAndQuasiIdSections() {
        BrumeProperties props = props("", "", "");
        ReportRenderer renderer = new ReportRenderer(props, objectMapper,
                new HtmlReportRenderer(props));

        PlanSummary plan = new PlanSummary("test_brume",
                List.of(new PlanTableStats("users", 5, 0, "direct")),
                List.of(new PiiWarning("users", "phone", "phone")),
                List.of(new QuasiIdWarning("users", "birth_date", "date", null, "birth")),
                Instant.parse("2026-05-12T10:00:00Z"));

        String text = renderer.renderPlanText(plan);

        assertThat(text).contains("[WARN PII]");
        assertThat(text).contains("[QUASI-ID]");
        // PII section first, quasi-id section after
        assertThat(text.indexOf("[WARN PII]")).isLessThan(text.indexOf("[QUASI-ID]"));
    }

    @Test
    void shouldExposeTemplatesAndCssOnClasspath() {
        ClassLoader classLoader = HtmlReportRenderer.class.getClassLoader();

        // Master templates + shared head wrapper + legacy fragments (comparison, pii-warnings).
        assertThat(classLoader.getResource("templates/report/execution.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/plan.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/common.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/comparison.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/pii-warnings.html")).isNotNull();

        // V2 execution fragments — chantier B (design system v1).
        assertThat(classLoader.getResource("templates/report/fragments/execution-masthead-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-hero-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-meta-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-stat-row.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-timing-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-callout.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-tables-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-strategies-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-warning-summary.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-warning-blocks.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/execution-footer-v2.html")).isNotNull();

        // V2 plan fragments.
        assertThat(classLoader.getResource("templates/report/fragments/plan-masthead-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/plan-hero-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/plan-meta-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/plan-stat-row.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/plan-callouts.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/plan-tables-v2.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/plan-warning-list.html")).isNotNull();
        assertThat(classLoader.getResource("templates/report/fragments/plan-footer-v2.html")).isNotNull();

        // Shared CSS.
        assertThat(classLoader.getResource("report/report.css")).isNotNull();
    }
}
