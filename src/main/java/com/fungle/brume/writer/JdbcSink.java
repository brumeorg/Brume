package com.fungle.brume.writer;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.util.SqlIdentifiers;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Gatherers;

/**
 * JDBC implementation of {@link Sink} — writes rows to the target database via one of
 * three strategies selected by {@link CopyMode}:
 *
 * <ul>
 *   <li>{@link CopyMode#NEVER}: batched {@code INSERT … ON CONFLICT DO NOTHING}
 *       (the historical behaviour before #5).</li>
 *   <li>{@link CopyMode#PREFER} (default): {@code COPY FROM stdin} via
 *       {@link CopyManager}; on any {@link DataAccessException}, fall back to the
 *       INSERT path for the same batch (handles PK conflicts and unsupported types
 *       such as {@code byte[]} / {@code bytea}).</li>
 *   <li>{@link CopyMode#FORCE}: {@code COPY} only; any error is recorded as a batch
 *       error.</li>
 * </ul>
 *
 * <p>Each batch runs in its own transaction with {@code session_replication_role = 'replica'}
 * so that FK constraints are not enforced on the target during the bulk load.
 *
 * <p>Batch errors are caught and logged at WARN level — the sink continues with the
 * next batch rather than aborting the entire chunk. After each chunk, the observed
 * batch error rate is compared against {@code brume.max-batch-error-rate}; if it
 * exceeds the threshold, {@link BatchErrorThresholdExceededException} is thrown.
 *
 * <p>Wired by {@code SinkConfig} via {@code @ConditionalOnProperty(brume.sink.type=jdbc)}.
 */
public class JdbcSink implements Sink {

    private static final Logger log = LoggerFactory.getLogger(JdbcSink.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final double maxBatchErrorRate;
    private final CopyMode copyMode;

    private WriteContext context;

    public JdbcSink(
            DataSource targetDataSource,
            PlatformTransactionManager targetTransactionManager,
            BrumeProperties brumeProperties) {
        this.jdbcTemplate = new JdbcTemplate(targetDataSource);
        this.transactionTemplate = new TransactionTemplate(targetTransactionManager);
        this.maxBatchErrorRate = brumeProperties.maxBatchErrorRate();
        this.copyMode = brumeProperties.sink().jdbc().copyMode();
        log.info("JdbcSink configured with copy-mode={}", this.copyMode);
    }

    @Override
    public void open(WriteContext context) {
        this.context = context;
    }

    @Override
    public void writeChunk(String table, List<ExtractedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            log.debug("JdbcSink: no rows to write for table {}", table);
            return;
        }
        if (context == null) {
            throw new IllegalStateException("JdbcSink.writeChunk called before open()");
        }

        String schemaName = context.schemaName();
        ExecutionReport report = context.report();
        int batchSize = (context.config() != null && context.config().extraction() != null)
                ? context.config().extraction().batchSize()
                : 1000;

        List<String> columns = new ArrayList<>(rows.getFirst().data().keySet());

        List<List<ExtractedRow>> batches = rows.stream()
                .gather(Gatherers.windowFixed(batchSize))
                .toList();

        AtomicLong totalInserted = new AtomicLong(0);
        AtomicLong totalConflicts = new AtomicLong(0);
        AtomicLong totalBatchErrors = new AtomicLong(0);

        int batchIndex = 0;
        for (List<ExtractedRow> batch : batches) {
            int currentBatchIndex = batchIndex;
            try {
                BatchResult result = writeBatch(schemaName, table, columns, batch);
                totalInserted.addAndGet(result.inserted());
                totalConflicts.addAndGet(result.conflicts());
                report.recordInserted(table, result.inserted(), result.conflicts());
                log.debug("JdbcSink: batch {} committed ({} rows) for {}.{}",
                        currentBatchIndex, batch.size(), schemaName, table);
            } catch (DataAccessException e) {
                report.recordBatchError(table, currentBatchIndex);
                totalBatchErrors.incrementAndGet();
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                log.warn("JdbcSink: batch {} failed for {}.{} — skipping batch. Root cause: [{}] {}",
                        currentBatchIndex, schemaName, table,
                        root.getClass().getSimpleName(), root.getMessage());
            }
            batchIndex++;
        }

        log.info("JdbcSink: {}.{} — {} inserted, {} conflicts, {} batch error(s)",
                schemaName, table,
                totalInserted.get(), totalConflicts.get(), totalBatchErrors.get());

        long batchCount = batches.size();
        long errorCount = totalBatchErrors.get();
        double errorRate = batchCount == 0 ? 0.0 : (double) errorCount / batchCount;

        if (errorRate > maxBatchErrorRate) {
            throw new BatchErrorThresholdExceededException(
                    table, errorCount, batchCount, errorRate, maxBatchErrorRate);
        }
    }

    @Override
    public void close() {
        this.context = null;
    }

    // -------------------------------------------------------------------------
    // Strategy dispatch
    // -------------------------------------------------------------------------

