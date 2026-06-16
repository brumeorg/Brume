package com.fungle.brume.audit.anonymity;

/**
 * A textual recommendation surfaced in the audit report.
 *
 * <p>V1 only emits descriptive recommendations (no auto-applied transformation —
 * see ticket #73 hors scope). Each recommendation carries a severity level so the
 * markdown can sort them and the JSON consumer can filter.
 *
 * @param severity gravity of the finding — see {@link Severity}
 * @param message  human-readable recommendation, ready to display in any of the
 *                 three formats (text / JSON / Markdown)
 */
public record Recommendation(Severity severity, String message) {

    /** Severity of a recommendation. */
    public enum Severity {
        /** Singletons present : actionable RGPD risk. */
        CRITICAL,
        /** k below threshold but no singletons : worth narrowing further. */
        WARNING,
        /** Informational : observed pattern, no action required. */
        INFO
    }
}
