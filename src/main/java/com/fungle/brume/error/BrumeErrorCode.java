package com.fungle.brume.error;

/**
 * Catalog of stable, descriptive error codes carried by {@link BrumeException} instances.
 *
 * <p>Each code names the family ({@code CONFIG_*}, {@code CONNECTION_*}, {@code SCHEMA_*},
 * {@code ANON_*}, {@code WRITE_*}) followed by a short symbolic suffix. Codes appear in
 * console output and in operator-facing logs; renaming one is a breaking change for users
 * who grep for it, so prefer adding a new code over editing an existing name.
 *
 * <p>Numeric IDs are intentionally avoided — descriptive names age better and the catalog
 * stays self-documenting.
 *
 * <p>Tracked under #17 (A10) / ADR-0026, extended by #17b / ADR-0027 with the per-site
 * {@code CONFIG_*} codes for the validators in {@code com.fungle.brume.config}.
 */
public enum BrumeErrorCode {

    // ---------------------------------------------------------------------
    // Configuration (exit code 1)
    // ---------------------------------------------------------------------

    /** HMAC algorithm name is unknown or unsupported on this JVM. */
    CONFIG_HMAC_INVALID,

    /** {@code brume.hmac-secret} is unset or blank. */
    CONFIG_HMAC_SECRET_MISSING,

    /** {@code brume.hmac-secret} is shorter than the minimum required UTF-8 byte length. */
    CONFIG_HMAC_SECRET_TOO_SHORT,

    /** {@code brume.fpe-key} is unset or blank. */
    CONFIG_FPE_KEY_MISSING,

    /** {@code brume.fpe-key} length is not a valid AES key size (16, 24, or 32 UTF-8 bytes). */
    CONFIG_FPE_KEY_INVALID_LENGTH,

    /** A numeric guardrail property is outside its accepted range (rate/percent/count). */
    CONFIG_GUARDRAIL_OUT_OF_RANGE,

    /** Planned dataset exceeds {@code brume.max-target-rows}. */
    CONFIG_MAX_TARGET_ROWS_EXCEEDED,

    /** {@code extraction.tables} is null or empty — Brume refuses a whole-DB copy. */
    CONFIG_EXTRACTION_TABLES_EMPTY,

    /** {@code extraction.fk_depth} is zero or negative. */
    CONFIG_EXTRACTION_FK_DEPTH_INVALID,

    /** {@code extraction.fetch_size} is zero or negative. */
    CONFIG_EXTRACTION_FETCH_SIZE_INVALID,

    /** {@code extraction.batch_size} is zero or negative. */
    CONFIG_EXTRACTION_BATCH_SIZE_INVALID,

    /** {@code extraction.chunk_size} is zero or negative. */
    CONFIG_EXTRACTION_CHUNK_SIZE_INVALID,

    /** {@code extraction.tables[].filter} contains an injection marker (cf. ADR-0017). */
    CONFIG_EXTRACTION_FILTER_INVALID,

    /** A SQL identifier (table or column) does not match the PostgreSQL unquoted-identifier rules. */
    CONFIG_INVALID_IDENTIFIER,

    /** A column with strategy {@code FAKE} is missing the required {@code SemanticType}. */
    CONFIG_STRATEGY_REQUIRES_TYPE,

    /** A column with type {@code JSONB} is missing the required {@code json_paths} list. */
    CONFIG_JSONB_REQUIRES_JSON_PATHS,

    /** {@code brume.sink.type=DUMP} but {@code brume.sink.output-path} is unset or blank. */
    CONFIG_SINK_OUTPUT_PATH_MISSING,

    /** {@code brume.sink.compression} disagrees with the {@code brume.sink.output-path} extension (cf. ADR-0022). */
    CONFIG_SINK_COMPRESSION_EXTENSION_MISMATCH,

    /** A configured output-file property ({@code brume.sink.output-path}, {@code brume.report.*-file}) is null or blank. */
    CONFIG_OUTPUT_PATH_BLANK,

    /** An output-file path is malformed — NUL byte, syntactically invalid, or otherwise non-resolvable. */
    CONFIG_OUTPUT_PATH_INVALID,

    /** An output-file path resolves outside the JVM working directory (path-traversal denial, cf. ADR-0020). */
    CONFIG_OUTPUT_PATH_TRAVERSAL,

    /** A JDBC URL property ({@code replication.source.url}, {@code replication.target.url}) is null or blank. */
    CONFIG_JDBC_URL_BLANK,

    /** A JDBC URL is malformed — wrong prefix, parse error, missing database name (cf. ADR-0021). */
    CONFIG_JDBC_URL_INVALID,

    /** A JDBC URL contains parameters outside the curated pgJDBC allowlist (cf. ADR-0021 — anti-{@code socketFactory} etc.). */
    CONFIG_JDBC_URL_REJECTED_PARAM,

    /** A referenced primary key uses {@code NULLIFY} or {@code MASK} — would break FK integrity (cf. ADR-0023). */
    CONFIG_FK_PROPAGATION_INTEGRITY_BREAKING,

    /** A referenced primary key uses {@code FAKE} but the FK is not covered by a {@code linked_columns} entry (cf. ADR-0023). */
    CONFIG_FK_PROPAGATION_FAKE_REQUIRES_LINK,

    /** A foreign key is declared with a strategy that conflicts with its parent PK's strategy (cf. ADR-0023). */
    CONFIG_FK_PROPAGATION_STRATEGY_CONFLICT,

