package com.fungle.brume.report;

/**
 * Planned row count for a single table, computed by {@link com.fungle.brume.plan.PlanEstimator}
 * before any extraction takes place.
 *
 * <p>Mirrors the three-phase extraction pipeline of {@link com.fungle.brume.extraction.ExtractionEngine}:
 * direct rules, FK-child resolution (inverse FK walk), and FK-parent resolution.
 *
 * @param table              unqualified table name
 * @param plannedDirect      count from direct extraction rule (COUNT(*) [WHERE filter]);
 *                           {@code 0} if not a direct target; {@code -1L} if query failed
 * @param plannedFkParents   count of distinct rows resolved as FK parent;
 *                           {@code 0} if not a FK parent role; {@code -1L} if query failed
 * @param plannedFkChildren  count of distinct rows resolved as FK child (inverse FK walk);
 *                           {@code 0} if not a FK child role; {@code -1L} if query failed
 * @param origin             combination of {@code "direct"}, {@code "fk-child"}, {@code "fk-parent"}
 *                           joined by {@code "+"} in canonical order, e.g. {@code "direct+fk-child"}
 */
public record PlanTableStats(
        String table,
        long plannedDirect,
        long plannedFkParents,
        long plannedFkChildren,
        String origin
) {
    /** Backward-compatible constructor — {@code plannedFkChildren} defaults to {@code 0}. */
    public PlanTableStats(String table, long plannedDirect, long plannedFkParents, String origin) {
        this(table, plannedDirect, plannedFkParents, 0L, origin);
    }

    /**
     * Returns the total planned row count for this table.
     *
     * <p>Returns {@code -1L} if any sub-count is negative (query failed).
     *
     * @return sum of {@code plannedDirect}, {@code plannedFkParents}, and {@code plannedFkChildren},
     *         or {@code -1L} if any sub-count is negative
     */
    public long plannedTotal() {
        if (plannedDirect < 0 || plannedFkParents < 0 || plannedFkChildren < 0) {
            return -1L;
        }
        return plannedDirect + plannedFkParents + plannedFkChildren;
    }
}

