package com.fungle.brume.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes {@code brume-state.json} after a successful {@code execute} run (ADR-0039).
 *
 * <p>The file captures the effective column strategies (post-FK propagation) so that
 * {@code brume audit} can exclude neutralized columns from the k-anonymity calculation.
 * Failures are non-fatal — the pipeline result is unaffected.
 */
@Component
public class ExecutionStateWriter {

    static final String STATE_FILE = "brume-state.json";
    static final String VERSION = "1.0.0";

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateWriter.class);

    private final ObjectMapper objectMapper;

    public ExecutionStateWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(AnonymizerConfig config, String schema) {
        List<ColumnState> columns = new ArrayList<>();
        for (TableAnonymizationConfig table : config.anonymization().tables()) {
            for (ColumnConfig col : table.columns()) {
                columns.add(new ColumnState(
                        table.table(),
                        col.name(),
                        col.strategy().name(),
                        col.type() != null ? col.type().name() : null
                ));
            }
        }

        ExecutionState state = new ExecutionState(
                Instant.now().toString(),
                schema,
                VERSION,
                List.copyOf(columns)
        );

        Path path = Path.of(STATE_FILE);
        try {
            Files.writeString(path,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                    StandardCharsets.UTF_8);
            log.info("Execution state written to {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to write execution state to '{}': {}", STATE_FILE, e.getMessage());
        }
    }
}
