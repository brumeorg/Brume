package com.fungle.brume.report;

/**
 * Immutable counter for a single (table, column, strategy) tuple.
 *
 * <p>One {@code StrategyUsage} entry is produced per unique combination of table name,
 * column name and anonymization strategy. The {@code count} field represents the total
 * number of individual cell values processed with that strategy during the run.
 *
 * @param table    name of the table containing the column
 * @param column   name of the column being anonymized
 * @param strategy name of the anonymization strategy applied (e.g. {@code "FAKE"},
 *                 {@code "FPE_ID"}, {@code "KEEP"}, etc.)
 * @param count    number of cell values processed with this strategy
 */
public record StrategyUsage(
        String table,
        String column,
        String strategy,
        long count
) {}

