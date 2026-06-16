package com.fungle.brume.writer;

import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * File-based {@link Sink} producing a {@code psql -f} restorable {@code .sql} dump.
 *
 * <p>Format (in this exact order):
 * <ol>
 *   <li>SQL comment header — timestamp, Brume version, source/target schema names.</li>
 *   <li>DDL block — output of {@code pg_dump --schema-only} via
 *       {@link SchemaReplicator#dumpSchema(String)}.</li>
 *   <li>{@code SET session_replication_role = 'replica';} — bypasses FK enforcement
 *       so cyclic FK tables (e.g. {@code users.manager_id → users.id}) restore in any order.</li>
 *   <li>For each table written via {@link #writeChunk(String, List)} :
 *       <ul>
 *         <li>{@code COPY "schema"."table" (col1, col2, ...) FROM stdin;} on the first chunk.</li>
 *         <li>One TSV-escaped line per row (via {@link TsvEscape}), tab-separated, newline-terminated.</li>
 *         <li>{@code \.} terminator when the next table starts or at {@link #close()}.</li>
 *       </ul>
 *   </li>
 *   <li>{@code SET session_replication_role = 'origin';} — restores FK enforcement.</li>
 *   <li>SQL comment trailer.</li>
 * </ol>
 *
 * <p>Streams everything through a {@link BufferedWriter} chained on
 * {@link OutputStreamWriter} (UTF-8) chained on the supplied {@link OutputStream} —
 * never buffers the dump in memory. Ticket #4 (B9) will insert a
 * {@code GZIPOutputStream} / {@code ZstdOutputStream} between the
 * {@code FileOutputStream} and the writer.
 *
 * <p>Wired by {@code SinkConfig} via {@code @ConditionalOnProperty(brume.sink.type=dump)}.
 */
public class SqlFileSink implements Sink {

    private static final Logger log = LoggerFactory.getLogger(SqlFileSink.class);
    private static final String VERSION = "1.0.0";

    private final OutputStream rawOutputStream;
    private final Path deferredPath;
    private final CompressionType deferredCompression;
    private final SchemaReplicator schemaReplicator;
    /** Tracked under #25d. When true, the {@code -- generated_at: ...} line is omitted from the header. */
    private final boolean stripTimestamps;

    private WriteContext context;
    private Writer writer;
    private StringBuilder lineBuffer;
    private OutputStream openedChain;

    private String currentTable;
    private List<String> currentColumns;

    /**
     * Constructs a sink that writes to the given output stream. The stream is
     * not closed by the sink itself (caller / Spring lifecycle owns it) but is
     * always {@code flush}-ed in {@link #close()}.
     */
    public SqlFileSink(OutputStream rawOutputStream, SchemaReplicator schemaReplicator) {
        this(rawOutputStream, schemaReplicator, false);
    }

    /** Constructor variant that controls timestamp stripping. Tracked under #25d. */
    public SqlFileSink(OutputStream rawOutputStream, SchemaReplicator schemaReplicator,
                       boolean stripTimestamps) {
        this.rawOutputStream = rawOutputStream;
        this.deferredPath = null;
        this.deferredCompression = null;
        this.schemaReplicator = schemaReplicator;
        this.stripTimestamps = stripTimestamps;
    }

    /**
     * Constructs a sink that opens the configured file (with optional compression) lazily
     * in {@link #open(WriteContext)} — no I/O is performed at construction time. The stream
     * chain is owned by the sink and closed in {@link #close()}.
     */
    private SqlFileSink(Path deferredPath, CompressionType deferredCompression,
                        SchemaReplicator schemaReplicator, boolean stripTimestamps) {
        this.rawOutputStream = null;
        this.deferredPath = deferredPath;
        this.deferredCompression = deferredCompression;
        this.schemaReplicator = schemaReplicator;
        this.stripTimestamps = stripTimestamps;
    }

    @Override
    public void open(WriteContext context) {
        this.context = context;
        OutputStream stream = (rawOutputStream != null) ? rawOutputStream : openDeferredChain();
        this.writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        this.lineBuffer = new StringBuilder(256);
        this.currentTable = null;
        this.currentColumns = null;

        try {
            writeHeader();
            writeDdl();
            writer.write("SET session_replication_role = 'replica';\n\n");
        } catch (IOException | InterruptedException e) {
            // #79d — close the chain we opened in openDeferredChain() to avoid descriptor leak
            // when open() fails mid-init (writeHeader / writeDdl / pg_dump unavailable). Without
            // this, the caller may skip close() on a failed open() and the FileOutputStream
            // stays open — on Windows, the resulting file lock blocks any retry that wants
            // to write the same path.
            closeOpenedChainQuietly();
            throw new com.fungle.brume.error.WriteException(
                    com.fungle.brume.error.BrumeErrorCode.WRITE_DUMP_IO,
                    "SqlFileSink: failed during open(): " + e.getMessage(),
                    "Check the directory exists and is writable by the current user.",
                    asIo(e));
        }
    }

    private OutputStream openDeferredChain() {
        OutputStream fileStream = null;
        try {
            if (deferredPath.getParent() != null) {
                Files.createDirectories(deferredPath.getParent());
            }
            fileStream = Files.newOutputStream(deferredPath);
            assert deferredCompression != null;
            OutputStream chain = wrapCompression(fileStream, deferredCompression);
            this.openedChain = chain;
            return chain;
        } catch (IOException e) {
            // #79d — if wrapCompression throws after fileStream is opened (e.g. GZIPOutputStream
            // header write fails), the file stream is leaked. Close it explicitly before
            // re-throwing — same descriptor leak class as the writeHeader/writeDdl path above.
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException ignored) {
                    // Best-effort — the original open failure is the relevant error.
                }
            }
            throw new com.fungle.brume.error.WriteException(
                    com.fungle.brume.error.BrumeErrorCode.WRITE_DUMP_IO,
                    "SqlFileSink: failed to open output file '" + deferredPath + "': " + e.getMessage(),
                    "Check disk space, parent directory existence, and filesystem permissions.",
                    e);
        }
    }

    /**
     * Best-effort close of the deferred chain after a failed {@link #open(WriteContext)}.
     * Nulls the writer so a stray {@link #close()} call short-circuits cleanly. Tracked
     * under #79d.
     */
    private void closeOpenedChainQuietly() {
        if (openedChain != null) {
            try {
                openedChain.close();
            } catch (IOException ignored) {
                // Best-effort cleanup — the original open() failure is the relevant error.
            } finally {
                openedChain = null;
            }
        }
        writer = null;
    }

    @Override
    public void writeChunk(String table, List<ExtractedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (context == null || writer == null) {
            throw new IllegalStateException("SqlFileSink.writeChunk called before open()");
        }

        try {
            if (!table.equals(currentTable)) {
                terminateCurrentCopyBlock();
                startNewCopyBlock(table, rows.getFirst());
            }

            for (ExtractedRow row : rows) {
                writeRow(row);
            }
        } catch (IOException e) {
            throw new com.fungle.brume.error.WriteException(
                    com.fungle.brume.error.BrumeErrorCode.WRITE_DUMP_IO,
                    "SqlFileSink: failed to write chunk for table '" + table + "': " + e.getMessage(),
                    "Check disk space and filesystem state on the sink output-path.",
                    e);
        }
    }

    @Override
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            terminateCurrentCopyBlock();
            writer.write("SET session_replication_role = 'origin';\n");
            writer.write("\n-- =============================================\n");
            writer.write("-- Brume dump end\n");
            writer.write("-- =============================================\n");
            writer.flush();
        } catch (IOException e) {
            throw new com.fungle.brume.error.WriteException(
                    com.fungle.brume.error.BrumeErrorCode.WRITE_DUMP_IO,
                    "SqlFileSink: failed during close(): " + e.getMessage(),
                    "Inspect the partial dump file and rerun.",
                    e);
        } finally {
            writer = null;
            lineBuffer = null;
            currentTable = null;
            currentColumns = null;
            context = null;
            if (openedChain != null) {
                try {
                    openedChain.close();
                } catch (IOException e) {
                    throw new com.fungle.brume.error.WriteException(
                            com.fungle.brume.error.BrumeErrorCode.WRITE_DUMP_IO,
                            "SqlFileSink: failed to close output stream: " + e.getMessage(),
                            "Verify the compression chain (gzip/zstd buffer flush) and disk space.",
                            e);
                } finally {
                    openedChain = null;
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private void writeHeader() throws IOException {
        writer.write("-- =============================================\n");
        writer.write("-- Brume dump\n");
        if (!stripTimestamps) {
            writer.write("-- generated_at: " + Instant.now() + "\n");
        }
        writer.write("-- brume_version: " + VERSION + "\n");
        writer.write("-- source_schema: " + context.schemaName() + "\n");
        writer.write("-- target_schema: " + context.schemaName() + "\n");
        writer.write("-- =============================================\n\n");
    }

    private void writeDdl() throws IOException, InterruptedException {
        log.info("SqlFileSink: fetching DDL for schema '{}'", context.schemaName());
        String ddl = schemaReplicator.dumpSchema(context.schemaName());
        writer.write("-- DDL block (pg_dump --schema-only)\n");
        writer.write(ddl);
        if (!ddl.endsWith("\n")) {
            writer.write('\n');
        }
        writer.write('\n');
    }

    private void startNewCopyBlock(String table, ExtractedRow firstRow) throws IOException {
        currentTable = table;
        currentColumns = new ArrayList<>(firstRow.data().keySet());

        String columnList = currentColumns.stream()
                .map(SqlIdentifiers::quote)
                .collect(Collectors.joining(", "));
        String qualifiedTable = SqlIdentifiers.quoteQualified(context.schemaName(), table);
        writer.write("COPY " + qualifiedTable + " (" + columnList + ") FROM stdin;\n");
        log.debug("SqlFileSink: COPY block opened for {} ({} columns)", qualifiedTable, currentColumns.size());
    }

    private void terminateCurrentCopyBlock() throws IOException {
        if (currentTable != null) {
            writer.write("\\.\n\n");
            log.debug("SqlFileSink: COPY block terminated for table {}", currentTable);
            currentTable = null;
            currentColumns = null;
        }
    }

    private void writeRow(ExtractedRow row) throws IOException {
        lineBuffer.setLength(0);
        List<Object> values = new ArrayList<>(currentColumns.size());
        for (String col : currentColumns) {
            values.add(row.data().get(col));
        }
        TsvEscape.appendRow(lineBuffer, values);
        writer.write(lineBuffer.toString());
    }

    private static IOException asIo(Throwable t) {
        if (t instanceof IOException io) return io;
        return new IOException(t);
    }

    /**
     * Returns a {@link SqlFileSink} that will write to the file at {@code outputPath} when
     * {@link #open(WriteContext)} is called, optionally chained through a compression layer
     * ({@link CompressionType#GZIP gzip} or {@link CompressionType#ZSTD zstd}). Parent
     * directories are created lazily on open. The returned sink owns the underlying stream
     * chain and closes it on {@link #close()}.
     *
     * <p>No I/O is performed by this method — the file is created (and any pre-existing file
     * truncated) only when the pipeline actually opens the sink. This guards against
     * read-only commands (e.g. {@code plan}) clobbering a previously produced dump just by
     * having the sink wired.
     *
     * <p>The output path is taken as-is — the caller is responsible for choosing a
     * coherent extension (e.g. {@code .sql.gz} for {@code GZIP}, {@code .sql.zst} for
     * {@code ZSTD}). No extension auto-append is performed.
     *
     * <p>Used by the Spring wiring to instantiate the sink with a file-backed stream.
     */
    public static SqlFileSink writingToFile(
            Path outputPath, CompressionType compression, SchemaReplicator schemaReplicator) {
        return new SqlFileSink(outputPath, compression, schemaReplicator, false);
    }

    /** Variant that controls timestamp stripping in the header. Tracked under #25d. */
    public static SqlFileSink writingToFile(
            Path outputPath, CompressionType compression, SchemaReplicator schemaReplicator,
            boolean stripTimestamps) {
        return new SqlFileSink(outputPath, compression, schemaReplicator, stripTimestamps);
    }

    private static OutputStream wrapCompression(OutputStream raw, CompressionType compression) throws IOException {
        return switch (compression) {
            case NONE -> raw;
            case GZIP -> new GZIPOutputStream(raw);
            case ZSTD -> new com.github.luben.zstd.ZstdOutputStream(raw);
        };
    }
}
