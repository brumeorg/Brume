package com.fungle.brume.plan;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.CursorReader;
import com.fungle.brume.extraction.FkGraph;
import com.fungle.brume.extraction.FkTraversal;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.OrphanReference;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.timeout.BoundedQueryExecutor;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Spring component that computes the planned row count for each table that will be
 * processed during a Brume replication run.
 *
 * <p>EXACT mode (default) runs the same FK walk as {@code execute} ({@link FkTraversal},
 * ADR-0050) but reads key columns only and counts the rows instead of writing them : the plan
 * matches the execution by construction. ESTIMATE mode walks the table-level FK graph with the
 * same radius rule and reads {@code pg_class.reltuples} (B3, ADR-0003).
 *
 * <p>This component never throws — any SQL-level error is caught, logged as WARN, and
 * represented as a {@code -1L} planned count.
 */
@Component
public class PlanEstimator {

    private static final Logger log = LoggerFactory.getLogger(PlanEstimator.class);

    /** Cumulative threshold in EXACT mode beyond which we suggest ESTIMATE (B3, ADR-0003). */
    private static final long EXACT_TOTAL_WARN_MS = 60_000L;

    private final CursorReader cursorReader;
    private final BoundedQueryExecutor boundedQueryExecutor;
    private final PlanMode mode;

    /**
     * Creates a new {@code PlanEstimator}.
     *
     * @param cursorReader    source reader shared with the extraction pipeline
     * @param brumeProperties Brume configuration (provides {@code brume.plan.mode})
     */
    public PlanEstimator(
            CursorReader cursorReader,
            BoundedQueryExecutor boundedQueryExecutor,
            BrumeProperties brumeProperties) {
        this.cursorReader = cursorReader;
        this.boundedQueryExecutor = boundedQueryExecutor;
        this.mode = brumeProperties.plan().mode();
        log.info("PlanEstimator configured with plan-mode={}", this.mode);
    }

    /**
     * Computes the planned row count for each table that will be processed : the configured
     * tables and every table the FK walk reaches within {@code fkDepth} (ADR-0050).
     *
     * @param config     the anonymization config (declares tables, filters and orphan policy)
     * @param schema     the source database schema (FK relationships)
     * @param schemaName the PostgreSQL schema name
     * @param fkDepth    FK radius — {@code extraction.fk_depth}, as used by {@code execute}
     * @return a {@link PlanSummary} with per-table planned counts and estimation timestamp
     */
    public PlanSummary estimate(AnonymizerConfig config, DatabaseSchema schema,
                                String schemaName, int fkDepth) {
        // Fail fast with a clear error if the schema name is not a valid identifier — every SQL
        // builder below quotes it via SqlIdentifiers and would otherwise surface an opaque
        // IllegalArgumentException 5 stack frames deep. Audit 2026-05-05 § A3, ticket T02.
        SqlIdentifiers.validate(schemaName);
        if (mode == PlanMode.ESTIMATE) {
            return estimateFromReltuples(config, schema, schemaName, fkDepth);
        }
        return estimateExact(config, schema, schemaName, fkDepth);
    }

