package com.fungle.brume.report;

/**
 * Represents a single PII (Personally Identifiable Information) warning for a database column
 * that matches a known PII naming pattern but has no anonymization rule declared in the config.
 *
 * <p>Produced by {@link com.fungle.brume.plan.PiiDetector} during the pre-execution plan phase.
 * One {@code PiiWarning} is emitted per uncovered column that matches a PII heuristic pattern.
 *
 * @param table          unqualified table name containing the suspect column
 * @param column         column name that matched a PII heuristic pattern
 * @param matchedPattern the first PII pattern that matched the column name (e.g. {@code "email"})
 */
public record PiiWarning(String table, String column, String matchedPattern) {
}

