package com.fungle.brume.plan;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.timeout.BoundedQueryExecutor;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring component that computes the exact planned row count for each table that will be
 * processed during a Brume replication run.
 *
 * <p>Uses SQL JOIN-based {@code COUNT} queries to achieve exact deduplication across
 * multi-path FK references. Self-referential cycles are handled via recursive CTEs with
 * a depth limit equal to {@code fkDepth}.
 *
 * <p>This component never throws — any SQL-level error is caught, logged as WARN, and
 * represented as a {@code -1L} planned count for the affected table.
 */
@Component
public class PlanEstimator {

    private static final Logger log = LoggerFactory.getLogger(PlanEstimator.class);

    /** Per-table threshold in EXACT mode beyond which we suggest ESTIMATE (B3, ADR-0003). */
    private static final long EXACT_TABLE_WARN_MS = 10_000L;
    /** Cumulative threshold in EXACT mode beyond which we suggest ESTIMATE. */
    private static final long EXACT_TOTAL_WARN_MS = 60_000L;

    private final JdbcTemplate sourceJdbcTemplate;
    private final BoundedQueryExecutor boundedQueryExecutor;
    private final PlanMode mode;

    /** Set when the EXACT path has emitted the "consider ESTIMATE" WARN once for this run. */
    private boolean exactWarnedSlow;

    /**
     * Creates a new {@code PlanEstimator}.
     *
     * @param sourceJdbcTemplate JDBC template connected to the source database
     * @param brumeProperties    Brume configuration (provides {@code brume.plan.mode})
     */
    public PlanEstimator(
            @Qualifier("sourceJdbcTemplate") JdbcTemplate sourceJdbcTemplate,
            BoundedQueryExecutor boundedQueryExecutor,
            BrumeProperties brumeProperties) {
        this.sourceJdbcTemplate = sourceJdbcTemplate;
        this.boundedQueryExecutor = boundedQueryExecutor;
        this.mode = brumeProperties.plan().mode();
        log.info("PlanEstimator configured with plan-mode={}", this.mode);
    }

    /**
     * Computes the exact planned row count for each table that will be processed,
     * including FK parent tables resolved up to {@code fkDepth} levels.
     *
     * <p>Uses SQL JOIN-based COUNT queries (not simple COUNT(*)) to achieve exact
     * deduplication across multi-path FK references. Self-referential cycles are
     * handled via recursive CTEs with a depth limit equal to {@code fkDepth}.
     *
     * @param config     the anonymization config (declares tables + filters)
     * @param schema     the source database schema (FK relationships)
     * @param schemaName the PostgreSQL schema name
     * @param fkDepth    maximum FK resolution depth (mirrors FkParentResolver)
     * @return a {@link PlanSummary} with per-table planned counts and estimation timestamp
     */
    public PlanSummary estimate(AnonymizerConfig config, DatabaseSchema schema,
                                String schemaName, int fkDepth) {
        // Fail fast with a clear error if the schema name is not a valid identifier — every SQL
        // builder below quotes it via SqlIdentifiers and would otherwise surface an opaque
        // IllegalArgumentException 5 stack frames deep. Audit 2026-05-05 § A3, ticket T02.
        SqlIdentifiers.validate(schemaName);
        if (mode == PlanMode.ESTIMATE) {
            return estimateFromReltuples(config, schema, schemaName);
        }
        return estimateExact(config, schema, schemaName, fkDepth);
    }

