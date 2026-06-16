package com.fungle.brume.report;

import com.fungle.brume.config.model.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the new V2 derivations introduced in chantier A
 * (cf. {@code report-ui/v1-integration-plan.md} §6).
 *
 * <p>Covers the package-private static helpers on
 * {@link ReportTemplateModelFactory} and the {@link Strategy#descriptionOf(String)}
 * fallback. No Spring context required.
 */
class ReportTemplateModelFactoryTest {

    // -------------------------------------------------------------------------
    // deriveStatusClass
    // -------------------------------------------------------------------------

    @Test
    void deriveStatusClass_returnsSuccess_whenSuccessAndNoBatchErrors() {
        ExecutionSummary summary = summaryWith(true, null, batchErrors(0));
        assertThat(ReportTemplateModelFactory.deriveStatusClass(summary)).isEqualTo("success");
    }

    @Test
    void deriveStatusClass_returnsPartial_whenSuccessButBatchErrors() {
        ExecutionSummary summary = summaryWith(true, null, batchErrors(7));
        assertThat(ReportTemplateModelFactory.deriveStatusClass(summary)).isEqualTo("partial");
    }

    @Test
    void deriveStatusClass_returnsFailure_whenSuccessFalse() {
        ExecutionSummary summary = summaryWith(false, "connection lost", batchErrors(0));
        assertThat(ReportTemplateModelFactory.deriveStatusClass(summary)).isEqualTo("failure");
    }

    // -------------------------------------------------------------------------
    // throughputAvg
    // -------------------------------------------------------------------------

    @Test
    void throughputAvg_returnsNa_belowOneSecond() {
        ExecutionSummary summary = summaryWithTiming(new PhaseTimings(100, 100, 100, 500), 10_000);
        assertThat(ReportTemplateModelFactory.throughputAvg(summary)).isEqualTo("n/a");
    }

    @Test
    void throughputAvg_returnsKilo_forKiloRange() {
        // 10_000 rows over 1 s → 10k lignes/s
        ExecutionSummary summary = summaryWithTiming(new PhaseTimings(0, 0, 0, 1_000), 10_000);
        assertThat(ReportTemplateModelFactory.throughputAvg(summary)).isEqualTo("10k lignes/s");
    }

    @Test
    void throughputAvg_returnsMega_forMillionRange() {
        // 2_000_000 rows over 1 s → 2.0M lignes/s
        ExecutionSummary summary = summaryWithTiming(new PhaseTimings(0, 0, 0, 1_000), 2_000_000);
        assertThat(ReportTemplateModelFactory.throughputAvg(summary)).isEqualTo("2.0M lignes/s");
    }

    // -------------------------------------------------------------------------
    // coveredColumnsCount
    // -------------------------------------------------------------------------

    @Test
    void coveredColumnsCount_countsDistinctTableColumnPairs() {
        ExecutionSummary summary = new ExecutionSummary(
                "src", "dst", true, null,
                new PhaseTimings(0, 0, 0, 100),
                List.of(),
                List.of(
                        new StrategyUsage("users", "email", "FAKE", 10),
                        new StrategyUsage("users", "email", "FAKE", 10), // duplicate (table, column)
                        new StrategyUsage("users", "phone", "FAKE", 10),
                        new StrategyUsage("orders", "id", "FPE_ID", 10)
                ),
                Instant.parse("2026-05-13T10:00:00Z")
        );
        assertThat(ReportTemplateModelFactory.coveredColumnsCount(summary)).isEqualTo(3L);
    }

    // -------------------------------------------------------------------------
    // hero date / time formatters
    // -------------------------------------------------------------------------

    @Test
    void formatHeroDate_formatsToUtcWithSecondsAndZone() {
        Instant instant = Instant.parse("2026-05-13T14:36:41Z");
        assertThat(ReportTemplateModelFactory.formatHeroDate(instant))
                .isEqualTo("2026-05-13 14:36:41 UTC");
    }

    @Test
    void formatHeroStarted_extractsTimeOnly() {
        Instant instant = Instant.parse("2026-05-13T14:32:17Z");
        assertThat(ReportTemplateModelFactory.formatHeroStarted(instant))
                .isEqualTo("14:32:17");
    }

    @Test
    void formatHeroFinished_addsTotalMs() {
        Instant started = Instant.parse("2026-05-13T14:32:17Z");
        // 4 min 23 s 400 ms = 263_400 ms → finishes at 14:36:40.4
        assertThat(ReportTemplateModelFactory.formatHeroFinished(started, 263_400L))
                .isEqualTo("14:36:40");
    }

    @Test
    void formatHeroFinished_handlesNullStartedAt() {
        assertThat(ReportTemplateModelFactory.formatHeroFinished(null, 1_000L)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // inferDdlObjectType
    // -------------------------------------------------------------------------

    @Test
    void inferDdlObjectType_recognizesCommonKeywords() {
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "CREATE VIEW public.v_customer_360 AS SELECT u.id"))
                .isEqualTo("VIEW");
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "CREATE TRIGGER tg_orders_audit AFTER INSERT"))
                .isEqualTo("TRIGGER");
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "CREATE INDEX ix_users_email_lower"))
                .isEqualTo("INDEX");
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "CREATE EXTENSION pg_stat_statements"))
                .isEqualTo("EXTENSION");
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "CREATE POLICY rls_payments_owner ON public.payments"))
                .isEqualTo("POLICY");
    }

    @Test
    void inferDdlObjectType_recognizesMaterializedView() {
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "CREATE MATERIALIZED VIEW mv_user_summary AS SELECT"))
                .isEqualTo("MATERIALIZED VIEW");
    }

    @Test
    void inferDdlObjectType_recognizesAlterAndDropVerbs() {
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "ALTER TABLE users ADD CONSTRAINT fk_x"))
                .isEqualTo("TABLE");
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "DROP INDEX ix_users_email"))
                .isEqualTo("INDEX");
    }

    @Test
    void inferDdlObjectType_fallsBackToDdl_whenUnknown() {
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(
                "GRANT SELECT ON users TO app_owner"))
                .isEqualTo("DDL");
        assertThat(ReportTemplateModelFactory.inferDdlObjectType(null)).isEqualTo("DDL");
        assertThat(ReportTemplateModelFactory.inferDdlObjectType("")).isEqualTo("DDL");
    }

    // -------------------------------------------------------------------------
    // formatDdlStatementNumber
    // -------------------------------------------------------------------------

    @Test
    void formatDdlStatementNumber_addsHashAndThousandsSeparator() {
        assertThat(ReportTemplateModelFactory.formatDdlStatementNumber(142_901))
                .isEqualTo("#142 901");
        assertThat(ReportTemplateModelFactory.formatDdlStatementNumber(14))
                .isEqualTo("#14");
    }

    // -------------------------------------------------------------------------
    // derivePlanPiiStats / derivePlanQuasiIdStats
    // -------------------------------------------------------------------------

    @Test
    void derivePlanPiiStats_summarizesTablesAndPatterns() {
        PlanSummary plan = new PlanSummary(
                "src",
                List.of(new PlanTableStats("users", 10, 0, "direct")),
                List.of(
                        new PiiWarning("users", "email", "email"),
                        new PiiWarning("users", "phone", "phone"),
                        new PiiWarning("addresses", "street", "address")
                ),
                List.of(),
                Instant.parse("2026-05-13T10:00:00Z")
        );
        assertThat(ReportTemplateModelFactory.derivePlanPiiStats(plan))
                .isEqualTo("2 table(s) · pattern email/phone/address");
    }

    @Test
    void derivePlanPiiStats_returnsEmpty_whenNoWarnings() {
        PlanSummary plan = new PlanSummary(
                "src", List.of(), List.of(), List.of(),
                Instant.parse("2026-05-13T10:00:00Z"));
        assertThat(ReportTemplateModelFactory.derivePlanPiiStats(plan)).isEmpty();
    }

    @Test
    void derivePlanQuasiIdStats_countsKeepImplicitAndExplicit() {
        PlanSummary plan = new PlanSummary(
                "src",
                List.of(new PlanTableStats("users", 10, 0, "direct")),
                List.of(),
                List.of(
                        new QuasiIdWarning("users", "birth_date", "date", null, "birth"),
                        new QuasiIdWarning("addresses", "postal_code", "varchar",
                                Strategy.KEEP, "postal"),
                        new QuasiIdWarning("users", "salary_eur", "numeric",
                                Strategy.HASH, "salary")
                ),
                Instant.parse("2026-05-13T10:00:00Z")
        );
        assertThat(ReportTemplateModelFactory.derivePlanQuasiIdStats(plan))
                .isEqualTo("2 colonne(s) KEEP à risque de ré-identification");
    }

    @Test
    void derivePlanQuasiIdStats_returnsEmpty_whenNoWarnings() {
        PlanSummary plan = new PlanSummary(
                "src", List.of(), List.of(), List.of(),
                Instant.parse("2026-05-13T10:00:00Z"));
        assertThat(ReportTemplateModelFactory.derivePlanQuasiIdStats(plan)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Strategy.descriptionOf safe fallback
    // -------------------------------------------------------------------------

    @Test
    void strategyDescriptionOf_returnsKnownDescription() {
        assertThat(Strategy.descriptionOf("HASH"))
                .contains("SHA-256");
        assertThat(Strategy.descriptionOf("FAKE"))
                .contains("dictionnaire de substitution");
    }

    @Test
    void strategyDescriptionOf_fallsBackForUnknownNames() {
        assertThat(Strategy.descriptionOf("CUSTOM_X"))
                .isEqualTo("Stratégie personnalisée");
        assertThat(Strategy.descriptionOf("")).isEqualTo("Stratégie personnalisée");
        assertThat(Strategy.descriptionOf(null)).isEqualTo("Stratégie personnalisée");
    }

    // -------------------------------------------------------------------------
    // PhaseTimings.compactClock (Q1)
    // -------------------------------------------------------------------------

    @Test
    void compactClock_subSecond() {
        assertThat(new PhaseTimings(0, 0, 0, 500).compactClock()).isEqualTo("0:00.5 s");
    }

    @Test
    void compactClock_underOneMinute() {
        assertThat(new PhaseTimings(0, 0, 0, 23_400).compactClock()).isEqualTo("0:23.4 s");
    }

    @Test
    void compactClock_minutesAndSeconds() {
        // 4 min 23 s 400 ms = 263_400 ms
        assertThat(new PhaseTimings(0, 0, 0, 263_400).compactClock()).isEqualTo("4:23.4 s");
    }

    @Test
    void compactClock_hoursMinutesSeconds() {
        // 1 h 5 min 12 s = 3_912_000 ms
        assertThat(new PhaseTimings(0, 0, 0, 3_912_000).compactClock()).isEqualTo("1:05:12 s");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static ExecutionSummary summaryWith(boolean success, String cause, long batchErrors) {
        List<TableStats> stats = batchErrors > 0
                ? List.of(new TableStats("payments", 100, 0, 0, 0, batchErrors))
                : List.of(new TableStats("users", 10, 0, 10, 0, 0));
        return new ExecutionSummary(
                "src", "dst", success, cause,
                new PhaseTimings(10, 10, 10, 30),
                stats, List.of(),
                Instant.parse("2026-05-13T10:00:00Z")
        );
    }

    private static long batchErrors(long n) {
        return n;
    }

    private static ExecutionSummary summaryWithTiming(PhaseTimings timings, long inserted) {
        return new ExecutionSummary(
                "src", "dst", true, null,
                timings,
                List.of(new TableStats("t", inserted, 0, inserted, 0, 0)),
                List.of(),
                Instant.parse("2026-05-13T10:00:00Z")
        );
    }
}
