package com.fungle.brume.report;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

/**
 * Generates realistic HTML report samples to feed Claude Design.
 *
 * <p>Disabled by default so {@code mvn test} stays fast. Run on demand with:
 * <pre>./mvnw test -Dtest=ReportSamplesGeneratorTest -Dbrume.generateReportSamples=true</pre>
 *
 * <p>Outputs land in {@code report-ui/generated/current/} at the repo root.
 * Files are deterministic (hardcoded timestamps + counts) so rerunning produces
 * a stable diff.
 */
@EnabledIfSystemProperty(named = "brume.generateReportSamples", matches = "true")
class ReportSamplesGeneratorTest {

    private static final Path OUTPUT_DIR =
            Paths.get(System.getProperty("user.dir"), "report-ui", "generated", "current");

    @Test
    void execute_success() throws IOException {
        HtmlReportRenderer renderer = new HtmlReportRenderer(propsForReport());
        write("execute-success.html", renderer.renderHtml(buildSuccessSummary()));
    }

    @Test
    void execute_partial() throws IOException {
        HtmlReportRenderer renderer = new HtmlReportRenderer(propsForReport());
        write("execute-partial.html", renderer.renderHtml(buildPartialSummary()));
    }

    @Test
    void execute_failure() throws IOException {
        HtmlReportRenderer renderer = new HtmlReportRenderer(propsForReport());
        write("execute-failure.html", renderer.renderHtml(buildFailureSummary()));
    }

