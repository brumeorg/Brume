package com.fungle.brume.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code brume-state.json} for use by {@code brume audit} (ADR-0039).
 *
 * <p>Returns {@link Optional#empty()} when the file is absent (first run, or
 * user opted out) or unreadable — the audit falls back to the current heuristic
 * behavior and warns the operator.
 */
@Component
public class ExecutionStateReader {

    /**
     * Strategies whose presence on a column means the column is safe to exclude from
     * k-anonymity audit: they either replace the value with something uncorrelated
     * (FAKE seeded per-value, NULLIFY, MASK) or encrypt it format-preservingly but
     * in a way that breaks inter-individual grouping (FPE_ID, FPE_UUID).
     *
     * <p>HASH and KEEP are intentionally absent: HASH is deterministic (same source
     * value → same hash, preserving correlation) and KEEP copies the real value.
     */
    public static final Set<String> NEUTRALIZED_STRATEGIES =
            Set.of("FAKE", "NULLIFY", "MASK", "FPE_ID", "FPE_UUID");

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateReader.class);

    private final ObjectMapper objectMapper;

    public ExecutionStateReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ExecutionState> read() {
        Path path = Path.of(ExecutionStateWriter.STATE_FILE);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            ExecutionState state = objectMapper.readValue(path.toFile(), ExecutionState.class);
            log.info("Execution state loaded from {} (schema={}, generatedAt={})",
                    path.toAbsolutePath(), state.schema(), state.generatedAt());
            return Optional.of(state);
        } catch (IOException e) {
            log.warn("Failed to read execution state from '{}': {} — falling back to heuristic audit",
                    ExecutionStateWriter.STATE_FILE, e.getMessage());
            return Optional.empty();
        }
    }
}
