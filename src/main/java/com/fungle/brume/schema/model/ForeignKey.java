package com.fungle.brume.schema.model;
/**
 * Represents a foreign key relationship between two tables in the database schema.
 *
 * <p>A foreign key means: a row in {@code fromTable} references a row in {@code toTable}
 * via the column pair {@code fromColumn -> toColumn}. In insertion order terms,
 * {@code toTable} (the parent) must be inserted before {@code fromTable} (the child).
 *
 * @param fromTable  name of the child table that holds the FK column
 * @param fromColumn name of the FK column in the child table
 * @param toTable    name of the parent table being referenced
 * @param toColumn   name of the referenced column in the parent table (usually the PK)
 */
public record ForeignKey(String fromTable, String fromColumn, String toTable, String toColumn) {
}
