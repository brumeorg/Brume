package com.fungle.brume.schema.model;

import java.util.List;

/**
 * One unique index of a table, primary key included (#29b) : the ordered key columns, and
 * whether NULLs count as equal ({@code NULLS NOT DISTINCT}, PostgreSQL 15+). Expression and
 * partial indexes are not represented : their uniqueness cannot be checked from the values.
 *
 * @param columns          key columns, in index order
 * @param nullsNotDistinct {@code true} when two NULLs collide
 */
public record UniqueKey(List<String> columns, boolean nullsNotDistinct) {

    public UniqueKey {
        columns = List.copyOf(columns);
    }
}
