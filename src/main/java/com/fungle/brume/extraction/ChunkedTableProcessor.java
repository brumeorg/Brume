package com.fungle.brume.extraction;

import com.fungle.brume.anonymization.AnonymizationEngine;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.monitoring.HeapMonitor;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.OrphanReference;
import com.fungle.brume.schema.GraphAnalyzer;
import com.fungle.brume.shutdown.CancellationToken;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.writer.HybridWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Chunk-oriented streaming orchestration for the hot path extract → anonymize → write.
 *
 * <p>The rows to copy are found by {@link FkTraversal} (ADR-0050) : the configured tables are
 * streamed first, then the FK graph is walked level by level within {@code fk_depth}, in both
 * directions. Each accepted row is anonymized and buffered per table ; buffers are written
 * when they reach {@code chunk_size} and at the end of every level, so memory stays bounded by
 * the chunk size plus the keys of the copied rows.
 */
@Component
public class ChunkedTableProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChunkedTableProcessor.class);

    private final CursorReader cursorReader;
    private final GraphAnalyzer graphAnalyzer;
    private final AnonymizationEngine anonymizationEngine;
    private final HybridWriter hybridWriter;
    private final ReplicationProperties replicationProperties;
    private final HeapMonitor heapMonitor;
    private final CancellationToken cancellationToken;
    private final com.fungle.brume.checkpoint.CheckpointService checkpointService;

    public ChunkedTableProcessor(
            CursorReader cursorReader,
            GraphAnalyzer graphAnalyzer,
            AnonymizationEngine anonymizationEngine,
            HybridWriter hybridWriter,
            ReplicationProperties replicationProperties,
            HeapMonitor heapMonitor,
            CancellationToken cancellationToken,
            com.fungle.brume.checkpoint.CheckpointService checkpointService) {
        this.cursorReader = cursorReader;
        this.graphAnalyzer = graphAnalyzer;
        this.anonymizationEngine = anonymizationEngine;
        this.hybridWriter = hybridWriter;
        this.replicationProperties = replicationProperties;
        this.heapMonitor = heapMonitor;
        this.cancellationToken = cancellationToken;
        this.checkpointService = checkpointService;
    }

    public void processAll(AnonymizerConfig config, DatabaseSchema schema, ExecutionReport report) {
        ExtractionConfig extraction = config.extraction();
        List<TableExtractionConfig> extractionTables = extraction.tables();
        if (extractionTables == null || extractionTables.isEmpty()) {
            log.warn("No tables declared in extraction.tables config — nothing to process in STREAMING mode.");
            return;
        }

        Map<String, TableExtractionConfig> configByTable = new LinkedHashMap<>();
        for (TableExtractionConfig tableConfig : extractionTables) {
            configByTable.put(tableConfig.table(), tableConfig);
        }
        List<TableExtractionConfig> seeds = new ArrayList<>();
        for (String table : orderConfiguredTables(extractionTables, schema)) {
            seeds.add(configByTable.get(table));
        }

        hybridWriter.beginSession(config, schema, report);
        try {
            StreamingSink sink = new StreamingSink(config, schema, report, seeds);
            List<OrphanReference> orphans = new FkTraversal(schema, extraction.fkDepth(),
                    extraction.orphanPolicy(), cancellationToken::checkpoint)
                    .run(seeds, new SourceReader(schema, extraction.fetchSize()), sink);
            sink.finish();
            report.recordOrphanReferences(orphans);
            orphans.forEach(o -> log.warn("[ORPHANS] {} (extraction.orphan_policy: ALLOW_ORPHANS)", o.describe()));
        } finally {
            hybridWriter.endSession();
        }
    }

    /** Full-row source reads, ordered by primary key so the dump order is stable (#30b). */
    private final class SourceReader implements FkTraversal.RowReader {

        private final DatabaseSchema schema;
        private final int fetchSize;

        SourceReader(DatabaseSchema schema, int fetchSize) {
            this.schema = schema;
            this.fetchSize = fetchSize;
        }

        @Override
        public void readSeeds(String table, String filter, Consumer<ExtractedRow> consumer) {
            // Don't log the raw filter — it can carry PII (e.g. email = '…'). Log only its
            // presence; the filter content is auditable through the config file. (#16, ADR-0025)
            log.info("Chunked streaming pipeline: processing {}.{}{} (fetch size: {})",
                    replicationProperties.schema(), table,
                    filter != null && !filter.isBlank() ? " (filtered)" : "", fetchSize);
            cursorReader.streamRows(replicationProperties.schema(), table, filter, fetchSize,
                    pkColumnFor(table, schema), consumer);
        }

        @Override
        public void readWhereIn(String table, List<String> keyColumns, Collection<Object> keys,
                                Consumer<ExtractedRow> consumer) {
            cursorReader.streamWhereIn(replicationProperties.schema(), table, keyColumns, keys,
                    null, FkTraversal.readOrder(schema, table, keyColumns), fetchSize, consumer);
        }
    }

    /**
     * Anonymizes and buffers the accepted rows, writes them per table, and marks a configured
     * table completed for {@code --resume} once the walk can no longer add rows to it (#25).
     * Rows of a table completed by a previous run are walked through but never rewritten.
     */
    private final class StreamingSink implements FkTraversal.RowSink {

        private final AnonymizerConfig config;
        private final DatabaseSchema schema;
        private final ExecutionReport report;
        private final int chunkSize;
        private final List<String> writeOrder;
        private final Set<String> notYetCompleted = new LinkedHashSet<>();
        private final Map<String, List<ExtractedRow>> anonymizedBuffers = new LinkedHashMap<>();
        private final Set<String> byteaWarnedOnce = new HashSet<>();

        StreamingSink(AnonymizerConfig config, DatabaseSchema schema, ExecutionReport report,
                      List<TableExtractionConfig> seeds) {
            this.config = config;
            this.schema = schema;
            this.report = report;
            this.chunkSize = config.extraction().chunkSize();
            this.writeOrder = graphAnalyzer.topologicalSort(schema);
            seeds.forEach(seed -> notYetCompleted.add(seed.table()));
        }

        @Override
        public void accept(ExtractedRow row, FkTraversal.Origin origin) {
            String table = row.table();
            if (checkpointService.shouldSkip(table)) {
                return;
            }
            switch (origin) {
                case DIRECT -> report.recordExtracted(table, 1L);
                case FK_PARENT -> report.recordFkParent(table, 1L);
                case FK_CHILD -> {
                    report.recordFkChild(table, 1L);
                    row = nullifyBytea(row, byteaWarnedOnce);
                }
            }
            List<ExtractedRow> anonymized = anonymizedBuffers.computeIfAbsent(table, _ -> new ArrayList<>());
            anonymized.add(anonymizationEngine.anonymizeRow(row, config.anonymization(), report));
            if (anonymized.size() >= chunkSize) {
                flush(table);
            }
        }

        @Override
        public void levelCompleted(int level, Set<String> openTables) {
            flushAll();
            heapMonitor.sample(report, "fk-walk-level:" + level);
            for (String table : List.copyOf(notYetCompleted)) {
                if (!openTables.contains(table)) {
                    complete(table);
                }
            }
        }

        void finish() {
            flushAll();
            List.copyOf(notYetCompleted).forEach(this::complete);
        }

        private void complete(String table) {
            // #25/A19 — no further row can reach this table : persist the checkpoint atomically.
            // SIGTERM before this line = the table is re-played on resume (idempotent via #29).
            checkpointService.markCompleted(table);
            notYetCompleted.remove(table);
        }

        private void flushAll() {
            cancellationToken.checkpoint(); // #24/A22 — between levels
            for (String table : writeOrder) {
                flush(table);
            }
            for (String table : List.copyOf(anonymizedBuffers.keySet())) {
                flush(table); // tables outside the topological order (defensive)
            }
        }

        private void flush(String table) {
            List<ExtractedRow> rows = anonymizedBuffers.remove(table);
            if (rows == null || rows.isEmpty()) {
                return;
            }
            heapMonitor.sample(report, "before-write:" + table);
            hybridWriter.writeChunk(table, List.copyOf(rows));
            heapMonitor.sample(report, "after-write:" + table);
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

    /**
     * Returns the single-column primary key name for {@code table} when one exists, or
     * {@code null} for composite/missing PK. Tracked under #30b.
     */
    private static String pkColumnFor(String table, DatabaseSchema schema) {
        TableMetadata meta = schema.get(table);
        return meta == null ? null : meta.singlePrimaryKeyColumn();
    }
}