    /**
     * Exact mode (default): mirrors the three-phase pipeline of
     * {@link com.fungle.brume.extraction.ExtractionEngine} —
     * (1) direct tables, (2) FK children (inverse FK walk), (3) FK parents.
     *
     * <p>Slow on large databases — emits a WARN suggesting {@link PlanMode#ESTIMATE} when
     * a single table exceeds {@value #EXACT_TABLE_WARN_MS} ms or when the cumulative time
     * exceeds {@value #EXACT_TOTAL_WARN_MS} ms.
     */
    private PlanSummary estimateExact(AnonymizerConfig config, DatabaseSchema schema,
                                      String schemaName, int fkDepth) {
        long exactStartNanos = System.nanoTime();
        this.exactWarnedSlow = false;

        Map<String, Long> directCounts = new LinkedHashMap<>();
        Map<String, Long> fkChildCounts = new LinkedHashMap<>();
        Map<String, Long> fkParentCounts = new LinkedHashMap<>();
        Map<String, String> tableOrigins = new LinkedHashMap<>();

        // pkSetSql[T] = SQL fragment returning the set of reachable PK values for T
        Map<String, String> pkSetSql = new LinkedHashMap<>();
        Set<String> discoveredTables = new LinkedHashSet<>();

        // --- Phase 1: Count direct tables (declared in config.extraction.tables) ---
        // Filters reaching this point have already been validated by ExtractionFilterValidator
        // in ConfigValidator (see ADR-0017) — no defensive sanitization needed here.
        List<TableExtractionConfig> directTables = config.extraction().tables();
        for (TableExtractionConfig tec : directTables) {
            String tableName = tec.table();
            String filter = (tec.filter() == null || tec.filter().isBlank()) ? null : tec.filter();

            long count = timedExecuteCount(buildDirectCountSql(schemaName, tableName, filter), tableName);
            directCounts.put(tableName, count);
            tableOrigins.put(tableName, "direct");
            discoveredTables.add(tableName);

            TableMetadata meta = schema.get(tableName);
            String pk = (meta != null) ? meta.primaryKeyColumn() : null;
            if (pk != null) {
                pkSetSql.put(tableName, buildFilteredPkSql(schemaName, tableName, pk, filter));
            }
        }

        // --- Phase 2: FK children — inverse FK walk from seed tables (mirrors FkChildResolver) ---
        Map<String, List<ForeignKey>> inverseIndex = buildInverseIndex(schema);
        List<String> childFrontier = new ArrayList<>(directCounts.keySet());

        for (int level = 0; level < fkDepth && !childFrontier.isEmpty(); level++) {
            // childTable → WHERE conditions (one per parent that references it this level)
            Map<String, List<String>> pendingConditions = new LinkedHashMap<>();
            Map<String, String> childPkCols = new LinkedHashMap<>();

            for (String parentTable : childFrontier) {
                String parentPkSql = pkSetSql.get(parentTable);
                if (parentPkSql == null) continue;

                for (ForeignKey fk : inverseIndex.getOrDefault(parentTable, List.of())) {
                    String childTable = fk.fromTable();
                    if (childTable.equals(parentTable)) continue; // self-ref handled in Phase 3

                    if (discoveredTables.contains(childTable)) {
                        tableOrigins.put(childTable, addRole(tableOrigins.get(childTable), "fk-child"));
                        continue;
                    }
                    pendingConditions.computeIfAbsent(childTable, _ -> new ArrayList<>())
                            .add(SqlIdentifiers.quote(fk.fromColumn()) + " IN (" + parentPkSql + ")");

                    TableMetadata childMeta = schema.get(childTable);
                    if (childMeta != null && childMeta.primaryKeyColumn() != null) {
                        childPkCols.put(childTable, childMeta.primaryKeyColumn());
                    }
                }
            }

            List<String> nextChildFrontier = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : pendingConditions.entrySet()) {
                String childTable = entry.getKey();
                if (discoveredTables.contains(childTable)) continue;

                List<String> conditions = entry.getValue();
                String where = conditions.size() == 1
                        ? conditions.get(0)
                        : "(" + String.join(" OR ", conditions) + ")";
                String childPk = childPkCols.get(childTable);

                String countSql = childPk != null
                        ? "SELECT COUNT(DISTINCT " + SqlIdentifiers.quote(childPk) + ") FROM "
                          + SqlIdentifiers.quoteQualified(schemaName, childTable) + " WHERE " + where
                        : "SELECT COUNT(*) FROM "
                          + SqlIdentifiers.quoteQualified(schemaName, childTable) + " WHERE " + where;

                long count = timedExecuteCount(countSql, childTable);
                fkChildCounts.put(childTable, count);
                tableOrigins.put(childTable, "fk-child");
                discoveredTables.add(childTable);

                if (childPk != null) {
                    pkSetSql.put(childTable,
                            "SELECT " + SqlIdentifiers.quote(childPk) + " FROM "
                                    + SqlIdentifiers.quoteQualified(schemaName, childTable)
                                    + " WHERE " + where);
                    nextChildFrontier.add(childTable);
                }
            }
            childFrontier = nextChildFrontier;
        }

        // --- Phase 3: FK parent resolution — covers direct tables AND FK children ---
        // Frontier = all tables with a known pkSetSql (direct + fk-children with single PK)
        List<String> frontier = new ArrayList<>(pkSetSql.keySet());

