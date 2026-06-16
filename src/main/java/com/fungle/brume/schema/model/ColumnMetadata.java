package com.fungle.brume.schema.model;

/**
 * Metadata for a single column within a database table.
 *
 * @param name      column name as reported by {@code information_schema.columns}
 * @param dataType  PostgreSQL data type name (e.g. {@code "bigint"}, {@code "varchar"}, {@code "jsonb"})
 * @param nullable  {@code true} if the column accepts {@code NULL} values ({@code IS_NULLABLE = 'YES'})
 */
public record ColumnMetadata(String name, String dataType, boolean nullable) {
}

