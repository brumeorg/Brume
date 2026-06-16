package com.fungle.brume.config.model;

import java.util.List;

/**
 * Anonymization rules for all columns of a single table.
 *
 * @param table   unqualified table name (schema is provided separately via
 *                {@code replication.schema} in {@code application.yaml})
 * @param columns ordered list of per-column anonymization rules
 */
public record TableAnonymizationConfig(String table, List<ColumnConfig> columns) {
}

