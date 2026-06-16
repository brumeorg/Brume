package com.fungle.brume.extraction;

import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Streaming orchestration for the hot path extract → anonymize → write.
 *
 * <p>This class is kept as a compatibility façade for the original T09 hot path. The actual
 * chunked streaming implementation now lives in {@link ChunkedTableProcessor} and is delegated to
 * from here.
 */
@Component
public class StreamingTableProcessor {

    private static final Logger log = LoggerFactory.getLogger(StreamingTableProcessor.class);

    private final ChunkedTableProcessor chunkedTableProcessor;

    public StreamingTableProcessor(ChunkedTableProcessor chunkedTableProcessor) {
        this.chunkedTableProcessor = chunkedTableProcessor;
    }

    public void processAll(AnonymizerConfig config, DatabaseSchema schema, ExecutionReport report) {
        log.debug("StreamingTableProcessor delegates to ChunkedTableProcessor");
        chunkedTableProcessor.processAll(config, schema, report);
    }

    void processTable(TableExtractionConfig tableConfig, AnonymizerConfig config,
                      DatabaseSchema schema, ExecutionReport report,
                      Map<String, Set<Object>> processedPrimaryKeys) {
        chunkedTableProcessor.processTable(tableConfig, config, schema, report, processedPrimaryKeys);
    }
}

