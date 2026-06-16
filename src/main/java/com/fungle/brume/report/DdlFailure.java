package com.fungle.brume.report;

/**
 * One DDL statement that pg_dump produced and that failed to apply on the target
 * while {@code replication.ddl-error-mode=LENIENT} was in effect. STRICT mode never
 * produces a {@code DdlFailure} — it throws at the first error instead.
 *
 * <p>Surfaced via {@link ExecutionSummary#ddlFailures()} so the operator can inspect
 * what was silently skipped — the original motivation for #28 (A17 audit STRICT/LENIENT).
 * Without this surface, LENIENT mode hides drama: a CREATE EXTENSION that fails because
 * the cible role lacks privileges still logs WARN, but the rapport had no record of it
 * pre-#28.
 *
 * @param statementIndex 1-based position of the statement in the pg_dump output
 *                       (matches what the WARN log line uses, for cross-reference)
 * @param sqlPreview     first ~80 characters of the SQL statement, single line —
 *                       enough to identify the kind of statement (CREATE TABLE foo,
 *                       ALTER TABLE foo ADD CONSTRAINT, ...) without bloating the rapport
 * @param errorMessage   what PostgreSQL returned (typically the same line that ended up
 *                       in the WARN log; first {@code SQLException.getMessage()} only)
 */
public record DdlFailure(int statementIndex, String sqlPreview, String errorMessage) {
}
