package com.fungle.brume.extraction;

import com.fungle.brume.anonymization.AnonymizationEngine;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.monitoring.HeapMonitor;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.GraphAnalyzer;
import com.fungle.brume.shutdown.CancellationToken;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.writer.HybridWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk-oriented streaming orchestration for the hot path extract → anonymize → write.
 *
 * <p>For each configured table, processing is split into two passes:
 * <ol>
 *   <li>A narrow FK pre-resolution pass that fetches distinct parent PKs and recursively resolves their parents</li>
 *   <li>A streaming read pass that emits explicit chunks, anonymizes them, writes them, then drops references</li>
 * </ol>
 *
 * <p>This keeps memory bounded by the configured chunk size rather than by the full table size.
 */
@Component
public class ChunkedTableProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChunkedTableProcessor.class);

    private final CursorReader cursorReader;
    private final FkParentResolver fkParentResolver;
    private final GraphAnalyzer graphAnalyzer;
    private final AnonymizationEngine anonymizationEngine;
    private final HybridWriter hybridWriter;
    private final ReplicationProperties replicationProperties;
    private final HeapMonitor heapMonitor;
    private final CancellationToken cancellationToken;
    private final com.fungle.brume.checkpoint.CheckpointService checkpointService;

    public ChunkedTableProcessor(
            CursorReader cursorReader,
            FkParentResolver fkParentResolver,
            GraphAnalyzer graphAnalyzer,
            AnonymizationEngine anonymizationEngine,
            HybridWriter hybridWriter,
            ReplicationProperties replicationProperties,
            HeapMonitor heapMonitor,
            CancellationToken cancellationToken,
            com.fungle.brume.checkpoint.CheckpointService checkpointService) {
        this.cursorReader = cursorReader;
        this.fkParentResolver = fkParentResolver;
        this.graphAnalyzer = graphAnalyzer;
        this.anonymizationEngine = anonymizationEngine;
        this.hybridWriter = hybridWriter;
        this.replicationProperties = replicationProperties;
        this.heapMonitor = heapMonitor;
        this.cancellationToken = cancellationToken;
        this.checkpointService = checkpointService;
    }

    public void processAll(AnonymizerConfig config, DatabaseSchema schema, ExecutionReport report) {
        List<TableExtractionConfig> extractionTables = config.extraction().tables();
        if (extractionTables == null || extractionTables.isEmpty()) {
            log.warn("No tables declared in extraction.tables config — nothing to process in STREAMING mode.");
            return;
        }

        Map<String, TableExtractionConfig> configByTable = new HashMap<>();
        for (TableExtractionConfig tableConfig : extractionTables) {
            configByTable.put(tableConfig.table(), tableConfig);
        }

        hybridWriter.beginSession(config, schema, report);
        try {
            // Cross-table dedup index: outer key is the table name, inner set holds the raw
            // PK values already extracted/written. Storing raw values rather than synthetic
            // "table.col.value" strings cuts per-entry footprint from ~70 B to ~16-32 B
            // (B4 / ADR-0001). Per-table release via FK reverse ref-count : #11b / B4b.
            Map<String, Set<Object>> processedPrimaryKeys = new HashMap<>();
            List<String> orderedTables = orderConfiguredTables(extractionTables, schema);
            int fkDepth = config.extraction() != null
                    ? config.extraction().fkDepth()
                    : replicationProperties.fkDepth();
            int fetchSize = config.extraction() != null ? config.extraction().fetchSize() : 1_000;
            int chunkSize = config.extraction() != null ? config.extraction().chunkSize() : 10_000;
            // #11b / B4b — release pkIndex entries as soon as no future table can consult them
            // via FK depth resolution. Caps memory at the working set rather than the full run.
            PkReleaseTracker releaseTracker = new PkReleaseTracker(orderedTables, schema, fkDepth, graphAnalyzer);

            // FK child resolution seeds: only the PKs of the configured seed tables.
            // Using parent PKs here would cause the child resolver to fetch ALL rows of
            // any table that has a FK to a parent (e.g. every dossier in the group),
            // instead of only the rows related to the seed filter (e.g. BORNAND's dossiers).
            Map<String, Set<Object>> seedPks = fkDepth > 0 ? new HashMap<>() : Collections.emptyMap();

            for (String table : orderedTables) {
                cancellationToken.checkpoint(); // #24/A22 — between tables
                // #25/A19 — skip tables already written in a previous interrupted
                // run when --resume is active. No-op when checkpoint is disabled.
                if (checkpointService.shouldSkip(table)) {
                    log.info("Checkpoint skip: '{}' already completed in a previous run", table);
                    // #11b — still decrement so ref-counts stay self-consistent for tables
                    // processed later in this resume. processedPrimaryKeys[table] is empty
                    // (nothing populated it on this run), so remove() is a no-op here.
                    releaseTracker.onTableCompleted(table, processedPrimaryKeys);
                    continue;
                }
                Set<Object> directPks = processTable(configByTable.get(table), config, schema, report, processedPrimaryKeys);
                // #25/A19 — table fully written : persist the checkpoint atomically.
                // SIGTERM before this line = the table is re-played on resume
                // (idempotent via #29). No-op when checkpoint is disabled.
                checkpointService.markCompleted(table);

                // Seed FK child resolution ONLY from directly-extracted rows (filter match).
                // processedPrimaryKeys[table] also contains parent rows resolved by
                // resolveParentsForTable — for auto-referential tables (e.g. utilisateur →
                // utilisateur) those ancestors would incorrectly seed FK child expansion,
                // multiplying the result set by orders of magnitude (bug #39).
                if (fkDepth > 0 && !directPks.isEmpty()) {
                    seedPks.computeIfAbsent(table, _ -> new HashSet<>()).addAll(directPks);
                }

                // #11b — evict pkIndex entries for tables no future descendant will consult.
                releaseTracker.onTableCompleted(table, processedPrimaryKeys);
            }

            // FK child resolution: stream tables that reference the seed rows via FK
            // (the inverse direction of FkParentResolver). Runs after the main loop so all
            // parent rows are already written. Seeded only from configured-table PKs so the
            // expansion stays bounded to the filtered dataset (not the full parent tables).
            // Rows are streamed and written in chunks — no full result set loaded in memory.
            if (fkDepth > 0 && !seedPks.isEmpty()) {
                resolveFkChildren(schema, config, report, seedPks, processedPrimaryKeys,
                        fkDepth, chunkSize, fetchSize);
            }
        } finally {
            hybridWriter.endSession();
        }
    }

    Set<Object> processTable(TableExtractionConfig tableConfig,
                             AnonymizerConfig config,
                             DatabaseSchema schema,
                             ExecutionReport report,
                             Map<String, Set<Object>> processedPrimaryKeys) {
        if (tableConfig == null) {
            return Collections.emptySet();
        }

        String schemaName = replicationProperties.schema();
        String table = tableConfig.table();
        String filter = tableConfig.filter();
        int fetchSize = config.extraction() != null ? config.extraction().fetchSize() : 1_000;
        int chunkSize = config.extraction() != null ? config.extraction().chunkSize() : 10_000;
        int fkDepth = config.extraction() != null ? config.extraction().fkDepth() : replicationProperties.fkDepth();

        // Don't log the raw filter — it can carry PII (e.g. email = '…'). Log only its
        // presence; the filter content is auditable through the config file. (#16, ADR-0025)
        boolean hasFilter = filter != null && !filter.isBlank();
        log.info("Chunked streaming pipeline: processing {}.{}{} (fetch size: {}, chunk size: {})",
                schemaName,
                table,
                hasFilter ? " (filtered)" : "",
                fetchSize,
                chunkSize);

        ExtractionResult parentRows = fkParentResolver.resolveParentsForTable(
                schemaName,
                table,
                filter,
                schema,
                fkDepth,
                fetchSize,
                processedPrimaryKeys,
                report);

        if (parentRows.totalRowCount() > 0) {
            OrderedExtractionResult orderedParents = parentRows.toOrdered(graphAnalyzer.topologicalSort(schema));
            for (String parentTable : orderedParents.allTables()) {
                // #25/A19 — when resuming, parent tables already in completedTables
                // are fully written on target — re-writing them via FK propagation
                // is redundant (idempotent via #29 but wastes I/O).
                if (checkpointService.shouldSkip(parentTable)) {
                    log.debug("Checkpoint skip parent rows for '{}' (already completed in a previous run)",
                            parentTable);
                    continue;
                }
                writeParentRows(parentTable, orderedParents.getRows(parentTable), config, schema, report,
                        chunkSize, processedPrimaryKeys);
            }
        }

        // #30b — pass the single-col PK as orderByColumn so the SELECT * is deterministic.
        // null for composite/missing PK : the SELECT falls back to plan-dependent order
        // (cf. WARN emitted at boot when brume.sink.strip-timestamps=true).
        String orderByColumn = pkColumnFor(table, schema);
        String pkCol = orderByColumn;
        Set<Object> directPks = new LinkedHashSet<>();
        cursorReader.readChunked(schemaName, table, filter, fetchSize, chunkSize, orderByColumn, rawChunk -> {
            cancellationToken.checkpoint(); // #24/A22 — between chunks
            List<ExtractedRow> sourceChunk = new ArrayList<>(rawChunk.size());
            List<ExtractedRow> anonymizedChunk = new ArrayList<>(rawChunk.size());
            for (ExtractedRow row : rawChunk) {
                report.recordExtracted(table, 1L);
                if (isAlreadyProcessed(row, schema, processedPrimaryKeys)) {
                    continue;
                }
                if (pkCol != null) {
                    Object pk = row.data().get(pkCol);
                    if (pk != null) directPks.add(pk);
                }
                sourceChunk.add(row);
                anonymizedChunk.add(anonymizationEngine.anonymizeRow(row, config.anonymization(), report));
            }
            heapMonitor.sample(report, "chunk-build:" + table);
            writeChunk(table, anonymizedChunk, sourceChunk, config, schema, report, processedPrimaryKeys);
            heapMonitor.sample(report, "chunk-flush:" + table);
        });
        return directPks;
    }

    /**
     * Streams and writes FK child tables (tables referencing the configured seed rows via FK).
     *
     * <p>Uses a frontier-based BFS up to {@code fkDepth} levels. At each level, for each
     * FK edge pointing to a frontier table, child rows are streamed directly from the DB
     * via {@link CursorReader#streamByForeignKey} and written in chunks immediately — no
     * full result set is accumulated in memory. Only the child PK sets (much smaller than
     * full rows) are kept for the next depth level.
     *
     * <p>Seeded only from configured-table PKs so the expansion stays bounded: with
     * {@code utilisateur} filtered to BORNAND, only BORNAND's dossiers are fetched,
     * not every dossier in BORNAND's group.
     */
    private void resolveFkChildren(
            DatabaseSchema schema, AnonymizerConfig config, ExecutionReport report,
            Map<String, Set<Object>> seedPks,
            Map<String, Set<Object>> processedPrimaryKeys,
            int fkDepth, int chunkSize, int fetchSize) {

        String schemaName = replicationProperties.schema();
        Map<String, List<ForeignKey>> inverseIndex = buildInverseIndex(schema);
        Map<String, Set<Object>> expandedPks = new HashMap<>();
        Map<String, Set<Object>> frontier = new HashMap<>(seedPks);
        Set<String> byteaWarnedOnce = new HashSet<>();

        for (int depth = 0; depth < fkDepth; depth++) {
            // Collect unexpanded PKs from the current frontier
            Map<String, Set<Object>> toExpand = new LinkedHashMap<>();
            for (Map.Entry<String, Set<Object>> e : frontier.entrySet()) {
                Set<Object> already = expandedPks.getOrDefault(e.getKey(), Set.of());
                Set<Object> fresh = new LinkedHashSet<>();
                for (Object pk : e.getValue()) {
                    if (!already.contains(pk)) fresh.add(pk);
                }
                if (!fresh.isEmpty()) toExpand.put(e.getKey(), fresh);
            }
            if (toExpand.isEmpty()) break;

            Map<String, Set<Object>> nextFrontier = new LinkedHashMap<>();
            long totalAdded = 0;

            for (Map.Entry<String, Set<Object>> e : toExpand.entrySet()) {
                String parentTable = e.getKey();
                Set<Object> parentPks = e.getValue();

                for (ForeignKey fk : inverseIndex.getOrDefault(parentTable, List.of())) {
                    String childTable = fk.fromTable();
                    if (seedPks.containsKey(childTable)) continue; // already a configured seed

                    TableMetadata childMeta = schema.get(childTable);
                    String childPkCol = childMeta != null ? childMeta.primaryKeyColumn() : null;

                    List<ExtractedRow> sourceBuffer = new ArrayList<>(chunkSize);
                    List<ExtractedRow> anonBuffer = new ArrayList<>(chunkSize);
                    long[] addedForEdge = {0};

                    cursorReader.streamByForeignKey(schemaName, childTable, fk.fromColumn(),
                            parentPks, fetchSize, row -> {
                        if (isAlreadyProcessed(row, schema, processedPrimaryKeys)) return;
                        row = nullifyBytea(row, byteaWarnedOnce);
                        if (childPkCol != null) {
                            Object pk = row.data().get(childPkCol);
                            if (pk != null) nextFrontier.computeIfAbsent(childTable,
                                    _ -> new LinkedHashSet<>()).add(pk);
                        }
                        sourceBuffer.add(row);
                        anonBuffer.add(anonymizationEngine.anonymizeRow(row, config.anonymization(), report));
                        addedForEdge[0]++;
                        if (anonBuffer.size() >= chunkSize) {
                            writeChunk(childTable, List.copyOf(anonBuffer), List.copyOf(sourceBuffer),
                                    config, schema, report, processedPrimaryKeys);
                            sourceBuffer.clear();
                            anonBuffer.clear();
                        }
                    });

                    if (!anonBuffer.isEmpty()) {
                        writeChunk(childTable, List.copyOf(anonBuffer), List.copyOf(sourceBuffer),
                                config, schema, report, processedPrimaryKeys);
                    }

                    if (addedForEdge[0] > 0) {
                        totalAdded += addedForEdge[0];
                        log.debug("FK child (STREAMING) depth {}: {} row(s) from {} via {}.{}→{}.{}",
                                depth + 1, addedForEdge[0], childTable,
                                childTable, fk.fromColumn(), parentTable, fk.toColumn());
                        report.recordFkChild(childTable, addedForEdge[0]);
                    }
                }
            }

            toExpand.forEach((t, pks) -> expandedPks.computeIfAbsent(t, _ -> new HashSet<>()).addAll(pks));
            log.debug("FK child resolution (STREAMING) pass {}/{}: {} row(s) added", depth + 1, fkDepth, totalAdded);

            if (totalAdded == 0) break;
            frontier = nextFrontier;
        }
    }

    /**
     * Replaces {@code byte[]} values in a FK child row with {@code null} so they pass through
     * {@code TsvEscape} without aborting the run. V1 does not support {@code bytea} columns (#5/B1).
     * Logs a WARN once per {@code table.column} pair to avoid log spam over large result sets.
     */
    private ExtractedRow nullifyBytea(ExtractedRow row, Set<String> warnedOnce) {
        boolean hasBytea = false;
        for (Object v : row.data().values()) {
            if (v instanceof byte[]) { hasBytea = true; break; }
        }
        if (!hasBytea) return row;

        Map<String, Object> cleaned = new LinkedHashMap<>(row.data());
        for (Map.Entry<String, Object> entry : cleaned.entrySet()) {
            if (entry.getValue() instanceof byte[]) {
                String key = row.table() + "." + entry.getKey();
                if (warnedOnce.add(key)) {
                    log.warn("FK child (STREAMING): bytea column {} replaced with NULL "
                            + "(TsvEscape V1 limitation — tracked as #5/B1). "
                            + "Configure an explicit strategy or wait for bytea support.", key);
                }
                entry.setValue(null);
            }
        }
        return new ExtractedRow(row.table(), cleaned);
    }

    private static Map<String, List<ForeignKey>> buildInverseIndex(DatabaseSchema schema) {
        Map<String, List<ForeignKey>> index = new HashMap<>();
        for (TableMetadata meta : schema.tables().values()) {
            for (ForeignKey fk : meta.foreignKeys()) {
                index.computeIfAbsent(fk.toTable(), _ -> new ArrayList<>()).add(fk);
            }
        }
        return index;
    }

    private List<String> orderConfiguredTables(List<TableExtractionConfig> extractionTables, DatabaseSchema schema) {
        Set<String> configuredTables = new HashSet<>();
        for (TableExtractionConfig tableConfig : extractionTables) {
            configuredTables.add(tableConfig.table());
        }

        List<String> ordered = new ArrayList<>();
        for (String table : graphAnalyzer.topologicalSort(schema)) {
            if (configuredTables.contains(table)) {
                ordered.add(table);
            }
        }

        for (TableExtractionConfig tableConfig : extractionTables) {
            if (!ordered.contains(tableConfig.table())) {
                ordered.add(tableConfig.table());
            }
        }

        return ordered;
    }

    private void writeParentRows(String table,
                                 List<ExtractedRow> rows,
                                 AnonymizerConfig config,
                                 DatabaseSchema schema,
                                 ExecutionReport report,
                                 int chunkSize,
                                 Map<String, Set<Object>> processedPrimaryKeys) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        // #30b — sort parent rows by PK before writing. Even with ORDER BY pk inside
        // CursorReader.readByPrimaryKeys, virtual-thread parallelism in FkParentResolver
        // means several threads can append to the same parent table's synchronizedList in
        // an indeterminate interleaving. Sort here restores a stable intra-table order.
        // Cost : O(p log p) where p = parent rows extracted via FK depth — bounded by the
        // FK closure of the seed filter, typically small vs the main streaming flow.
        rows = sortByPkColumnIfPossible(rows, table, schema);

        List<ExtractedRow> sourceBuffer = new ArrayList<>(chunkSize);
        List<ExtractedRow> anonymizedBuffer = new ArrayList<>(chunkSize);
        for (ExtractedRow row : rows) {
            if (isAlreadyProcessed(row, schema, processedPrimaryKeys)) {
                continue;
            }
            sourceBuffer.add(row);
            anonymizedBuffer.add(anonymizationEngine.anonymizeRow(row, config.anonymization(), report));
            if (anonymizedBuffer.size() >= chunkSize) {
                writeChunk(table, anonymizedBuffer, sourceBuffer, config, schema, report, processedPrimaryKeys);
                sourceBuffer = new ArrayList<>(chunkSize);
                anonymizedBuffer = new ArrayList<>(chunkSize);
            }
        }
        writeChunk(table, anonymizedBuffer, sourceBuffer, config, schema, report, processedPrimaryKeys);
    }

    /**
     * Returns the single-column primary key name for {@code table} when one exists, or
     * {@code null} for composite/missing PK. Tracked under #30b.
     */
    private static String pkColumnFor(String table, DatabaseSchema schema) {
        TableMetadata meta = schema.get(table);
        return meta == null ? null : meta.primaryKeyColumn();
    }

    /**
     * Returns a list of rows sorted by their PK column, or the original list when no
     * single-col PK is declared or values aren't mutually {@link Comparable}. Tracked under #30b.
     */
    private static List<ExtractedRow> sortByPkColumnIfPossible(
            List<ExtractedRow> rows, String table, DatabaseSchema schema) {
        String pkCol = pkColumnFor(table, schema);
        if (pkCol == null || rows.size() < 2) {
            return rows;
        }
        List<ExtractedRow> sorted = new ArrayList<>(rows);
        try {
            sorted.sort((a, b) -> {
                @SuppressWarnings({"rawtypes", "unchecked"})
                int cmp = ((Comparable) a.data().get(pkCol)).compareTo(b.data().get(pkCol));
                return cmp;
            });
            return sorted;
        } catch (ClassCastException | NullPointerException e) {
            // PK values not comparable or null — give up on the sort, return original.
            log.debug("sortByPkColumnIfPossible '{}': PK values not Comparable/null — "
                    + "intra-table order may vary across runs", table);
            return rows;
        }
    }

    private void writeChunk(String table,
                            List<ExtractedRow> rows,
                            List<ExtractedRow> sourceRows,
                            AnonymizerConfig config,
                            DatabaseSchema schema,
                            ExecutionReport report,
                            Map<String, Set<Object>> processedPrimaryKeys) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<ExtractedRow> rowsToWrite = List.copyOf(rows);
        heapMonitor.sample(report, "before-write:" + table);
        hybridWriter.writeChunk(table, rowsToWrite);
        markProcessed(sourceRows, schema, processedPrimaryKeys);
        heapMonitor.sample(report, "after-write:" + table);
    }

    private boolean isAlreadyProcessed(ExtractedRow row, DatabaseSchema schema,
                                       Map<String, Set<Object>> processedPrimaryKeys) {
        Object pkValue = primaryKeyValue(row, schema);
        if (pkValue == null) {
            return false;
        }
        Set<Object> tableSet = processedPrimaryKeys.get(row.table());
        return tableSet != null && tableSet.contains(pkValue);
    }

    private void markProcessed(List<ExtractedRow> rows, DatabaseSchema schema,
                               Map<String, Set<Object>> processedPrimaryKeys) {
        for (ExtractedRow row : rows) {
            Object pkValue = primaryKeyValue(row, schema);
            if (pkValue != null) {
                processedPrimaryKeys
                        .computeIfAbsent(row.table(), _ -> new HashSet<>())
                        .add(pkValue);
            }
        }
    }

    /**
     * Returns the row's primary key value, or {@code null} when the table has no
     * single-column PK or the PK value is itself {@code null} (skip dedup).
     */
    private Object primaryKeyValue(ExtractedRow row, DatabaseSchema schema) {
        TableMetadata metadata = schema.get(row.table());
        if (metadata == null || metadata.primaryKeyColumn() == null) {
            return null;
        }
        return row.data().get(metadata.primaryKeyColumn());
    }
}


