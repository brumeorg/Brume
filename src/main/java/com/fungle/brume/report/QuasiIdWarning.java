package com.fungle.brume.report;

import com.fungle.brume.config.model.Strategy;

/**
 * Represents a single quasi-identifier warning for a database column whose name
 * matches a known quasi-id heuristic and whose effective anonymization strategy
 * does not break correlation (KEEP, HASH, FPE_ID, FPE_UUID by default).
 *
 * <p>Produced by {@link com.fungle.brume.audit.QuasiIdDetector} during the
 * pre-execution plan phase. One {@code QuasiIdWarning} is emitted per matching
 * column whose effective strategy is not in
 * {@code brume.audit.neutralizing-strategies}.
 *
 * <p>Differs from {@link PiiWarning} in semantics: PII = "column has no rule
 * declared, name matches a sensitive pattern" ; quasi-id = "column has a rule
 * (or default KEEP) but the strategy does not destroy correlation, and the name
 * looks like a re-identification vector (date of birth, postal code, salary…)".
 *
 * <p>Tracked under #21c. Cf. ADR-0035 for the rationale on which strategies
 * preserve vs neutralize correlation (HMAC-deterministic = preserved by
 * construction).
 *
 * @param table             unqualified table name containing the suspect column
 * @param column            column name that matched a quasi-id heuristic pattern
 * @param dataType          PostgreSQL data type of the column (e.g. {@code "date"},
 *                          {@code "integer"}, {@code "character varying"})
 * @param effectiveStrategy strategy that will be applied to the column. {@code null}
 *                          means the column has no explicit rule (KEEP implicit).
 * @param matchedPattern    the first quasi-id pattern that matched the column name
 *                          (e.g. {@code "birth"})
 */
public record QuasiIdWarning(
        String table,
        String column,
        String dataType,
        Strategy effectiveStrategy,
        String matchedPattern) {
}
