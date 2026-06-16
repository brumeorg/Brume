package com.fungle.brume.audit.anonymity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Immutable specification of an audit run, built from the CLI flags and passed to
 * {@link AnonymityAuditor}. Keeps {@link AnonymityAuditor} testable without picocli.
 *
 * @param explicitQuasiId  table → ordered quasi-id columns, as supplied via
 *                         {@code --quasi-id "users:birth_date,postal_code"}. Empty
 *                         when the user did not pass {@code --quasi-id}.
 * @param autoDetect       {@code true} when {@code --auto-detect-quasi-id} was set.
 *                         When both {@code explicitQuasiId} is non-empty AND
 *                         {@code autoDetect} is {@code true}, the explicit list
 *                         wins (auto-detection is skipped).
 * @param strict           {@code true} when {@code --strict} was set
 * @param kMin             threshold for {@code --strict}, defaults to 5 (Sweeney 2002)
 * @param sampleRate       fraction in {@code (0, 1]} — {@code 1.0} means full scan
 * @param outputJsonPath   path to write the JSON report ({@code null} = no file)
 * @param outputMarkdownPath path to write the Markdown DPO report ({@code null} = no file)
 */
public record AnonymityAuditSpec(
        Map<String, List<String>> explicitQuasiId,
        boolean autoDetect,
        boolean strict,
        long kMin,
        double sampleRate,
        Path outputJsonPath,
        Path outputMarkdownPath) {

    public AnonymityAuditSpec {
        explicitQuasiId = Map.copyOf(explicitQuasiId);
    }

    /** Returns {@code true} when the user provided neither explicit list nor auto-detect. */
    public boolean isUnderspecified() {
        return explicitQuasiId.isEmpty() && !autoDetect;
    }
}
