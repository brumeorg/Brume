package com.fungle.brume.schema.model;

import java.util.List;

/**
 * Metadata for a single database table: its columns, outgoing foreign keys, and primary key.
 *
 * @param name             unqualified table name
 * @param columns          ordered list of column metadata as returned by {@code information_schema.columns}
 * @param foreignKeys      list of foreign keys declared on this table (outgoing edges in the FK graph)
 * @param primaryKeyColumn name of the single-column primary key, or {@code null} if the table has
 *                         a composite PK or no PK at all
 */
public record TableMetadata(
        String name,
        List<ColumnMetadata> columns,
        List<ForeignKey> foreignKeys,
        String primaryKeyColumn) {
}

