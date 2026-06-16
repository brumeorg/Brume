package com.fungle.brume.report;

import java.util.List;

/**
 * Immutable snapshot of the plan vs actual comparison, produced after the Brume pipeline
 * completes.
 *
 * <p>Combines the pre-execution {@link PlanSummary} (planned row counts) with the
 * post-execution {@link ExecutionSummary} (actual row counts, inserts, conflicts),
 * the PII warnings detected before the run, and the natural-language {@link Insight}s
 * generated from the comparison metrics.
 *
 * @param plan        the pre-execution plan snapshot
 * @param execution   the post-execution summary
 * @param rows        one {@link ComparisonRow} per table in scope
 * @param piiWarnings PII columns detected before the run with no anonymization rule
 * @param insights    natural-language observations generated from the comparison data
 */
public record ComparisonSummary(
        PlanSummary plan,
        ExecutionSummary execution,
        List<ComparisonRow> rows,
        List<PiiWarning> piiWarnings,
        List<Insight> insights
) {

    /**
     * Returns the total number of rows that were planned across all tables.
     *
     * @return {@link PlanSummary#totalPlanned()}
     */
    public long totalPlanned() {
        return plan.totalPlanned();
    }

    /**
     * Returns the total number of rows actually extracted during the run.
     *
     * @return {@link ExecutionSummary#totalExtracted()}
     */
    public long totalActual() {
        return execution.totalExtracted();
    }

    /**
     * Returns {@code true} if at least one row has a delta greater than 1% in absolute value.
     *
     * @return {@code true} if any {@link ComparisonRow#deltaPercent()} exceeds ±1%
     */
    public boolean hasDeltas() {
        return rows.stream().anyMatch(r -> Math.abs(r.deltaPercent()) > 1.0);
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
     * Returns {@code true} if at least one natural-language insight was generated.
     *
     * @return {@code true} if the insights list is non-empty
     */
    public boolean hasInsights() {
        return insights != null && !insights.isEmpty();
    }
}