    @Test
    void plan() throws IOException {
        HtmlReportRenderer renderer = new HtmlReportRenderer(propsForReport());
        write("plan.html", renderer.renderPlanHtml(buildPlanSummary()));
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static BrumeProperties propsForReport() {
        return new BrumeProperties(
                "config.yaml", "secret", "key",
                "HmacSHA256", "fr", 0.0,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", "")
        );
    }

    /**
     * Realistic runtime context per CLI command. Used to surface the command pill in the
     * sample HTML so that the four files in {@code report-ui/generated/current/} reflect
     * what Brume produces in production, not the bare {@link BrumeRuntimeContext#empty()}
     * default.
     */
    private static BrumeRuntimeContext runtimeContext(String command) {
        return new BrumeRuntimeContext(
                "0.0.1-SNAPSHOT", "brume.yml", "fr", "LENIENT", command);
    }

    private ExecutionSummary buildSuccessSummary() {
        PhaseTimings timings = new PhaseTimings(28_412, 19_804, 14_223, 62_439);

        List<TableStats> tableStats = List.of(
                new TableStats("public.users",          12_847, 0,  12_847, 0, 0),
                new TableStats("public.user_profiles",  12_847, 1,  12_847, 0, 0),
                new TableStats("public.orders",         84_201, 2,  84_201, 0, 0),
                new TableStats("public.order_items",   318_402, 1, 318_402, 0, 0),
                new TableStats("public.addresses",      21_048, 1,  21_048, 0, 0),
                new TableStats("public.sessions",       48_921, 1,  48_921, 0, 0)
        );

        List<StrategyUsage> strategyUsages = List.of(
                new StrategyUsage("public.users",         "email",            "FAKE",   12_847),
                new StrategyUsage("public.users",         "phone",            "FAKE",   12_847),
                new StrategyUsage("public.users",         "first_name",       "FAKE",   12_847),
                new StrategyUsage("public.users",         "last_name",        "FAKE",   12_847),
                new StrategyUsage("public.users",         "id",               "FPE_ID", 12_847),
                new StrategyUsage("public.user_profiles", "bio",              "HASH",   12_847),
                new StrategyUsage("public.user_profiles", "user_id",          "FPE_ID", 12_847),
                new StrategyUsage("public.orders",        "id",               "FPE_ID", 84_201),
                new StrategyUsage("public.orders",        "billing_address",  "HASH",   84_201),
                new StrategyUsage("public.orders",        "user_id",          "FPE_ID", 84_201),
                new StrategyUsage("public.addresses",     "street",           "FAKE",   21_048),
                new StrategyUsage("public.addresses",     "city",             "KEEP",   21_048),
                new StrategyUsage("public.addresses",     "postal_code",      "KEEP",   21_048)
        );

        SubstitutionDictStats dict = new SubstitutionDictStats(
                38_492, 1_000_000,
                List.of(
                        new SubstitutionDictStats.TopContributor("public.users.email",       12_847),
                        new SubstitutionDictStats.TopContributor("public.users.phone",       12_847),
                        new SubstitutionDictStats.TopContributor("public.users.first_name",   8_241),
                        new SubstitutionDictStats.TopContributor("public.addresses.street",   4_557)
                )
        );

        HeapStats heap = new HeapStats(
                542L * 1024 * 1024,         // 542 MiB peak
                2L * 1024 * 1024 * 1024,    // 2 GiB max
                false, 85
        );

        return new ExecutionSummary(
                "production_clone", "staging_anonymized",
                true, null,
                timings, tableStats, strategyUsages, dict, heap,
                DdlExecutionResult.empty(),
                runtimeContext("execute"),
                Instant.parse("2026-05-13T08:14:21Z")
        );
    }

    private ExecutionSummary buildPartialSummary() {
        PhaseTimings timings = new PhaseTimings(87_412, 142_837, 33_204, 263_421);

        List<TableStats> tableStats = List.of(
                new TableStats("public.users",             1_284_502, 0,  1_284_502,  0,   0),
                new TableStats("public.user_profiles",     1_284_498, 1,  1_284_498,  0,   0),
                new TableStats("public.addresses",         2_104_883, 1,  2_104_883,  4,   0),
                new TableStats("public.orders",            8_472_901, 2,  8_472_901,  0,   0),
                new TableStats("public.order_items",      31_840_277, 1, 31_840_277,  0,   0),
                new TableStats("public.payments",          8_390_221, 1,          0,  0, 168),
                new TableStats("public.payment_methods",   1_840_921, 1,  1_840_921, 12,   0),
                new TableStats("public.shipping_events",  14_209_884, 1, 14_209_884,  0,   0),
                new TableStats("public.support_tickets",     392_884, 1,    392_884,  2,   0),
                new TableStats("public.ticket_messages",   1_840_292, 2,  1_840_292,  0,   0),
                new TableStats("public.sessions",          4_892_017, 1,  4_892_017,  0,   0)
        );

        List<StrategyUsage> strategyUsages = List.of(
                new StrategyUsage("public.users",            "email",             "FAKE",   1_284_502),
                new StrategyUsage("public.users",            "phone",             "FAKE",   1_284_502),
                new StrategyUsage("public.users",            "id",                "FPE_ID", 1_284_502),
                new StrategyUsage("public.user_profiles",    "bio",               "HASH",   1_284_498),
                new StrategyUsage("public.orders",           "id",                "FPE_ID", 8_472_901),
                new StrategyUsage("public.payments",         "card_token_masked", "HASH",   8_390_221),
                new StrategyUsage("public.payments",         "iban",              "FPE_ID", 8_390_221),
                new StrategyUsage("public.addresses",        "postal_code",       "HASH",   2_104_883),
                new StrategyUsage("public.support_tickets",  "subject",           "FAKE",     392_884)
        );

        SubstitutionDictStats dict = new SubstitutionDictStats(
                769_842, 1_000_000,
                List.of(
                        new SubstitutionDictStats.TopContributor("public.users.email",                1_284_502),
                        new SubstitutionDictStats.TopContributor("public.users.phone",                1_284_502),
                        new SubstitutionDictStats.TopContributor("public.addresses.street",             421_504),
                        new SubstitutionDictStats.TopContributor("public.support_tickets.subject",       38_492)
                )
        );

        HeapStats heap = new HeapStats(
                2_604L * 1024 * 1024,       // 2.5 GiB peak
                4L * 1024 * 1024 * 1024,    // 4 GiB max
                true, 85
        );

        DdlExecutionResult ddl = new DdlExecutionResult(
                42, 3,
                List.of(
                        new DdlFailure(142, "CREATE VIEW public.v_customer_360 AS SELECT u.id, u.legal_name FROM users u",
                                "column \"legal_name\" does not exist on relation \"users\""),
                        new DdlFailure(201, "CREATE TRIGGER tg_orders_audit AFTER INSERT ON public.orders",
                                "function audit.log_order() does not exist"),
                        new DdlFailure(204, "CREATE POLICY rls_payments_owner ON public.payments FOR SELECT TO app_owner",
                                "role \"app_owner\" does not exist")
                )
        );

        return new ExecutionSummary(
                "production_clone", "staging_anonymized",
                true, null,
                timings, tableStats, strategyUsages,
                dict, heap, ddl,
                runtimeContext("execute"),
                Instant.parse("2026-05-13T14:32:17Z")
        );
    }

    private ExecutionSummary buildFailureSummary() {
        PhaseTimings timings = new PhaseTimings(14_204, 8_421, 2_104, 24_729);

        List<TableStats> tableStats = List.of(
                new TableStats("public.users",         12_847, 0, 12_847, 0, 0),
                new TableStats("public.user_profiles", 12_847, 1, 12_847, 0, 0),
                new TableStats("public.orders",        84_201, 2, 23_104, 0, 0)
        );

        List<StrategyUsage> strategyUsages = List.of(
                new StrategyUsage("public.users",  "email", "FAKE",   12_847),
                new StrategyUsage("public.users",  "id",    "FPE_ID", 12_847),
                new StrategyUsage("public.orders", "id",    "FPE_ID", 23_104)
        );

        HeapStats heap = new HeapStats(
                312L * 1024 * 1024,
                2L * 1024 * 1024 * 1024,
                false, 85
        );

        return new ExecutionSummary(
                "production_clone", "staging_anonymized",
                false,
                "Connection to target database lost during write phase (SQLException: I/O error)",
                timings, tableStats, strategyUsages,
                SubstitutionDictStats.empty(), heap,
                DdlExecutionResult.empty(),
                runtimeContext("execute"),
                Instant.parse("2026-05-13T10:42:08Z")
        );
    }

    private PlanSummary buildPlanSummary() {
        List<PlanTableStats> tableStats = List.of(
                new PlanTableStats("public.users",         12_847,      0, "direct"),
                new PlanTableStats("public.user_profiles", 12_847,      0, "direct"),
                new PlanTableStats("public.addresses",     21_048,      0, "direct"),
                new PlanTableStats("public.orders",        84_201,      0, "direct"),
                new PlanTableStats("public.order_items", 318_402,       0, "direct"),
                new PlanTableStats("public.payments",      28_492, 12_847, "direct+fk-parent"),
                new PlanTableStats("public.sessions",      48_921,      0, "direct"),
                new PlanTableStats("public.audit_log",        -1L,    -1L, "direct")
        );

        List<PiiWarning> piiWarnings = List.of(
                new PiiWarning("public.users",     "email",      "email"),
                new PiiWarning("public.users",     "phone",      "phone"),
                new PiiWarning("public.addresses", "street",     "address"),
                new PiiWarning("public.orders",    "ship_notes", "free-text-pii")
        );

        List<QuasiIdWarning> quasiIdWarnings = List.of(
                new QuasiIdWarning("public.users",     "birth_date",  "date",    null,          "birth"),
                new QuasiIdWarning("public.users",     "salary_eur",  "numeric", Strategy.HASH, "salary"),
                new QuasiIdWarning("public.addresses", "postal_code", "varchar", Strategy.KEEP, "postal")
        );

        return new PlanSummary(
                "production_clone",
                tableStats, piiWarnings, quasiIdWarnings,
                runtimeContext("plan"),
                Instant.parse("2026-05-13T08:13:42Z")
        );
    }

    // -------------------------------------------------------------------------

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Path path = OUTPUT_DIR.resolve(name);
        Files.writeString(path, html, StandardCharsets.UTF_8);
        System.out.println("[ReportSamplesGenerator] wrote " + path.toAbsolutePath());
    }
}
