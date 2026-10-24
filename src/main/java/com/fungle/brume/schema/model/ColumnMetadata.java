package com.fungle.brume.schema.model;

/**
 * Metadata for a single column within a database table.
 *
 * @param name      column name as reported by {@code information_schema.columns}
 * @param dataType  PostgreSQL data type name (e.g. {@code "bigint"}, {@code "varchar"}, {@code "jsonb"})
 * @param nullable  {@code true} if the column accepts {@code NULL} values ({@code IS_NULLABLE = 'YES'})
 * @param maxLength declared length of a {@code varchar(n)} / {@code char(n)} column
 *                  ({@code character_maximum_length}), {@code null} when unbounded or not textual
 */
public record ColumnMetadata(String name, String dataType, boolean nullable, Integer maxLength) {

    public ColumnMetadata(String name, String dataType, boolean nullable) {
        this(name, dataType, nullable, null);
    }
}

