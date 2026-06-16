package com.fungle.brume.writer;

import com.fungle.brume.extraction.model.ExtractedRow;

import java.util.List;

/**
 * Write target abstraction for the Brume pipeline.
 *
 * <p>Decouples the orchestration (HybridWriter, ChunkedTableProcessor) from the actual
 * write target — a JDBC database today ({@link JdbcSink}), a SQL dump file or a no-op
 * dry-run sink in the future.
 *
 * <p>Lifecycle: {@link #open(WriteContext)} once, then {@link #writeChunk(String, List)}
 * many times (zero or more chunks across one or more tables, in topological order),
 * then {@link #close()} once. Single-threaded usage is required — Brume writes
 * sequentially. Implementations must accept {@code close()} after a failed
 * {@code open()} for cleanup.
 *
 * <p>{@link #close()} declares no checked exception — implementations translate
 * IO/SQL failures into runtime exceptions.
 */
public interface Sink extends AutoCloseable {

    /**
     * Opens a write session with the given context. Called exactly once before any
     * {@link #writeChunk(String, List)} call.
     *
     * @param context the session context (schema name, config, schema, report)
     */
    void open(WriteContext context);

    /**
     * Writes a chunk of rows belonging to the given table. May be called many times
     * per session, across one or more tables in topological order.
     *
     * @param table the unqualified table name
     * @param rows  the rows to write; may be empty (no-op)
     */
    void writeChunk(String table, List<ExtractedRow> rows);

    /**
     * Closes the write session and releases resources. Idempotent and safe to call
     * after a failed {@code open()}.
     */
    @Override
    void close();
}
