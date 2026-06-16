package com.fungle.brume.audit.anonymity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders an {@link AnonymityReport} as pretty-printed JSON via the shared
 * {@link ObjectMapper}.
 *
 * <p>The structure follows the record graph directly :
 * <pre>{@code
 * {
 *   "schema": "...",
 *   "auditedAt": "...",
 *   "strict": false,
 *   "kMin": 5,
 *   "overallKMin": 1,
 *   "policyViolated": true,
 *   "tables": [ { "table": "...", "quasiIdColumns": [...], "distribution": {...}, ... } ]
 * }
 * }</pre>
 *
 * <p>This is Brume's machine-readable contract for the audit ; we document it in
 * the README and add a regression test when it stabilizes (#36 E2 versioning).
 * For V1 we expose the raw record structure and treat each record component name
 * as part of the public API.
 */
public final class AnonymityJsonRenderer {

    private AnonymityJsonRenderer() {}

    public static String render(AnonymityReport report, ObjectMapper objectMapper) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new Envelope(report, report.policyViolated()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize anonymity report as JSON", e);
        }
    }

    /**
     * Wraps the report with a top-level {@code policyViolated} field so JSON
     * consumers can act on it without re-implementing the {@code strict + kMin <
     * overallKMin} logic. The {@code report} field carries the full structure.
     */
    public record Envelope(AnonymityReport report, boolean policyViolated) {}
}
