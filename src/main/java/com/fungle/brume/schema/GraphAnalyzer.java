package com.fungle.brume.schema;

import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph algorithms over a {@link DatabaseSchema}: topological sort, cycle detection,
 * and ancestor resolution via foreign key edges.
 *
 * <p>The FK graph is modelled as a directed graph where an edge {@code A -> B} means
 * "table A has a foreign key pointing to table B", i.e. B (parent) must be inserted
 * before A (child).
 *
 * <p>Algorithm choice: DFS-based topological sort (Tarjan-style post-order),
 * as opposed to Kahn's algorithm used in the MVP {@code GraphBuilder}.
 * DFS naturally handles cycle detection in the same pass.
 */
@Component
public class GraphAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GraphAnalyzer.class);

    /**
     * Returns a valid insertion order for all tables in the schema.
     *
     * <p>The algorithm is a DFS-based topological sort (reverse post-order).
     * Tables that are part of a cycle (e.g. self-referential or mutual FKs) are
     * detected, logged as WARN, and appended at the end of the list so that the
     * pipeline can still proceed.
     *
     * @param schema the analyzed database schema
     * @return ordered list of table names — parents before children; cyclic tables appended last
     */
    public List<String> topologicalSort(DatabaseSchema schema) {
        // Build adjacency list: table -> list of parents (tables this table depends on)
        Map<String, List<String>> dependencies = buildDependencyMap(schema);

        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();  // tables currently on the DFS stack (cycle detection)
        Set<String> cyclic = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        List<String> path = new ArrayList<>();  // ordered DFS path — used to identify full cycle segments

        for (String table : schema.tableNames()) {
            if (!visited.contains(table)) {
                dfsVisit(table, dependencies, visited, inStack, path, cyclic, result);
            }
        }

        if (!cyclic.isEmpty()) {
            log.warn("Cycle detected in FK graph — tables involved: {}. "
                    + "These tables will be appended at the end of the insertion order. "
                    + "Ensure FK constraints are disabled during copy (session_replication_role = 'replica').",
                    cyclic);
            // Cyclic tables are already added by dfsVisit, but we ensure they all appear
            for (String t : cyclic) {
                if (!result.contains(t)) {
                    result.add(t);
                }
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the set of table names that participate in a cycle in the FK graph.
     *
     * <p>A table is considered cyclic if it is part of a directed cycle — including
     * self-referential tables ({@code table.fk -> table.pk}).
     *
     * @param schema the analyzed database schema
     * @return set of table names involved in at least one FK cycle; empty if the graph is acyclic
     */
    public Set<String> detectCycles(DatabaseSchema schema) {
        Map<String, List<String>> dependencies = buildDependencyMap(schema);

        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        Set<String> cyclic = new LinkedHashSet<>();
        List<String> ignored = new ArrayList<>();
        List<String> path = new ArrayList<>();

        for (String table : schema.tableNames()) {
            if (!visited.contains(table)) {
                dfsVisit(table, dependencies, visited, inStack, path, cyclic, ignored);
            }
        }

        return Collections.unmodifiableSet(cyclic);
    }

    /**
     * Returns all ancestor table names reachable from {@code tableName} by following
     * foreign key edges upward (child -> parent direction), up to {@code maxDepth} hops.
     *
     * <p>Used by {@code FkParentResolver} in Phase 4 to pre-compute which parent tables
     * need to be loaded to satisfy referential integrity.
     *
     * @param tableName  starting table (child)
     * @param schema     the analyzed database schema
     * @param maxDepth   maximum number of FK hops to follow (prevents infinite loops on cycles)
     * @return set of ancestor table names (does not include {@code tableName} itself)
     */
    public Set<String> resolveAncestors(String tableName, DatabaseSchema schema, int maxDepth) {
        Set<String> ancestors = new LinkedHashSet<>();
        resolveAncestorsRecursive(tableName, schema, maxDepth, 0, ancestors);
        return Collections.unmodifiableSet(ancestors);
    }

    // -------------------------------------------------------------------------
    // Internal DFS implementation
    // -------------------------------------------------------------------------

    /**
     * DFS post-order visit — builds the topological sort result and tracks cycles.
     *
     * <p>When a back edge is detected (target node already on the current DFS stack),
     * the full cycle segment is identified by locating the target in {@code path} and
     * marking every node from that index to the end of the path as cyclic.
     * This ensures that all participants in a cycle (e.g. both sides of a mutual FK)
     * are correctly flagged, not just the node at the top of the stack.
     *
     * @param table        current table being visited
     * @param dependencies adjacency map (table -> list of parent tables)
     * @param visited      set of tables fully processed
     * @param inStack      set of tables currently on the DFS recursion stack
     * @param path         ordered list of tables on the current DFS path (stack order)
     * @param cyclic       accumulator for tables detected as part of a cycle
     * @param result       accumulator for the topological order (parents first)
     */
    private void dfsVisit(
            String table,
            Map<String, List<String>> dependencies,
            Set<String> visited,
            Set<String> inStack,
            List<String> path,
            Set<String> cyclic,
            List<String> result) {

        if (inStack.contains(table)) {
            // Back edge found — mark every node from 'table' to the end of the current path as cyclic.
            // This covers all participants in the cycle, not just the back-edge target.
            int idx = path.lastIndexOf(table);
            if (idx >= 0) {
                cyclic.addAll(path.subList(idx, path.size()));
            }
            cyclic.add(table);
            return;
        }
        if (visited.contains(table)) {
            return;
        }

        inStack.add(table);
        path.add(table);

        for (String parent : dependencies.getOrDefault(table, List.of())) {
            dfsVisit(parent, dependencies, visited, inStack, path, cyclic, result);
        }

        path.remove(path.size() - 1);
        inStack.remove(table);
        visited.add(table);

        if (!cyclic.contains(table)) {
            result.add(table);
        }
    }

    /**
     * Recursively collects ancestors by following FK parent edges.
     *
     * @param current   current table
     * @param schema    database schema
     * @param maxDepth  maximum depth
     * @param depth     current depth
     * @param ancestors accumulator
     */
    private void resolveAncestorsRecursive(
            String current,
            DatabaseSchema schema,
            int maxDepth,
            int depth,
            Set<String> ancestors) {

        if (depth >= maxDepth) {
            return;
        }

        TableMetadata meta = schema.get(current);
        if (meta == null) {
            return;
        }

        for (ForeignKey fk : meta.foreignKeys()) {
            String parent = fk.toTable();
            // Avoid infinite loops on cyclic FK graphs (e.g. self-referential)
            if (!ancestors.contains(parent) && !parent.equals(current)) {
                ancestors.add(parent);
                resolveAncestorsRecursive(parent, schema, maxDepth, depth + 1, ancestors);
            }
        }
    }

    /**
     * Builds a map from each table to its list of direct FK parents.
     *
     * @param schema the database schema
     * @return adjacency map (child table -> list of parent tables)
     */
    private Map<String, List<String>> buildDependencyMap(DatabaseSchema schema) {
        Map<String, List<String>> deps = new HashMap<>();
        // Initialize all tables with empty lists (ensures tables with no FK also appear)
        for (String table : schema.tableNames()) {
            deps.put(table, new ArrayList<>());
        }
        for (String table : schema.tableNames()) {
            TableMetadata meta = schema.get(table);
            if (meta != null) {
                for (ForeignKey fk : meta.foreignKeys()) {
                    deps.computeIfAbsent(table, _ -> new ArrayList<>()).add(fk.toTable());
                }
            }
        }
        return deps;
    }
}