        for (int level = 1; level <= fkDepth && !frontier.isEmpty(); level++) {
            Map<String, List<String>> parentRefSubqueries = new LinkedHashMap<>();
            Map<String, String> parentPkColumns = new LinkedHashMap<>();

            for (String childTable : frontier) {
                TableMetadata childMeta = schema.get(childTable);
                if (childMeta == null
                        || childMeta.foreignKeys() == null
                        || childMeta.foreignKeys().isEmpty()) {
                    continue;
                }
                String childPkSql = pkSetSql.get(childTable);
                if (childPkSql == null) continue;

                String childPk = childMeta.primaryKeyColumn();
                if (childPk == null) continue;

                for (ForeignKey fk : childMeta.foreignKeys()) {
                    String parentTable = fk.toTable();

                    if (parentTable.equals(childTable)) {
                        handleSelfReferentialFk(schemaName, fk, childPkSql, fkDepth,
                                fkParentCounts, pkSetSql, tableOrigins,
                                discoveredTables, childTable, childPk);
                        continue;
                    }

                    TableMetadata parentMeta = schema.get(parentTable);
                    if (parentMeta == null) continue;
                    String parentPk = parentMeta.primaryKeyColumn();
                    if (parentPk == null) continue;

                    String refSubquery = "SELECT DISTINCT " + SqlIdentifiers.quote(fk.fromColumn())
                            + " FROM " + SqlIdentifiers.quoteQualified(schemaName, childTable)
                            + " WHERE " + SqlIdentifiers.quote(childPk) + " IN (" + childPkSql + ")";

                    parentRefSubqueries.computeIfAbsent(parentTable, k -> new ArrayList<>())
                            .add(refSubquery);
                    parentPkColumns.put(parentTable, parentPk);
                }
            }

            List<String> nextFrontier = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry : parentRefSubqueries.entrySet()) {
                String parentTable = entry.getKey();
                List<String> refs = entry.getValue();
                String parentPk = parentPkColumns.get(parentTable);
                String unionSubquery = String.join(" UNION ", refs);

                if (discoveredTables.contains(parentTable)) {
                    tableOrigins.put(parentTable, addRole(tableOrigins.get(parentTable), "fk-parent"));
                    String existing = pkSetSql.get(parentTable);
                    if (existing != null) {
                        pkSetSql.put(parentTable, existing + " UNION " + unionSubquery);
                    }
                    continue;
                }

                String quotedParentPk = SqlIdentifiers.quote(parentPk);
                String quotedParentTable = SqlIdentifiers.quoteQualified(schemaName, parentTable);
                String countSql = "SELECT COUNT(DISTINCT " + quotedParentPk + ")"
                        + " FROM " + quotedParentTable
                        + " WHERE " + quotedParentPk + " IN (" + unionSubquery + ")";
                long count = timedExecuteCount(countSql, parentTable);
                fkParentCounts.put(parentTable, count);
                tableOrigins.put(parentTable, "fk-parent");
                discoveredTables.add(parentTable);

                String newPkSql = "SELECT " + quotedParentPk
                        + " FROM " + quotedParentTable
                        + " WHERE " + quotedParentPk + " IN (" + unionSubquery + ")";
                pkSetSql.put(parentTable, newPkSql);
                nextFrontier.add(parentTable);
            }

