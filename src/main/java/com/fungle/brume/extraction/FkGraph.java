package com.fungle.brume.extraction;

import com.fungle.brume.config.model.OrphanPolicy;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table-level view of the FK graph used by {@link FkTraversal} (ADR-0050) : which tables a
 * row walk can still reach, and the table set an ESTIMATE {@code plan} walks without reading
 * any row.
 *
 * <p>Foreign keys are listed in a fixed order (child table, FK columns, parent table) so that
 * every walk over them is deterministic.
 */
public final class FkGraph {

    private final List<ForeignKey> foreignKeys;
    private final Map<String, List<ForeignKey>> byChild = new HashMap<>();
    private final Map<String, List<ForeignKey>> byParent = new HashMap<>();

    public FkGraph(DatabaseSchema schema) {
        List<ForeignKey> all = new ArrayList<>();
        for (TableMetadata table : schema.tables().values()) {
            if (table.foreignKeys() != null) all.addAll(table.foreignKeys());
        }
        all.sort(Comparator.comparing(ForeignKey::fromTable)
                .thenComparing(fk -> String.join(",", fk.fromColumns()))
                .thenComparing(ForeignKey::toTable)
                .thenComparing(fk -> String.join(",", fk.toColumns())));
        this.foreignKeys = List.copyOf(all);
        for (ForeignKey fk : foreignKeys) {
            byChild.computeIfAbsent(fk.fromTable(), _ -> new ArrayList<>()).add(fk);
            byParent.computeIfAbsent(fk.toTable(), _ -> new ArrayList<>()).add(fk);
        }
    }

    /** Every FK of the schema, in the deterministic walk order. */
    List<ForeignKey> foreignKeys() {
        return foreignKeys;
    }

    /** FKs held by {@code table} (edges to its parents). */
    List<ForeignKey> parentEdges(String table) {
        return byChild.getOrDefault(table, List.of());
    }

    /** FKs pointing to {@code table} (edges to its children). */
    List<ForeignKey> childEdges(String table) {
        return byParent.getOrDefault(table, List.of());
    }

    /**
     * Tables that rows of {@code from} can still lead to : 1 to {@code steps} FK hops in
     * either direction, plus — when {@code climbBeyond} — every ancestor of those tables
     * (the unbounded parent climb of {@link OrphanPolicy#KEEP_PARENTS}).
     */
    Set<String> reachable(Collection<String> from, int steps, boolean climbBeyond) {
        Set<String> reached = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>(from);
        for (int i = 0; i < steps && !frontier.isEmpty(); i++) {
            Set<String> next = new LinkedHashSet<>();
            for (String table : frontier) {
                parentEdges(table).forEach(fk -> next.add(fk.toTable()));
                childEdges(table).forEach(fk -> next.add(fk.fromTable()));
            }
            next.removeAll(reached);
            reached.addAll(next);
            frontier = next;
        }
        if (climbBeyond) {
            Set<String> base = new LinkedHashSet<>(from);
            base.addAll(reached);
            reached.addAll(ancestors(base));
        }
        return reached;
    }

    /** Tables reachable from {@code from} by climbing one or more FKs. */
    Set<String> ancestors(Collection<String> from) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(from);
        while (!todo.isEmpty()) {
            for (ForeignKey fk : parentEdges(todo.poll())) {
                if (found.add(fk.toTable())) todo.add(fk.toTable());
            }
        }
        return found;
    }

    /**
     * Table-level version of the row walk, for the ESTIMATE {@code plan} : every table within
     * {@code radius} hops of a seed table, then their ancestors under KEEP_PARENTS. Each table
     * maps to how it is first reached.
     */
    public Map<String, FkTraversal.Origin> tablesWithin(Collection<String> seedTables, int radius,
                                                        OrphanPolicy policy) {
        Map<String, FkTraversal.Origin> origins = new LinkedHashMap<>();
        seedTables.forEach(t -> origins.put(t, FkTraversal.Origin.DIRECT));
        Set<String> frontier = new LinkedHashSet<>(seedTables);
        for (int i = 0; i < radius && !frontier.isEmpty(); i++) {
            Set<String> next = new LinkedHashSet<>();
            for (String table : frontier) {
                for (ForeignKey fk : parentEdges(table)) {
                    if (origins.putIfAbsent(fk.toTable(), FkTraversal.Origin.FK_PARENT) == null) next.add(fk.toTable());
                }
                for (ForeignKey fk : childEdges(table)) {
                    if (origins.putIfAbsent(fk.fromTable(), FkTraversal.Origin.FK_CHILD) == null) next.add(fk.fromTable());
                }
            }
            frontier = next;
        }
        if (policy == OrphanPolicy.KEEP_PARENTS) {
            for (String ancestor : ancestors(List.copyOf(origins.keySet()))) {
                origins.putIfAbsent(ancestor, FkTraversal.Origin.FK_PARENT);
            }
        }
        return origins;
    }
}
