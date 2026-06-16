package com.fungle.brume.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator for crash-resume via checkpoint file (#25 / A19, ADR-0037).
 *
 * <p>Lifecycle :
 * <ol>
 *   <li>{@link #boot(DatabaseSchema)} — called by {@code ReplicationAgent} after
 *       schema analysis. Reads any existing checkpoint, validates {@code
 *       configHash} against the current {@code config.yaml}, and prepares an
 *       in-memory state for the run.</li>
 *   <li>{@link #shouldSkip(String)} — consulted by {@code ChunkedTableProcessor}
 *       at the start of each table : returns {@code true} when the table is in
 *       {@code completedTables}.</li>
 *   <li>{@link #markCompleted(String)} — called after each fully-written table.
 *       Mutates the in-memory state and writes the file atomically.</li>
 * </ol>
 *
 * <p>When {@code brume.checkpoint.enabled=false} (default V1), this service is a
 * no-op : {@link #shouldSkip} returns {@code false} for every table and
 * {@link #markCompleted} does nothing.
 *
 * <p>Granularity V1 = per-table only. A SIGTERM mid-table loses the in-flight
 * table — resume re-plays it entirely (idempotent via #29). Per-chunk granularity
 * is tracked in {@code #25e} post-V1.
 *
 * <p>The shutdown hook (ADR-0032 SIGTERM) does <strong>not</strong> write the
 * checkpoint — racing with closing log appenders and Hikari pools would lead to
 * inconsistent state. The per-table write in {@link #markCompleted} is the only
 * commit point.
 */
@Component
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final BrumeProperties.CheckpointProperties config;
    private final String configPath;
    private final String sourceSchema;
    private final ObjectMapper objectMapper;

    private CheckpointStore store;       // null when disabled
    private CheckpointState state;       // null when disabled
    private boolean booted;

    public CheckpointService(BrumeProperties brumeProperties,
                             ReplicationProperties replicationProperties,
                             ObjectMapper objectMapper) {
        this.config = brumeProperties.checkpoint();
        this.configPath = brumeProperties.configPath();
        this.sourceSchema = replicationProperties.schema();
        this.objectMapper = objectMapper;
    }

    /**
     * Reads any existing checkpoint and validates it against the current config.
     * Re-callable across multiple {@code ReplicationAgent.run()} invocations
     * sharing the same Spring context (typical in test scenarios with
     * {@code @DirtiesContext} not on every method) : the second call resets the
     * in-memory state and re-reads the checkpoint file from disk — which mirrors
     * production behavior where a fresh JVM starts cold every time.
     *
     * @param schema the current source schema, used to WARN on
     *               {@code completedTables} that reference tables no longer in scope
     */
    public void boot(DatabaseSchema schema) {
        if (booted) {
            log.debug("CheckpointService.boot re-invoked — resetting state for the new run");
            this.state = null;
            this.store = null;
        }
        booted = true;

        if (!config.enabled()) {
            log.debug("Checkpoint disabled (brume.checkpoint.enabled=false) — no-op service");
            return;
        }

        Path path = resolveAndValidatePath(config.path());
        this.store = new CheckpointStore(path, objectMapper);

        Optional<CheckpointState> existing = readSafely(path);
        String currentHash = ConfigHash.of(Path.of(configPath));

        if (existing.isPresent()) {
            CheckpointState prior = existing.get();
            if (!currentHash.equals(prior.configHash())) {
                throw new ConfigurationException(
                        BrumeErrorCode.CHECKPOINT_CONFIG_DRIFT,
                        "Checkpoint at " + path + " was created with a different config.yaml "
                                + "(hash drift). The resume cannot proceed safely.",
                        "Re-run without --resume to start fresh, or restore the original "
                                + "config.yaml that produced this checkpoint.");
            }
            warnOnTablesDriftedOutOfSchema(prior, schema);
            this.state = prior;
            log.info("Checkpoint loaded from {} : {} table(s) already completed — skipping them",
                    path, prior.completedTables().size());
        } else {
            this.state = CheckpointState.initial(
                    UUID.randomUUID().toString(),
                    sourceSchema,
                    currentHash,
                    Instant.now());
            log.info("Checkpoint enabled — fresh run, writing to {}", path);
        }
    }

    /**
     * Returns {@code true} when {@code table} is already in
     * {@link CheckpointState#completedTables()} — the caller should skip it.
     * Returns {@code false} when checkpoint is disabled or the table is new.
     */
    public boolean shouldSkip(String table) {
        if (!config.enabled() || state == null) {
            return false;
        }
        return state.isCompleted(table);
    }

    /**
     * Records {@code table} as fully written and persists the new state atomically.
     * No-op when checkpoint is disabled.
     */
    public void markCompleted(String table) {
        if (!config.enabled() || state == null || store == null) {
            return;
        }
        if (state.isCompleted(table)) {
            return;  // idempotent — defends against re-entry
        }
        state = state.withTableCompleted(table, Instant.now());
        store.write(state);
        log.debug("Checkpoint: marked '{}' as completed ({} total) — written to {}",
                table, state.completedTables().size(), store.path());
    }

    /** Read-only access for diagnostics (e.g. ReportRenderer footer). May be null. */
    public CheckpointState currentState() {
        return state;
    }

    /** Returns the path of the checkpoint file when enabled, otherwise {@code null}. */
    public Path checkpointPath() {
        return store == null ? null : store.path();
    }

    // ---------------------------------------------------------------------

    /**
     * Resolves {@code path} to an absolute {@link Path} and validates that it is
     * non-blank. Unlike {@code brume.sink.output-path} (ADR-0020), the checkpoint
     * is <strong>intentionally allowed outside the JVM cwd</strong> — production
     * usage typically points at a persistent volume mount such as
     * {@code /var/lib/brume/} or {@code ~/.brume/} that lives outside the JVM
     * working directory. The threat model differs : sink output is an
     * <em>artifact</em> potentially weaponized through config injection ; the
     * checkpoint is a <em>state file</em> whose path is part of the operator's
     * environment configuration.
     */
    private static Path resolveAndValidatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CHECKPOINT_PATH_MISSING,
                    "brume.checkpoint.path is null or blank.",
                    "Set brume.checkpoint.path in application.yaml or pass --resume <path>.");
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    private Optional<CheckpointState> readSafely(Path path) {
        try {
            return store.read();
        } catch (CheckpointStore.CheckpointIoException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.CHECKPOINT_FILE_INVALID,
                    "Checkpoint file at " + path + " is invalid: " + e.getMessage(),
                    "Re-run without --resume to start fresh ; delete the checkpoint "
                            + "file if it was corrupted by an external process.",
                    e);
        }
    }

    /**
     * WARNs (does not fail) when the checkpoint lists tables that are no longer in
     * the current schema. The user may have removed a table from the extraction
     * config legitimately — fail-loud would block normal evolution.
     */
    private void warnOnTablesDriftedOutOfSchema(CheckpointState prior, DatabaseSchema schema) {
        Set<String> known = new HashSet<>(schema.tableNames());
        for (String t : prior.completedTables()) {
            if (!known.contains(t)) {
                log.warn("Checkpoint references table '{}' that is no longer in schema '{}' — "
                        + "it will be skipped (table likely removed from extraction config).",
                        t, sourceSchema);
            }
        }
    }
}