            frontier = nextFrontier;
        }

        // --- Assemble result ---
        Set<String> allTables = new LinkedHashSet<>(directCounts.keySet());
        allTables.addAll(fkChildCounts.keySet());
        allTables.addAll(fkParentCounts.keySet());

        List<PlanTableStats> tableStats = new ArrayList<>();
        for (String tableName : allTables) {
            long direct   = directCounts.getOrDefault(tableName, 0L);
            long fkChild  = fkChildCounts.getOrDefault(tableName, 0L);
            long fkParent = fkParentCounts.getOrDefault(tableName, 0L);
            String origin = tableOrigins.getOrDefault(tableName, "direct");
            tableStats.add(new PlanTableStats(tableName, direct, fkParent, fkChild, origin));
        }

        long totalMs = (System.nanoTime() - exactStartNanos) / 1_000_000L;
        if (totalMs > EXACT_TOTAL_WARN_MS && !exactWarnedSlow) {
            log.warn("PlanEstimator: EXACT plan took {} ms total. Consider 'brume.plan.mode=ESTIMATE' "
                    + "for a fast approximate plan (±20% accuracy, < 1s for 1000 tables).", totalMs);
            exactWarnedSlow = true;
        }

        return new PlanSummary(schemaName, tableStats, List.of(), Instant.now());
    }

    /**
     * Estimate mode: reads {@code pg_class.reltuples} for direct tables and FK parent
     * tables. Filters on direct tables are ignored (the estimate is an upper bound on the
     * unfiltered population — a WARN is logged when a filter is encountered). FK
     * resolution walks the schema graph (no DB query for closure), reading reltuples for
     * each parent. Self-ref FKs are handled naturally by the discoveredTables guard.
     *
     * <p>Per B3, the result is suitable as a fast plan or as input to
     * {@code max-target-rows} warnings — not for exact accounting.
     */
    private PlanSummary estimateFromReltuples(AnonymizerConfig config, DatabaseSchema schema,
                                              String schemaName) {
        Map<String, Long> directCounts = new LinkedHashMap<>();
        Map<String, Long> fkChildCounts = new LinkedHashMap<>();
        Map<String, Long> fkParentCounts = new LinkedHashMap<>();
        Map<String, String> tableOrigins = new LinkedHashMap<>();
        Set<String> discoveredTables = new LinkedHashSet<>();

        // Phase 1: Direct tables
        List<TableExtractionConfig> directTables = config.extraction().tables();
        for (TableExtractionConfig tec : directTables) {
            String tableName = tec.table();
            if (tec.filter() != null && !tec.filter().isBlank()) {
                log.warn("PlanEstimator (ESTIMATE mode): filter on '{}' is ignored — reltuples is an "
                        + "upper bound on the full table; the actual extraction count will likely be lower.",
                        tableName);
            }
            long count = readReltuples(schemaName, tableName);
            directCounts.put(tableName, count);
            tableOrigins.put(tableName, "direct");
            discoveredTables.add(tableName);
        }

        // Phase 2: FK children — inverse schema-level walk (mirrors FkChildResolver)
        Map<String, List<ForeignKey>> inverseIndex = buildInverseIndex(schema);
        Set<String> childFrontier = new LinkedHashSet<>(directCounts.keySet());
        int childSafetyBound = 1000;
        while (!childFrontier.isEmpty() && childSafetyBound-- > 0) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String parentTable : childFrontier) {
                for (ForeignKey fk : inverseIndex.getOrDefault(parentTable, List.of())) {
                    String childTable = fk.fromTable();
                    if (childTable.equals(parentTable)) continue; // self-ref
                    if (discoveredTables.contains(childTable)) {
                        tableOrigins.put(childTable, addRole(tableOrigins.get(childTable), "fk-child"));
                        continue;
                    }
                    long count = readReltuples(schemaName, childTable);
                    fkChildCounts.put(childTable, count);
                    tableOrigins.put(childTable, "fk-child");
                    discoveredTables.add(childTable);
                    nextFrontier.add(childTable);
                }
            }
            childFrontier = nextFrontier;
        }

        // Phase 3: FK parents — walk from direct + FK children
        Set<String> parentFrontier = new LinkedHashSet<>();
        parentFrontier.addAll(directCounts.keySet());
        parentFrontier.addAll(fkChildCounts.keySet());
        int parentSafetyBound = 1000;
        while (!parentFrontier.isEmpty() && parentSafetyBound-- > 0) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String childTable : parentFrontier) {
                TableMetadata childMeta = schema.get(childTable);
                if (childMeta == null || childMeta.foreignKeys() == null) continue;
                for (ForeignKey fk : childMeta.foreignKeys()) {
                    String parentTable = fk.toTable();
                    if (discoveredTables.contains(parentTable)) {
                        tableOrigins.put(parentTable, addRole(tableOrigins.get(parentTable), "fk-parent"));
                        continue;
                    }
                    long count = readReltuples(schemaName, parentTable);
                    fkParentCounts.put(parentTable, count);
                    tableOrigins.put(parentTable, "fk-parent");
                    discoveredTables.add(parentTable);
                    nextFrontier.add(parentTable);
                }
            }
            parentFrontier = nextFrontier;
        }

        Set<String> allTables = new LinkedHashSet<>(directCounts.keySet());
        allTables.addAll(fkChildCounts.keySet());
        allTables.addAll(fkParentCounts.keySet());
        List<PlanTableStats> tableStats = new ArrayList<>();
        for (String tableName : allTables) {
            long direct   = directCounts.getOrDefault(tableName, 0L);
            long fkChild  = fkChildCounts.getOrDefault(tableName, 0L);
            long fkParent = fkParentCounts.getOrDefault(tableName, 0L);
            String origin = tableOrigins.getOrDefault(tableName, "direct");
            tableStats.add(new PlanTableStats(tableName, direct, fkParent, fkChild, origin));
        }
        return new PlanSummary(schemaName, tableStats, List.of(), Instant.now());
    }

    /**
     * Reads {@code pg_class.reltuples} for the given schema-qualified table. Returns
     * {@code 0} if the table has never been ANALYZE'd ({@code reltuples = -1}) or has
     * no rows; returns {@code -1} on SQL error or table not found (no row in pg_class).
     */
    private long readReltuples(String schemaName, String tableName) {
        try {
            Long result = boundedQueryExecutor.execute(
                    "PlanEstimator.readReltuples[" + tableName + "]",
                    jdbc -> jdbc.queryForObject(
                            "SELECT GREATEST(c.reltuples::bigint, 0) "
                                    + "FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid "
                                    + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'r'",
                            Long.class, schemaName, tableName));
            return result != null ? result : 0L;
        } catch (DataAccessException e) {
            log.warn("PlanEstimator (ESTIMATE): failed to read reltuples for '{}.{}': {}",
                    schemaName, tableName, e.getMessage());
            return -1L;
        }
    }

    /**
     * Wraps {@link #executeCount(String, String)} with per-table timing. Emits a WARN
     * suggesting {@code brume.plan.mode=ESTIMATE} the first time a single COUNT exceeds
     * {@value #EXACT_TABLE_WARN_MS} ms. The WARN fires at most once per
     * {@link #estimateExact} invocation.
     */
    private long timedExecuteCount(String sql, String tableHint) {
        long start = System.nanoTime();
        long count = executeCount(sql, tableHint);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        log.debug("PlanEstimator: EXACT count for '{}' took {} ms (rows={})", tableHint, elapsedMs, count);
        if (count >= 0 && elapsedMs > EXACT_TABLE_WARN_MS && !exactWarnedSlow) {
            log.warn("PlanEstimator: EXACT count for '{}' took {} ms. Consider 'brume.plan.mode=ESTIMATE' "
                    + "for a fast approximate plan (±20% accuracy, < 1s for 1000 tables).",
                    tableHint, elapsedMs);
            exactWarnedSlow = true;
        }
        return count;
    }

    /**
     * Handles a self-referential FK (e.g. {@code users.manager_id → users.id}) by computing
     * the total reachable count via a recursive CTE capped at {@code fkDepth} iterations.
     *
     * @param schemaName       the PostgreSQL schema name
     * @param fk               the self-referential foreign key
     * @param childPkSql       SQL expression giving the initial set of reachable PKs
     * @param fkDepth          maximum recursion depth
     * @param fkParentCounts   map to update with the computed count
     * @param pkSetSql         map to update with the new pk set SQL for this table
     * @param tableOrigins     map to update with the origin label
     * @param discoveredTables set of already-discovered tables
     * @param tableName        the table name (same as both child and parent)
     * @param pkColumn         the primary key column of the table
     */
    private void handleSelfReferentialFk(String schemaName, ForeignKey fk, String childPkSql,
                                          int fkDepth, Map<String, Long> fkParentCounts,
                                          Map<String, String> pkSetSql,
                                          Map<String, String> tableOrigins,
                                          Set<String> discoveredTables,
                                          String tableName, String pkColumn) {
        if (discoveredTables.contains(tableName)) {
            tableOrigins.put(tableName, addRole(tableOrigins.get(tableName), "fk-parent"));
            return;
        }

        // Build a recursive CTE to count all rows reachable through the hierarchy
        String quotedPk = SqlIdentifiers.quote(pkColumn);
        String quotedTable = SqlIdentifiers.quoteQualified(schemaName, tableName);
        String quotedFkCol = SqlIdentifiers.quote(fk.fromColumn());
        String cte = "WITH RECURSIVE hierarchy(id, depth) AS ("
                + " SELECT DISTINCT " + quotedPk + ", 0 FROM " + quotedTable
                + " WHERE " + quotedPk + " IN (" + childPkSql + ")"
                + " UNION ALL"
                + " SELECT t." + quotedFkCol + ", h.depth + 1"
                + " FROM " + quotedTable + " t"
                + " INNER JOIN hierarchy h ON t." + quotedPk + " = h.id"
                + " WHERE h.depth < " + fkDepth
                + " AND t." + quotedFkCol + " IS NOT NULL"
                + ") SELECT COUNT(DISTINCT id) FROM hierarchy";

        long count = timedExecuteCount(cte, tableName + " (self-ref)");
        fkParentCounts.put(tableName, count);
        tableOrigins.put(tableName, "fk-parent");
        discoveredTables.add(tableName);

        // pk set SQL for next depth levels
        String newPkSql = "WITH RECURSIVE hierarchy(id, depth) AS ("
                + " SELECT DISTINCT " + quotedPk + ", 0 FROM " + quotedTable
                + " WHERE " + quotedPk + " IN (" + childPkSql + ")"
                + " UNION ALL"
                + " SELECT t." + quotedFkCol + ", h.depth + 1"
                + " FROM " + quotedTable + " t"
                + " INNER JOIN hierarchy h ON t." + quotedPk + " = h.id"
                + " WHERE h.depth < " + fkDepth
                + " AND t." + quotedFkCol + " IS NOT NULL"
                + ") SELECT DISTINCT id FROM hierarchy";
        pkSetSql.put(tableName, newPkSql);
    }

    /**
     * Executes a SQL COUNT query and returns the result.
     * Returns {@code -1L} and logs a WARN if execution fails.
     *
     * @param sql       the COUNT SQL statement
     * @param tableHint a hint for the log message (e.g. the table name)
     * @return the count result, or {@code -1L} on error
     */
    private long executeCount(String sql, String tableHint) {
        try {
            Long result = boundedQueryExecutor.execute(
                    "PlanEstimator.count[" + tableHint + "]",
                    jdbc -> jdbc.queryForObject(sql, Long.class));
            return result != null ? result : 0L;
        } catch (DataAccessException e) {
            log.warn("Failed to count rows for '{}': {}", tableHint, e.getMessage());
            return -1L;
        }
    }

    /**
     * Builds a simple {@code COUNT(*)} SQL for a directly-extracted table.
     *
     * @param schemaName the PostgreSQL schema name
     * @param tableName  the table name
     * @param filter     optional sanitized SQL WHERE clause (without the {@code WHERE} keyword)
     * @return the COUNT SQL statement
     */
    private String buildDirectCountSql(String schemaName, String tableName, String filter) {
        String sql = "SELECT COUNT(*) FROM " + SqlIdentifiers.quoteQualified(schemaName, tableName);
        if (filter != null && !filter.isBlank()) {
            sql += " WHERE " + filter;
        }
        return sql;
    }

    /**
     * Builds a SQL expression that returns the set of PK values for a directly-extracted table.
     * Used as the basis for FK reference subqueries at the next level.
     *
     * @param schemaName the PostgreSQL schema name
     * @param tableName  the table name
     * @param pkColumn   the primary key column name
     * @param filter     optional sanitized SQL WHERE clause
     * @return SQL fragment returning the reachable PK set
     */
    private String buildFilteredPkSql(String schemaName, String tableName,
                                       String pkColumn, String filter) {
        String sql = "SELECT " + SqlIdentifiers.quote(pkColumn)
                + " FROM " + SqlIdentifiers.quoteQualified(schemaName, tableName);
        if (filter != null && !filter.isBlank()) {
            sql += " WHERE " + filter;
        }
        return sql;
    }

    /**
     * Builds the inverse FK index: parentTable → list of FK edges pointing TO it.
     * Used to discover FK child tables (tables that reference a given parent via FK).
     */
    private static Map<String, List<ForeignKey>> buildInverseIndex(DatabaseSchema schema) {
        Map<String, List<ForeignKey>> index = new HashMap<>();
        for (TableMetadata meta : schema.tables().values()) {
            if (meta.foreignKeys() == null) continue;
            for (ForeignKey fk : meta.foreignKeys()) {
                index.computeIfAbsent(fk.toTable(), _ -> new ArrayList<>()).add(fk);
            }
        }
        return index;
    }

    /**
     * Appends {@code newRole} to an existing origin string, maintaining canonical order
     * ({@code direct} &lt; {@code fk-child} &lt; {@code fk-parent}).
     * No-op if {@code newRole} is already present.
     */
    private static String addRole(String existingOrigin, String newRole) {
        if (existingOrigin == null || existingOrigin.isBlank()) return newRole;
        if (existingOrigin.contains(newRole)) return existingOrigin;
        return existingOrigin + "+" + newRole;
    }
}