    private BatchResult writeBatch(String schemaName, String table,
                                   List<String> columns, List<ExtractedRow> batch) {
        return switch (copyMode) {
            case NEVER -> insertBatch(schemaName, table, columns, batch);
            case FORCE -> copyBatch(schemaName, table, columns, batch);
            case PREFER -> {
                try {
                    yield copyBatch(schemaName, table, columns, batch);
                } catch (DataAccessException copyError) {
                    Throwable rootCause = copyError.getCause() != null ? copyError.getCause() : copyError;
                    log.debug("JdbcSink: COPY failed for {}.{} (PREFER mode), falling back to INSERT — {} (cause: {})",
                            schemaName, table, copyError.getMessage(), rootCause.getMessage());
                    yield insertBatch(schemaName, table, columns, batch);
                } catch (TransactionException txError) {
                    // COPY failed and rollback also failed — the target connection is broken
                    // (typically EOFException / server closed). Hikari evicts the dead connection
                    // automatically; the INSERT fallback will obtain a fresh one.
                    log.warn("JdbcSink: COPY failed for {}.{} with broken connection (PREFER mode), "
                            + "falling back to INSERT — {}", schemaName, table, txError.getMessage());
                    yield insertBatch(schemaName, table, columns, batch);
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // INSERT path (NEVER, PREFER fallback)
    // -------------------------------------------------------------------------

    private BatchResult insertBatch(String schemaName, String table,
                                    List<String> columns, List<ExtractedRow> batch) {
        String columnList = columns.stream()
                .map(SqlIdentifiers::quote)
                .collect(java.util.stream.Collectors.joining(", "));
        String placeholders = "?, ".repeat(columns.size());
        String placeholderList = placeholders.substring(0, placeholders.length() - 2);
        String sql = "INSERT INTO " + SqlIdentifiers.quoteQualified(schemaName, table)
                + " (" + columnList + ") VALUES (" + placeholderList + ") ON CONFLICT DO NOTHING";

        int[][][] resultHolder = new int[1][][];
        transactionTemplate.executeWithoutResult(_ -> {
            jdbcTemplate.execute("SET session_replication_role = 'replica'");
            resultHolder[0] = jdbcTemplate.batchUpdate(sql, batch, batch.size(), (ps, row) -> {
                int paramIndex = 1;
                for (String col : columns) {
                    Object value = row.data().get(col);
                    if (value instanceof String s) {
                        // Use Types.OTHER so PostgreSQL infers the target column type
                        // (required for JSONB columns where the anonymized value is a String).
                        ps.setObject(paramIndex++, s, Types.OTHER);
                    } else {
                        ps.setObject(paramIndex++, value);
                    }
                }
            });
        });

        long inserted = 0;
        long conflicts = 0;
        int[][] result = resultHolder[0];
        if (result != null) {
            for (int[] subBatch : result) {
                for (int v : subBatch) {
                    if (v >= 1 || v == Statement.SUCCESS_NO_INFO) {
                        inserted++;
                    } else if (v == 0) {
                        conflicts++;
                    }
                }
            }
        }
        return new BatchResult(inserted, conflicts);
    }

    // -------------------------------------------------------------------------
    // COPY path (PREFER, FORCE)
    // -------------------------------------------------------------------------

    private BatchResult copyBatch(String schemaName, String table,
                                  List<String> columns, List<ExtractedRow> batch) {
        String columnList = columns.stream()
                .map(SqlIdentifiers::quote)
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = "COPY " + SqlIdentifiers.quoteQualified(schemaName, table)
                + " (" + columnList + ") FROM stdin";

        StringBuilder buf = new StringBuilder(batch.size() * 128);
        for (ExtractedRow row : batch) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String col : columns) {
                values.add(row.data().get(col));
            }
            TsvEscape.appendRow(buf, values);
        }

        Long copied = transactionTemplate.execute(_ -> jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (Statement s = conn.createStatement()) {
                s.execute("SET session_replication_role = 'replica'");
            }
            try {
                PGConnection pg = conn.unwrap(PGConnection.class);
                CopyManager copyManager = pg.getCopyAPI();
                return copyManager.copyIn(sql, new StringReader(buf.toString()));
            } catch (SQLException e) {
                throw new DataAccessResourceFailureException("COPY failed", e);
            } catch (java.io.IOException e) {
                throw new DataAccessResourceFailureException("COPY IO failed", e);
            } catch (UnsupportedOperationException e) {
                // TsvEscape.escape rejects byte[] (PROGRESS #5b). Bubble up as DataAccessException
                // so the PREFER mode catches it and falls back to INSERT.
                throw new DataAccessResourceFailureException(
                        "COPY rejected by TsvEscape (likely byte[]/bytea column)", e);
            }
        }));

        long copiedRows = copied == null ? 0L : copied;
        return new BatchResult(copiedRows, 0L);
    }

    /** Outcome of a single batch write. */
    private record BatchResult(long inserted, long conflicts) {}
}
