package com.fungle.brume.schema.model;

import java.util.List;

/**
 * Represents a foreign key relationship between two tables in the database schema.
 *
 * <p>A foreign key means: a row in {@code fromTable} references a row in {@code toTable}
 * via the ordered column pairs {@code fromColumns[i] -> toColumns[i]}. In insertion-order
 * terms, {@code toTable} (the parent) must be inserted before {@code fromTable} (the child).
 *
 * <p>A <em>composite</em> foreign key spans more than one column pair. The two column lists
 * are positionally aligned and have equal size (enforced by the compact constructor). Before
 * #81b a FK was single-column only, and a composite FK was loaded as N independent
 * single-column edges — which let {@code FkParentResolver} over-fetch parents matching just
 * one column of the tuple (ADR-0042).
 *
 * @param fromTable   name of the child table that holds the FK column(s)
 * @param fromColumns ordered FK column names in the child table
 * @param toTable     name of the parent table being referenced
 * @param toColumns   ordered referenced column names in the parent table (positionally aligned with {@code fromColumns})
 */
public record ForeignKey(String fromTable, List<String> fromColumns, String toTable, List<String> toColumns) {

    public ForeignKey {
        fromColumns = List.copyOf(fromColumns);
        toColumns = List.copyOf(toColumns);
        if (fromColumns.size() != toColumns.size()) {
            throw new IllegalArgumentException(
                    "Foreign key column lists must be positionally aligned: fromColumns=" + fromColumns
                            + " toColumns=" + toColumns);
        }
        if (fromColumns.isEmpty()) {
            throw new IllegalArgumentException("Foreign key must reference at least one column pair");
        }
    }

    /**
     * Compatibility constructor for the single-column case — keeps the many existing call
     * sites that predate the composite model compiling unchanged.
     */
    public ForeignKey(String fromTable, String fromColumn, String toTable, String toColumn) {
        this(fromTable, List.of(fromColumn), toTable, List.of(toColumn));
    }

    /** @return {@code true} if this foreign key spans more than one column pair */
    public boolean isComposite() {
        return fromColumns.size() > 1;
    }

    /**
     * First child-side FK column. Convenience for single-column consumers (plan estimation,
     * report insights) that handle composite FKs on a best-effort basis. Critical extraction
     * paths use {@link #fromColumns()} to match the full tuple.
     */
    public String fromColumn() {
        return fromColumns.getFirst();
    }

    /**
     * First parent-side referenced column. See {@link #fromColumn()} for the composite caveat.
     */
    public String toColumn() {
        return toColumns.getFirst();
    }
}
