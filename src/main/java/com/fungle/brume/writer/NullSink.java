package com.fungle.brume.writer;

import com.fungle.brume.extraction.model.ExtractedRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Counting-only {@link Sink} implementation — accepts the full pipeline (extract +
 * anonymize + chunked emission) without persisting anything. Used by the
 * {@code dry-run} subcommand to validate a configuration end-to-end before committing
 * to a real run.
 *
 * <p>{@link #writeChunk(String, List)} records each row as inserted in the
 * {@link com.fungle.brume.report.ExecutionReport} so that the post-execution report is
 * indistinguishable from a successful real run (zero conflicts, zero batch errors) —
 * the user sees exactly what would have been written.
 *
 * <p>Wired by {@code SinkConfig} via {@code @ConditionalOnProperty(brume.sink.type=NULL)}.
 */
public class NullSink implements Sink {

    private static final Logger log = LoggerFactory.getLogger(NullSink.class);

    private WriteContext context;

    @Override
    public void open(WriteContext context) {
        this.context = context;
        log.info("NullSink: opened — dry-run mode, no rows will be persisted (schema={})",
                context.schemaName());
    }

    @Override
    public void writeChunk(String table, List<ExtractedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (context == null) {
            throw new IllegalStateException("NullSink.writeChunk called before open()");
        }
        long count = rows.size();
        context.report().recordInserted(table, count, 0L);
        log.debug("NullSink: would have written {} row(s) to {}.{}",
                count, context.schemaName(), table);
    }

    @Override
    public void close() {
        if (context != null) {
            log.info("NullSink: closed — dry-run finished without touching the target");
        }
        this.context = null;
    }
}
