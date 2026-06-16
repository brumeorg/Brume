package com.fungle.brume.config.model;

import java.util.List;

/**
 * Declaration that a set of columns across different tables share the same logical value
 * and must therefore receive the same anonymized output.
 *
 * <p>Example: {@code users.email} and {@code audit_logs.user_email} both represent
 * the same person's email address. Grouping them under the same {@code semanticKey}
 * ensures the {@code SubstitutionDictionary} returns the same fake email for both.
 *
 * @param semanticKey unique identifier for this cross-table linkage
 *                    (e.g. {@code "user_email"}); used as part of the dictionary key
 * @param columns     list of table/column pairs that share this semantic meaning
 */
public record LinkedColumnsConfig(String semanticKey, List<ColumnReference> columns) {
}

