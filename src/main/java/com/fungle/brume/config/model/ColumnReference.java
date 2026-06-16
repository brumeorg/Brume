package com.fungle.brume.config.model;

/**
 * Reference to a specific column in a specific table.
 *
 * <p>Used inside {@link LinkedColumnsConfig} to declare which columns across
 * different tables should be anonymized with the same fake value (same semantic key).
 *
 * @param table  schema-qualified or unqualified table name
 * @param column column name within that table
 */
public record ColumnReference(String table, String column) {
}

