package com.fungle.brume.config.model;

import java.util.List;

/**
 * Anonymization section of the Brume configuration file.
 *
 * <p>Declares:
 * <ul>
 *   <li>Cross-table column linkages that must produce the same anonymized value
 *       (via {@link LinkedColumnsConfig} and the {@code SubstitutionDictionary})</li>
 *   <li>Per-table, per-column anonymization rules</li>
 * </ul>
 *
 * @param linkedColumns groups of columns that must share the same fake value across tables;
 *                      defaults to an empty list if absent from the YAML file
 * @param tables        per-table anonymization rules; defaults to an empty list if absent
 */
public record AnonymizationConfig(
        List<LinkedColumnsConfig> linkedColumns,
        List<TableAnonymizationConfig> tables) {

    /** Normalizes null lists to empty lists so callers never receive {@code null}. */
    public AnonymizationConfig {
        if (linkedColumns == null) linkedColumns = List.of();
        if (tables == null) tables = List.of();
    }
}

