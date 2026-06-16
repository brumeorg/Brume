package com.fungle.brume.plan;

/**
 * Strategy used by {@link PlanEstimator} to compute planned row counts.
 *
 * <ul>
 *   <li>{@link #EXACT} — runs {@code COUNT(*)} (and JOIN-based {@code COUNT DISTINCT}
 *       for FK parents, recursive CTE for self-ref). Accurate but can take
 *       5-10 minutes per table on databases with billions of rows.</li>
 *   <li>{@link #ESTIMATE} — reads {@code pg_class.reltuples} (autovacuum-maintained
 *       statistic, ±20% accuracy, &lt; 10 ms per table). Filters and FK references
 *       are ignored; the result is an upper bound suitable for
 *       {@code max-target-rows} warnings, not for exact accounting.</li>
 * </ul>
 */
public enum PlanMode {
    EXACT,
    ESTIMATE
}
