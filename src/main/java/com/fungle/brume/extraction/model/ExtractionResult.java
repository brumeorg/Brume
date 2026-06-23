package com.fungle.brume.extraction.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;

/**
 * Mutable container for all rows extracted from the source database.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap} and
 * {@link Collections#synchronizedList(List)} to support parallel FK parent resolution.
 *
 * <p>The {@code pkIndex} is a flat set of typed {@link PkKey} records — {@code (table,
 * columns, values)} — used as the single source of truth for duplicate detection. The key
 * is a column/value <em>tuple</em>, so it covers single-column and composite primary keys
 * uniformly (#81b / ADR-0042). Always consult it via {@link #containsPrimaryKey} or the
 * atomic {@link #tryAddWithPk}.
 *
 * <p>#79c — pre-fix used string concatenation {@code table + "." + pkColumn + "." + pkValue}
 * which collided when an identifier contained {@code "."}. Records have proper {@code equals}/
 * {@code hashCode} semantics with no separator ambiguity.
 */
public class ExtractionResult {

    /**
     * Composite primary-key identifier — used as the {@code pkIndex} set key. Records have
     * value-based {@code equals}/{@code hashCode}, so two {@code PkKey} instances are equal
     * iff their table and the ordered {@code columns}/{@code values} tuples are equal. For a
     * single-column PK the two lists hold exactly one element.
     */
    public record PkKey(String table, List<String> columns, List<Object> values) {
        public PkKey {
            columns = List.copyOf(columns);
            values = List.copyOf(values);
        }
    }

    private final ConcurrentHashMap<String, List<ExtractedRow>> rowsByTable = new ConcurrentHashMap<>();
    private final Set<PkKey> pkIndex = ConcurrentHashMap.newKeySet();

    public void add(ExtractedRow row) {
        rowsByTable.computeIfAbsent(row.table(), _ -> Collections.synchronizedList(new ArrayList<>())).add(row);
    }

    /**
     * Adds a row and registers its PK in the index.
     * Used during <em>initial extraction</em> where uniqueness is guaranteed by the DB.
     * Does NOT prevent duplicates — call {@link #tryAddWithPk} for concurrent FK resolution.
     */
    public void addWithPk(ExtractedRow row, List<String> pkColumns) {
        List<Object> values = pkTuple(row, pkColumns);
        if (values != null) {
            pkIndex.add(new PkKey(row.table(), pkColumns, values));
        }
        rowsByTable.computeIfAbsent(row.table(), _ -> Collections.synchronizedList(new ArrayList<>())).add(row);
    }

    /** Single-column convenience overload — see {@link #addWithPk(ExtractedRow, List)}. */
    public void addWithPk(ExtractedRow row, String pkColumn) {
        addWithPk(row, List.of(pkColumn));
    }

    /**
     * Atomically checks whether a row with this PK is already present and, if not, adds it.
     *
     * <p>Thread-safe: uses {@link Set#add} on the concurrent {@code pkIndex} as the gate.
     * The first thread to add a given key wins; any concurrent thread that arrives with the
     * same key will find it already present and returns {@code false} without inserting.
     *
     * @return {@code true} if the row was inserted, {@code false} if it was already present
     */
    public boolean tryAddWithPk(ExtractedRow row, List<String> pkColumns) {
        List<Object> values = pkTuple(row, pkColumns);
        if (values == null) {
            // Incomplete key (no PK columns, or a null value in the tuple) — add unconditionally.
            rowsByTable.computeIfAbsent(row.table(), _ -> Collections.synchronizedList(new ArrayList<>())).add(row);
            return true;
        }
        PkKey key = new PkKey(row.table(), pkColumns, values);
        if (pkIndex.add(key)) {
            // We are the first to claim this key — safe to add the row
            rowsByTable.computeIfAbsent(row.table(), _ -> Collections.synchronizedList(new ArrayList<>())).add(row);
            return true;
        }
        return false; // Already present — skip to avoid duplicate
    }

    /** Single-column convenience overload — see {@link #tryAddWithPk(ExtractedRow, List)}. */
    public boolean tryAddWithPk(ExtractedRow row, String pkColumn) {
        return tryAddWithPk(row, List.of(pkColumn));
    }

    public boolean containsPrimaryKey(String table, List<String> columns, List<Object> values) {
        return pkIndex.contains(new PkKey(table, columns, values));
    }

    /** Single-column convenience overload — see {@link #containsPrimaryKey(String, List, List)}. */
    public boolean containsPrimaryKey(String table, String pkColumn, Object pkValue) {
        if (pkValue == null) {
            return false; // a null PK value is never indexed (see pkTuple)
        }
        return containsPrimaryKey(table, List.of(pkColumn), List.of(pkValue));
    }

    /**
     * Builds the ordered PK value tuple for {@code row}, or {@code null} when the key is
     * incomplete: no columns, or any column value is {@code null} (a row that cannot be
     * deduplicated reliably — e.g. a nullable FK column pointing at an optional parent).
     */
    private static List<Object> pkTuple(ExtractedRow row, List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            return null;
        }
        List<Object> values = new ArrayList<>(pkColumns.size());
        for (String col : pkColumns) {
            Object v = row.data().get(col);
            if (v == null) {
                return null;
            }
            values.add(v);
        }
        return values;
    }

    public List<ExtractedRow> getRows(String table) {
        return rowsByTable.getOrDefault(table, List.of());
    }

    public Set<String> allTables() {
        return rowsByTable.keySet();
    }

    public int totalRowCount() {
        return rowsByTable.values().stream().mapToInt(List::size).sum();
    }

    public OrderedExtractionResult toOrdered(List<String> tableOrder) {
        return new OrderedExtractionResult(rowsByTable, tableOrder);
    }
}
