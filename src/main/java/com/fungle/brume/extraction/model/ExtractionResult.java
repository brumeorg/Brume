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
 * pkColumn, pkValue)} — used as the single source of truth for duplicate detection.
 * Always consult it via {@link #containsPrimaryKey} or the atomic {@link #tryAddWithPk}.
 *
 * <p>#79c — pre-fix used string concatenation {@code table + "." + pkColumn + "." + pkValue}
 * which collided when an identifier contained {@code "."} (e.g. quoted PostgreSQL identifier
 * {@code "users.profile"}). Records have proper {@code equals}/{@code hashCode} semantics
 * with no separator ambiguity.
 */
public class ExtractionResult {

    /**
     * Composite primary-key identifier — used as the {@code pkIndex} set key. Records have
     * value-based {@code equals}/{@code hashCode}, so two {@code PkKey} instances are equal
     * iff all three components are equal (no separator-collision risk vs string concat).
     */
    public record PkKey(String table, String pkColumn, Object pkValue) {
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
    public void addWithPk(ExtractedRow row, String pkColumn) {
        Object pkValue = row.data().get(pkColumn);
        if (pkValue != null) {
            pkIndex.add(new PkKey(row.table(), pkColumn, pkValue));
        }
        rowsByTable.computeIfAbsent(row.table(), _ -> Collections.synchronizedList(new ArrayList<>())).add(row);
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
    public boolean tryAddWithPk(ExtractedRow row, String pkColumn) {
        Object pkValue = row.data().get(pkColumn);
        if (pkValue == null) {
            // No PK value (nullable FK pointing to optional parent) — add unconditionally
            rowsByTable.computeIfAbsent(row.table(), _ -> Collections.synchronizedList(new ArrayList<>())).add(row);
            return true;
        }
        PkKey key = new PkKey(row.table(), pkColumn, pkValue);
        if (pkIndex.add(key)) {
            // We are the first to claim this key — safe to add the row
            rowsByTable.computeIfAbsent(row.table(), _ -> Collections.synchronizedList(new ArrayList<>())).add(row);
            return true;
        }
        return false; // Already present — skip to avoid duplicate
    }

    public boolean containsPrimaryKey(String table, String pkColumn, Object pkValue) {
        return pkIndex.contains(new PkKey(table, pkColumn, pkValue));
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