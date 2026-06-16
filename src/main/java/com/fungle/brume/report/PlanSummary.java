package com.fungle.brume.report;

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of the pre-execution plan computed before any database write.
 *
 * <p>Produced by {@link com.fungle.brume.plan.PlanEstimator},
 * {@link com.fungle.brume.plan.PiiDetector} and
 * {@link com.fungle.brume.audit.QuasiIdDetector} after the schema analysis phase.
 * Stored in memory so that OBS-7 can produce a plan vs actual comparison once the
 * pipeline completes.
 *
 * @param sourceSchema     the PostgreSQL schema being replicated (e.g. {@code "test_brume"})
 * @param tableStats       per-table planned row counts, one entry per table in scope
 * @param piiWarnings      columns that matched a PII heuristic but have no anonymization rule
 * @param quasiIdWarnings  columns whose name matches a quasi-id heuristic and whose
 *                         effective strategy does not break correlation (#21c)
 * @param runtimeContext   meta-info (Brume version, config path, faker locale, DDL mode)
 * @param estimatedAt      timestamp when the plan was computed
 */
public record PlanSummary(
        String sourceSchema,
        List<PlanTableStats> tableStats,
        List<PiiWarning> piiWarnings,
        List<QuasiIdWarning> quasiIdWarnings,
        BrumeRuntimeContext runtimeContext,
        Instant estimatedAt
) {

    public PlanSummary {
        runtimeContext = runtimeContext == null ? BrumeRuntimeContext.empty() : runtimeContext;
    }

    /**
     * 5-arg overload (without {@code runtimeContext}) for tests written before the
     * context was added. Defaults the context to {@link BrumeRuntimeContext#empty()}.
     */
    public PlanSummary(String sourceSchema,
                       List<PlanTableStats> tableStats,
                       List<PiiWarning> piiWarnings,
                       List<QuasiIdWarning> quasiIdWarnings,
                       Instant estimatedAt) {
        this(sourceSchema, tableStats, piiWarnings, quasiIdWarnings,
                BrumeRuntimeContext.empty(), estimatedAt);
    }

    /**
     * 4-arg overload kept for tests written before {@code quasiIdWarnings} was added (#21c).
     * Defaults {@code quasiIdWarnings} and {@code runtimeContext} to empty values.
     */
    public PlanSummary(String sourceSchema,
                       List<PlanTableStats> tableStats,
                       List<PiiWarning> piiWarnings,
                       Instant estimatedAt) {
        this(sourceSchema, tableStats, piiWarnings, List.of(),
                BrumeRuntimeContext.empty(), estimatedAt);
    }

    /**
     * Returns the total number of rows planned across all tables.
     *
     * <p>Tables whose count failed ({@code -1L}) are excluded from the sum.
     *
     * @return sum of {@link PlanTableStats#plannedTotal()} for all tables with valid counts
     */
    public long totalPlanned() {
        return tableStats.stream()
                .mapToLong(PlanTableStats::plannedTotal)
                .filter(v -> v >= 0)
                .sum();
    }

    /**
     * Returns {@code true} if at least one PII warning was detected.
     *
     * @return {@code true} if the PII warnings list is non-empty
     */
    public boolean hasPiiWarnings() {
        return piiWarnings != null && !piiWarnings.isEmpty();
    }

    /**
     * Returns {@code true} if at least one quasi-identifier warning was detected.
     */
    public boolean hasQuasiIdWarnings() {
        return quasiIdWarnings != null && !quasiIdWarnings.isEmpty();
    }
}
