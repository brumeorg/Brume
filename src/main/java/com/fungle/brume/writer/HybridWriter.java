package com.fungle.brume.writer;

import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates writes from an {@link OrderedExtractionResult} to the configured {@link Sink}.
 *
 * <p>Two usage modes:
 * <ul>
 *   <li><strong>BATCH mode</strong> (single-call) — {@link #write(OrderedExtractionResult,
 *       AnonymizerConfig, DatabaseSchema, ExecutionReport)} opens a sink session, writes every
 *       table, and closes the session.</li>
 *   <li><strong>STREAMING mode</strong> (chunk-by-chunk) — the orchestrator brackets its own
 *       loop with {@link #beginSession(AnonymizerConfig, DatabaseSchema, ExecutionReport)} and
 *       {@link #endSession()}, calling {@link #writeChunk(String, List)} per chunk.</li>
 * </ul>
 *
 * <p>Only the rows present in the input are written — i.e. the subset selected by extraction
 * filters and FK parent resolution, never the full source table. This is the correct behaviour
 * for Brume's partial-extraction model.
 *
 * <p>FK constraint bypass is handled by the sink itself ({@link JdbcSink} sets
 * {@code session_replication_role = 'replica'} on each batch transaction).
 */
@Component
public class HybridWriter {

    private static final Logger log = LoggerFactory.getLogger(HybridWriter.class);

    private final Sink sink;
    private final String schemaName;

    public HybridWriter(
            Sink sink,
            com.fungle.brume.config.ReplicationProperties replicationProperties) {
        this.sink = sink;
        this.schemaName = replicationProperties.schema();
    }

    /**
     * Writes all tables from {@code result} to the configured sink in a single session.
     *
     * <p>Tables are processed in the order they appear in {@code result} (topological order,
     * parents before children). Only the rows present in {@code result} are written.
     */
    public void write(OrderedExtractionResult result, AnonymizerConfig config,
                      DatabaseSchema schema, ExecutionReport report) {
        Set<String> tablesWithRules = config.anonymization().tables().stream()
                .map(TableAnonymizationConfig::table)
                .collect(Collectors.toSet());

        beginSession(config, schema, report);
        try {
            for (String table : result.allTables()) {
                List<ExtractedRow> rows = result.getRows(table);
                if (rows.isEmpty()) {
                    log.debug("HybridWriter: skipping empty table {}", table);
                    continue;
                }
                String label = tablesWithRules.contains(table) ? "ANON" : "KEEP";
                log.info("HybridWriter: table {} → Sink [{}, {} rows]", table, label, rows.size());
                sink.writeChunk(table, rows);
            }
        } finally {
            endSession();
        }
    }

    /**
     * Opens a sink session. Must be paired with {@link #endSession()} via try/finally.
     */
    public void beginSession(AnonymizerConfig config, DatabaseSchema schema, ExecutionReport report) {
        sink.open(new WriteContext(schemaName, config, schema, report));
    }

    /**
     * Closes the sink session opened by {@link #beginSession(AnonymizerConfig, DatabaseSchema, ExecutionReport)}.
     */
    public void endSession() {
        sink.close();
    }

    /**
     * Writes a single chunk of rows to the configured sink. Must be called inside an open session.
     */
    public void writeChunk(String table, List<ExtractedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            log.debug("HybridWriter: skipping empty chunk for table {}", table);
            return;
        }
        log.info("HybridWriter: table {} → Sink [{} rows]", table, rows.size());
        sink.writeChunk(table, rows);
    }
}
