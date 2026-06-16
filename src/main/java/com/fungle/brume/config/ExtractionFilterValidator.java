package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the {@code extraction.tables[].filter} string before it is interpolated into
 * a SQL {@code WHERE} clause.
 *
 * <p>Brume deliberately interpolates the filter raw — parameterising an arbitrary {@code WHERE}
 * predicate is not feasible — but bounds the input with a defense-in-depth heuristic so that
 * a templated {@code brume.yml} cannot smuggle in DDL, DML, or comment-based bypasses.
 *
 * <p>Called from {@link ConfigValidator#validate(com.fungle.brume.config.model.AnonymizerConfig)}
 * fail-fast, before any database connection is opened. {@code PlanEstimator} and
 * {@link com.fungle.brume.extraction.CursorReader} can therefore trust that any filter they see
 * has already passed this gate (no double validation).
 *
 * <p>Rules — a filter is rejected if any of the following hold (evaluated on the trimmed input):
 * <ol>
 *   <li>contains a {@code ;} <em>outside</em> of a single-quoted SQL literal,</li>
 *   <li>contains a SQL comment marker — {@code --}, {@code /*}, or {@code *}{@code /},</li>
 *   <li>contains a forbidden DML/DDL keyword (case-insensitive, word-boundary matched) :
 *       {@code INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, GRANT, REVOKE, CREATE, COPY,
 *       CALL, DO, EXECUTE, MERGE},</li>
 *   <li>length > 1000 characters,</li>
 *   <li>contains a NUL byte (U+0000).</li>
 * </ol>
 *
 * <p>The heuristic is intentionally a denylist (and a bit conservative) — the legitimate filter
 * shapes ({@code col >= '2025-01-01'}, {@code total > 0 AND status = 'paid'},
 * {@code id IN (SELECT id FROM child WHERE …)}) all pass.
 *
 * <p>Sourced from ticket {@code work/pro-ready/tickets/T01-sql-injection-where-filter.md}
 * and ADR-0017.
 */
public final class ExtractionFilterValidator {

    private static final int MAX_FILTER_LENGTH = 1000;

    /** Forbidden keywords — case-insensitive, word-boundary matched. */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
            "GRANT", "REVOKE", "CREATE", "COPY", "CALL", "DO", "EXECUTE", "MERGE"
    );

    /** Pattern that matches any forbidden keyword as a whole word, case-insensitive. */
    private static final Pattern FORBIDDEN_KEYWORDS_PATTERN = Pattern.compile(
            "(?i)\\b(" + String.join("|", FORBIDDEN_KEYWORDS) + ")\\b");

    private ExtractionFilterValidator() {}

    /**
     * Throws a {@link ConfigurationException} if {@code filter} fails any rule. A {@code null}
     * or blank filter is accepted (= "no WHERE clause").
     *
     * @param tableName name of the enclosing extraction table — used in the error message
     * @param filter    raw filter string from {@code extraction.tables[].filter}
     * @throws ConfigurationException if the filter contains injection markers
     */
    public static void validate(String tableName, String filter) {
        if (filter == null || filter.isBlank()) {
            return;
        }
        String trimmed = filter.trim();

        if (trimmed.length() > MAX_FILTER_LENGTH) {
            throw reject(tableName, filter,
                    "exceeds maximum length of " + MAX_FILTER_LENGTH + " characters (was " + trimmed.length() + ")");
        }
        if (trimmed.indexOf('\0') >= 0) {
            throw reject(tableName, filter, "contains a NUL byte");
        }
        if (containsCommentMarker(trimmed)) {
            throw reject(tableName, filter, "contains a SQL comment marker (--, /*, or */)");
        }
        if (containsSemicolonOutsideStringLiteral(trimmed)) {
            throw reject(tableName, filter, "contains ';' outside of a string literal");
        }
        var matcher = FORBIDDEN_KEYWORDS_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            throw reject(tableName, filter,
                    "contains forbidden keyword '" + matcher.group(1).toUpperCase() + "'");
        }
    }

    private static boolean containsCommentMarker(String s) {
        return s.contains("--") || s.contains("/*") || s.contains("*/");
    }

    /**
     * Returns {@code true} iff {@code s} contains a {@code ;} that is not inside a single-quoted
     * literal. PostgreSQL escapes a single quote inside a literal by doubling it ({@code ''}) —
     * we walk the string tracking the quote state and treat {@code ''} as still inside the
     * literal (i.e. the doubled quote does not toggle state).
     */
    private static boolean containsSemicolonOutsideStringLiteral(String s) {
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                // Doubled '' inside a literal escapes a quote — stay inside the string
                if (inString && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }
            if (c == ';' && !inString) {
                return true;
            }
        }
        return false;
    }

    private static ConfigurationException reject(String tableName, String filter, String reason) {
        String snippet = filter.length() > 80 ? filter.substring(0, 77) + "..." : filter;
        return new ConfigurationException(
                BrumeErrorCode.CONFIG_EXTRACTION_FILTER_INVALID,
                "Invalid filter on extraction.tables['" + tableName + "']: " + reason
                        + ". Filter snippet: <<" + snippet + ">>",
                "Filters are interpolated raw into a SQL WHERE clause, so Brume bans "
                        + "semicolons, comment markers (--, /*, */), DML/DDL keywords (INSERT, "
                        + "UPDATE, DELETE, DROP, ALTER, TRUNCATE, GRANT, REVOKE, CREATE, COPY, "
                        + "CALL, DO, EXECUTE, MERGE), NUL bytes, and filters longer than 1000 "
                        + "chars. Rewrite the predicate using only column comparisons (e.g. "
                        + "\"status = 'paid' AND created_at >= '2025-01-01'\"). See README "
                        + "§extraction.tables and ADR-0017.");
    }
}