    /** Schema-aware validation: a table referenced in the config does not exist in the source schema. */
    CONFIG_SCHEMA_UNKNOWN_TABLE,

    /** Schema-aware validation: a column referenced in the config does not exist in the source schema. */
    CONFIG_SCHEMA_UNKNOWN_COLUMN,

    /** Schema-aware validation: type/strategy combination is incompatible and {@code --strict-config} is on. */
    CONFIG_SCHEMA_TYPE_STRATEGY_INCOMPATIBLE,

    /** Preflight (#19/A13): {@code pg_dump} executable could not be invoked (not on PATH, not executable, version subprocess failed). */
    PREFLIGHT_PG_DUMP_NOT_FOUND,

    /** Preflight (#19/A13): {@code pg_dump} major version is older than the source PostgreSQL major version. */
    PREFLIGHT_PG_DUMP_VERSION_MISMATCH,

    /** Preflight (#19/A13): source role lacks {@code USAGE} on the configured schema. */
    PREFLIGHT_NO_USAGE_ON_SOURCE,

    /** Preflight (#19/A13): target role lacks {@code CREATE} on the current database. */
    PREFLIGHT_NO_CREATE_ON_TARGET,

    /** Preflight (#19/A13): target role is not a member of the existing target schema's owner; {@code DROP SCHEMA CASCADE} would fail at runtime. */
    PREFLIGHT_NO_OWNERSHIP_ON_TARGET_SCHEMA,

    /** {@code brume.config-path} points at a file that does not exist. */
    CONFIG_FILE_NOT_FOUND,

    /** The YAML config file could not be parsed (malformed YAML, deserialization failure). */
    CONFIG_FILE_PARSE_ERROR,

    /** {@code replication.source} is null. */
    CONFIG_REPLICATION_SOURCE_MISSING,

    /** {@code replication.target} is null. */
    CONFIG_REPLICATION_TARGET_MISSING,

    // ---------------------------------------------------------------------
    // Connection (exit code 2)
    // ---------------------------------------------------------------------

    /** Source database is unreachable or rejected the connection (Hikari init failed). */
    CONNECTION_SOURCE_UNREACHABLE,

    /** Target database is unreachable or rejected the connection. */
    CONNECTION_TARGET_UNREACHABLE,

    // ---------------------------------------------------------------------
    // Schema (exit code 4)
    // ---------------------------------------------------------------------

    /** {@code pg_dump} subprocess exited with a non-zero status. */
    SCHEMA_PGDUMP_FAILED,

    /** {@code pg_dump} subprocess did not finish within {@code replication.pgdump-timeout-seconds}. */
    SCHEMA_PGDUMP_TIMEOUT,

    /** {@code pg_dump} subprocess output could not be read (pipe-level failure). */
    SCHEMA_PGDUMP_IO,

    /** Preflight (#19/A13): source schema is not visible to the current role (not in {@code information_schema.schemata}). */
    PREFLIGHT_SOURCE_SCHEMA_NOT_FOUND,

    // ---------------------------------------------------------------------
    // Anonymization (exit code 6)
    // ---------------------------------------------------------------------

    /** {@code FPE_ID} received a value outside the supported numeric range. */
    ANON_FPE_ID_OUT_OF_RANGE,

    // ---------------------------------------------------------------------
    // Write (exit code 5)
    // ---------------------------------------------------------------------

    /** Substitution dictionary has reached its configured size limit. */
    WRITE_DICT_OVERFLOW,

    /** A table's batch error rate has crossed {@code brume.max-batch-error-rate}. */
    WRITE_BATCH_THRESHOLD,

    /** {@code SqlFileSink} could not open, write to, or close its output file. */
    WRITE_DUMP_IO,

    // ---------------------------------------------------------------------
    // Timeouts (exit code 7) — #23/A21 / ADR-0033
    // ---------------------------------------------------------------------

    /** A bounded source query exceeded {@code brume.timeouts.statement-seconds}. */
    RUN_TIMEOUT_STATEMENT,

    /** The full pipeline run exceeded {@code brume.timeouts.total-run-seconds}. */
    RUN_TIMEOUT_TOTAL,

    // ---------------------------------------------------------------------
    // Audit (#73 / ADR-0036) — exit code 1 for spec errors, 8 for policy violation
    // (8 is returned directly by AuditRunner — see ADR-0036 §3, not via this enum)
    // ---------------------------------------------------------------------

    /** {@code brume audit} invoked without {@code --quasi-id} nor {@code --auto-detect-quasi-id}. */
    AUDIT_QUASI_ID_REQUIRED,

    /** {@code brume audit} requires a configured target database, but none is wired (cf. ADR-0028). */
    AUDIT_TARGET_NOT_CONFIGURED,

    /** {@code --quasi-id} references a table that does not exist in the target schema. */
    AUDIT_UNKNOWN_TABLE,

    /** {@code --quasi-id} references a column that does not exist in the named table. */
    AUDIT_UNKNOWN_COLUMN,

    // ---------------------------------------------------------------------
    // Checkpoint / resume (#25 / ADR-0037) — exit code 1 (Configuration family)
    // ---------------------------------------------------------------------

    /** {@code --resume} present without a path argument, or path is blank. */
    CHECKPOINT_PATH_MISSING,

    /** The checkpoint file at the configured path is malformed JSON or unreadable. */
    CHECKPOINT_FILE_INVALID,

    /** The checkpoint file's {@code configHash} does not match the current {@code config.yaml}. */
    CHECKPOINT_CONFIG_DRIFT
}
