package com.fungle.brume.report;

/**
 * Immutable record representing the plan vs actual comparison for one table.
 *
 * <p>Produced by {@link com.fungle.brume.agent.ReplicationAgent#buildComparison} after the
 * pipeline completes. Consumed by {@link ReportRenderer} and {@link HtmlReportRenderer} to
 * render the "Plan vs Réel" section.
 *
 * @param table        unqualified table name
 * @param planned      planned row count from {@link PlanTableStats#plannedTotal()};
 *                     {@code 0} if the table was not in the plan
 * @param actual       actual row count extracted = {@code TableStats.extracted() + fkParents()};
 *                     {@code 0} if the table was not touched during execution
 * @param inserted     number of rows actually inserted in the target
 * @param conflicts    number of rows silently skipped by {@code ON CONFLICT DO NOTHING}
 * @param deltaPercent percentage delta {@code ((actual - planned) / planned) * 100};
 *                     {@code 0.0} when {@code planned == 0} to avoid division by zero
 */
public record ComparisonRow(
        String table,
        long planned,
        long actual,
        long inserted,
        long conflicts,
        double deltaPercent
) {}

