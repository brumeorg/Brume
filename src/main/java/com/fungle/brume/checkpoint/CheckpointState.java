package com.fungle.brume.checkpoint;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of a Brume run progression — persisted between table-completions
 * to enable resume after crash / OOM / SIGTERM / network drop (#25 / A19).
 *
 * <p>Serialised as JSON to {@code brume-checkpoint.json} (path configurable). The
 * file is rewritten atomically (tmp + fsync + ATOMIC_MOVE) on each table completion.
 *
 * <p>Granularity V1 = per-table : {@link #completedTables()} lists the tables fully
 * written to the target. A SIGTERM mid-table loses the in-flight table — the resume
 * re-plays it entirely, which is idempotent thanks to #29 (deterministic
 * anonymization + UPSERT semantics in {@code JdbcSink}).
 *
 * <p>The {@link #configHash()} is computed once at boot from the source
 * {@code config.yaml} (file bytes with CR/LF normalised, SHA-256) ; a mismatch on
 * resume refuses the run with a clear message — protects against silent
 * inconsistency when the config has drifted between runs.
 *
 * <p>The {@code SubstitutionDictionary} is NOT persisted : HMAC-deterministic
 * anonymization (validated by spike 2026-05-12) makes the dict redundant for
 * cross-run consistency. The dict is rebuilt from scratch on resume — same inputs
 * produce same outputs by construction.
 *
 * @param schemaVersion   format version of this checkpoint record — bumped on
 *                        breaking changes to the schema (start at {@code "1.0"})
 * @param runId           UUID generated on first checkpoint write of a run ; carried
 *                        through resumes for traceability
 * @param startedAt       wall-clock timestamp of the first {@code execute} that
 *                        created this checkpoint ; not updated on resume
 * @param updatedAt       wall-clock timestamp of the most recent write
 * @param sourceSchema    PostgreSQL schema being replicated (sanity match on resume)
 * @param configHash      {@code sha256(file_bytes)} of {@code config.yaml} with
 *                        line endings normalised to LF
 * @param completedTables ordered list of tables already fully written to the target,
 *                        in completion order (carries the alpha order set by
 *                        {@code SchemaAnalyzer} since #25c)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointState(
        String schemaVersion,
        String runId,
        Instant startedAt,
        Instant updatedAt,
        String sourceSchema,
        String configHash,
        List<String> completedTables) {

    /** Current version of the checkpoint file format. */
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public CheckpointState {
        completedTables = (completedTables == null) ? List.of() : List.copyOf(completedTables);
    }

    /**
     * Returns a new state with {@code table} appended to {@link #completedTables}
     * (deduplicated, completion-order preserved) and {@code updatedAt} bumped to
     * {@code now}.
     */
    public CheckpointState withTableCompleted(String table, Instant now) {
        Set<String> set = new LinkedHashSet<>(completedTables);
        set.add(table);
        return new CheckpointState(
                schemaVersion, runId, startedAt, now, sourceSchema, configHash,
                List.copyOf(set));
    }

    /** Returns {@code true} if {@code table} is in {@link #completedTables}. */
    public boolean isCompleted(String table) {
        return completedTables.contains(table);
    }

    /** Builder for the very first checkpoint of a run. */
    public static CheckpointState initial(String runId, String sourceSchema,
                                          String configHash, Instant now) {
        return new CheckpointState(CURRENT_SCHEMA_VERSION, runId, now, now,
                sourceSchema, configHash, List.of());
    }
}