    /**
     * Exact mode (default) : the {@code execute} FK walk on key columns only, counting rows by
     * how they enter the copy (declared table, FK parent, FK child). Slow on large databases —
     * emits a WARN suggesting {@link PlanMode#ESTIMATE} beyond {@value #EXACT_TOTAL_WARN_MS} ms.
     */
    private PlanSummary estimateExact(AnonymizerConfig config, DatabaseSchema schema,
                                      String schemaName, int fkDepth) {
        long startNanos = System.nanoTime();
        ExtractionConfig extraction = config.extraction();
        Map<String, long[]> counts = new LinkedHashMap<>();
        extraction.tables().forEach(t -> counts.put(t.table(), new long[3]));

        FkTraversal.RowReader keysOnly = new FkTraversal.RowReader() {
            @Override
            public void readSeeds(String table, String filter, Consumer<ExtractedRow> consumer) {
                List<String> columns = FkTraversal.keyColumns(schema, table);
                if (columns == null) {
                    cursorReader.streamRows(schemaName, table, filter, extraction.fetchSize(), null, consumer);
                } else {
                    cursorReader.streamRows(schemaName, table, filter, columns, List.of(), extraction.fetchSize(), consumer);
                }
            }

            @Override
            public void readWhereIn(String table, List<String> keyColumns, Collection<Object> keys,
                                    Consumer<ExtractedRow> consumer) {
                cursorReader.streamWhereIn(schemaName, table, keyColumns, keys,
                        FkTraversal.keyColumns(schema, table), List.of(), extraction.fetchSize(), consumer);
            }
        };

        try {
            List<OrphanReference> orphans = new FkTraversal(schema, fkDepth, extraction.orphanPolicy(), () -> { })
                    .run(extraction.tables(), keysOnly,
                            (row, origin) -> counts.computeIfAbsent(row.table(), _ -> new long[3])[origin.ordinal()]++);
            orphans.forEach(o -> log.warn("[ORPHANS] plan: {} (extraction.orphan_policy: ALLOW_ORPHANS)", o.describe()));
        } catch (RuntimeException e) {
            log.warn("PlanEstimator: EXACT plan failed — planned counts reported as -1: {}", e.getMessage());
            List<PlanTableStats> failed = new ArrayList<>();
            extraction.tables().forEach(t -> failed.add(new PlanTableStats(t.table(), -1L, 0L, 0L, "direct")));
            return new PlanSummary(schemaName, failed, List.of(), Instant.now());
        }

        long totalMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (totalMs > EXACT_TOTAL_WARN_MS) {
            log.warn("PlanEstimator: EXACT plan took {} ms total. Consider 'brume.plan.mode=ESTIMATE' "
                    + "for a fast approximate plan (±20% accuracy, < 1s for 1000 tables).", totalMs);
        }

        List<PlanTableStats> tableStats = new ArrayList<>();
        counts.forEach((table, c) -> tableStats.add(new PlanTableStats(
                table, c[FkTraversal.Origin.DIRECT.ordinal()], c[FkTraversal.Origin.FK_PARENT.ordinal()],
                c[FkTraversal.Origin.FK_CHILD.ordinal()], origin(table, config, c))));
        return new PlanSummary(schemaName, tableStats, List.of(), Instant.now());
    }

    /**
     * Estimate mode: reads {@code pg_class.reltuples} for every table the FK walk can reach
     * at table level (same radius and orphan policy as the row walk). Filters on direct tables
     * are ignored (the estimate is an upper bound on the unfiltered population — a WARN is
     * logged when a filter is encountered).
     *
     * <p>Per B3, the result is suitable as a fast plan or as input to
     * {@code max-target-rows} warnings — not for exact accounting.
     */
    private PlanSummary estimateFromReltuples(AnonymizerConfig config, DatabaseSchema schema,
                                              String schemaName, int fkDepth) {
        List<String> seedTables = new ArrayList<>();
        for (TableExtractionConfig tec : config.extraction().tables()) {
            if (tec.filter() != null && !tec.filter().isBlank()) {
                log.warn("PlanEstimator (ESTIMATE mode): filter on '{}' is ignored — reltuples is an "
                        + "upper bound on the full table; the actual extraction count will likely be lower.",
                        tec.table());
            }
            seedTables.add(tec.table());
        }

        Map<String, FkTraversal.Origin> reached = new FkGraph(schema)
                .tablesWithin(seedTables, fkDepth, config.extraction().orphanPolicy());
        List<PlanTableStats> tableStats = new ArrayList<>();
        reached.forEach((table, origin) -> {
            long count = readReltuples(schemaName, table);
            tableStats.add(switch (origin) {
                case DIRECT -> new PlanTableStats(table, count, 0L, 0L, "direct");
                case FK_PARENT -> new PlanTableStats(table, 0L, count, 0L, "fk-parent");
                case FK_CHILD -> new PlanTableStats(table, 0L, 0L, count, "fk-child");
            });
        });
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
     * Origin label of a table in the plan : a configured table stays {@code direct} — the FK
     * roles describe how <em>other</em> tables were pulled in (ADR-0038).
     */
    private static String origin(String table, AnonymizerConfig config, long[] counts) {
        boolean configured = config.extraction().tables().stream().anyMatch(t -> t.table().equals(table));
        if (configured) return "direct";
        boolean child = counts[FkTraversal.Origin.FK_CHILD.ordinal()] > 0;
        boolean parent = counts[FkTraversal.Origin.FK_PARENT.ordinal()] > 0;
        if (child && parent) return "fk-child+fk-parent";
        return child ? "fk-child" : "fk-parent";
    }
}
