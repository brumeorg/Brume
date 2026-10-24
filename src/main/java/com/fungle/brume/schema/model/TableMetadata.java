package com.fungle.brume.schema.model;

import java.util.List;
import java.util.Set;

/**
 * Metadata for a single database table: its columns, outgoing foreign keys, and primary key.
 *
 * <p>The primary key is modelled as an <em>ordered list</em> of column names
 * ({@link #primaryKeyColumns}), so the three cases are distinguishable by construction
 * (#81b / ADR-0042):
 * <ul>
 *   <li>empty list — the table has no primary key ;</li>
 *   <li>one element — single-column primary key (the common case) ;</li>
 *   <li>two or more — composite primary key.</li>
 * </ul>
 *
 * <p>Before #81b a single {@code String primaryKeyColumn} collapsed both "composite PK" and
 * "no PK" to {@code null} — indistinguishable. Call sites that only handle single-column PKs
 * should use {@link #singlePrimaryKeyColumn()} which preserves the old semantics (returns the
 * column iff there is exactly one, else {@code null}).
 *
 * @param name              unqualified table name
 * @param columns           ordered list of column metadata as returned by {@code information_schema.columns}
 * @param foreignKeys       list of foreign keys declared on this table (outgoing edges in the FK graph)
 * @param primaryKeyColumns ordered primary-key column names (empty = no PK, size ≥ 2 = composite)
 * @param uniqueColumns     columns that are unique on their own : single-column PK, UNIQUE
 *                          constraint or unique index (#29a — fake values there must not collide)
 * @param uniqueKeys        every unique index usable from the values, PK included, composite or
 *                          not (#29b — the DUMP sink drops a row that would break one)
 */
public record TableMetadata(
        String name,
        List<ColumnMetadata> columns,
        List<ForeignKey> foreignKeys,
        List<String> primaryKeyColumns,
        Set<String> uniqueColumns,
        List<UniqueKey> uniqueKeys) {

    public TableMetadata {
        primaryKeyColumns = primaryKeyColumns == null ? List.of() : List.copyOf(primaryKeyColumns);
        uniqueColumns = uniqueColumns == null ? Set.of() : Set.copyOf(uniqueColumns);
        uniqueKeys = uniqueKeys == null ? List.of() : List.copyOf(uniqueKeys);
    }

    /** Compatibility constructor — no unique-key information (pre-#29b call sites). */
    public TableMetadata(String name, List<ColumnMetadata> columns, List<ForeignKey> foreignKeys,
                         List<String> primaryKeyColumns, Set<String> uniqueColumns) {
        this(name, columns, foreignKeys, primaryKeyColumns, uniqueColumns, List.of());
    }

    /** Compatibility constructor — no unique-column information (pre-#29a call sites). */
    public TableMetadata(String name, List<ColumnMetadata> columns,
                         List<ForeignKey> foreignKeys, List<String> primaryKeyColumns) {
        this(name, columns, foreignKeys, primaryKeyColumns, Set.of());
    }

    /**
     * Compatibility constructor — single-column PK as a bare {@code String} (or {@code null}
     * for no PK). Keeps the many existing call sites that predate the {@code List<String>}
     * model compiling unchanged.
     *
     * @param primaryKeyColumn the single PK column name, or {@code null} if the table has no
     *                         single-column primary key
     */
    public TableMetadata(String name, List<ColumnMetadata> columns,
                         List<ForeignKey> foreignKeys, String primaryKeyColumn) {
        this(name, columns, foreignKeys,
                primaryKeyColumn == null ? List.of() : List.of(primaryKeyColumn));
    }

    /**
     * The primary-key column name iff this table has a <em>single-column</em> primary key,
     * otherwise {@code null} (composite PK or no PK). Preserves the pre-#81b semantics of the
     * former {@code primaryKeyColumn()} accessor.
     *
     * @return the single PK column, or {@code null}
     */
    public String singlePrimaryKeyColumn() {
        return primaryKeyColumns.size() == 1 ? primaryKeyColumns.getFirst() : null;
    }

    /** @return {@code true} if this table has a composite (≥ 2 column) primary key */
    public boolean hasCompositePrimaryKey() {
        return primaryKeyColumns.size() >= 2;
    }

    /** @return {@code true} if this table declares no primary key at all */
    public boolean hasNoPrimaryKey() {
        return primaryKeyColumns.isEmpty();
    }
}
