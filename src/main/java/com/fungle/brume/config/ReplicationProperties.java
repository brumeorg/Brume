package com.fungle.brume.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration properties for the {@code replication.*} YAML prefix.
 *
 * <p>Holds the target schema name, the path to {@code pg_dump}, the connection
 * details for both the source and target databases, and the HikariCP pool size
 * applied to both custom DataSource beans.
 *
 * <p>Registered via {@code @EnableConfigurationProperties} in
 * {@link com.fungle.brume.BrumeApplication}.
 *
 * @param schema                 name of the PostgreSQL schema to replicate (e.g. {@code test_brume})
 * @param pgdumpPath             path to the {@code pg_dump} executable (default: {@code pg_dump})
 * @param pgdumpTimeoutSeconds   maximum time in seconds to wait for {@code pg_dump} to complete
 *                               (default: {@code 300}). If exceeded, the subprocess is killed and
 *                               the operation fails.
 * @param ddlErrorMode           behavior when a DDL statement fails (default: {@code STRICT}).
 *                               STRICT: fail-fast on first DDL error. LENIENT: log and continue.
 * @param poolSize               maximum HikariCP connection pool size for both source and target
 *                               DataSources (default: {@code 20})
 * @param fkDepth                maximum FK parent resolution depth for {@link com.fungle.brume.plan.PlanEstimator}
 *                               (default: {@code 3})
 * @param source                 source database connection parameters
 * @param target                 target database connection parameters
 * @param ddlCache               when {@code true}, cache the {@code pg_dump --schema-only} output
 *                               under {@code .brume/ddl-cache/} keyed by a fingerprint of the source
 *                               schema (information_schema.columns + table_constraints). Subsequent
 *                               runs with an unchanged schema skip {@code pg_dump} entirely.
 *                               Default {@code false} — opt-in for dev workflows; leave OFF in CI
 *                               and production. (B14)
 */
@ConfigurationProperties(prefix = "replication")
public record ReplicationProperties(
        String schema,
        @DefaultValue("pg_dump") String pgdumpPath,
        @DefaultValue("300") int pgdumpTimeoutSeconds,
        @DefaultValue("STRICT") DdlErrorMode ddlErrorMode,
        @DefaultValue("20") int poolSize,
        @DefaultValue("3") int fkDepth,
        Source source,
        Target target,
        @DefaultValue("false") boolean ddlCache
) {
    @ConstructorBinding
    public ReplicationProperties {
    }

    /** Compatibility constructor — pre-#13 callers without the {@code ddlCache} field. */
    public ReplicationProperties(
            String schema,
            String pgdumpPath,
            int pgdumpTimeoutSeconds,
            DdlErrorMode ddlErrorMode,
            int poolSize,
            int fkDepth,
            Source source,
            Target target) {
        this(schema, pgdumpPath, pgdumpTimeoutSeconds, ddlErrorMode, poolSize, fkDepth, source, target, false);
    }
    /**
     * Behavior when a DDL statement fails during schema replication.
     */
    public enum DdlErrorMode {
        /**
         * Fail-fast: the first DDL error stops the pipeline with an exception.
         * This is the default and recommended mode for production.
         */
        STRICT,

        /**
         * Lenient: DDL errors are logged as warnings and the pipeline continues.
         * Use this only when you expect certain DDL statements to fail intentionally
         * (e.g., CREATE EXTENSION without privileges).
         */
        LENIENT
    }

    /**
     * Connection parameters for a PostgreSQL data source.
     *
     * <p>{@link #toString()} masks every field — URLs can carry inline {@code user:password@host}
     * authority, and the username/password are obviously sensitive on their own. Tracked
     * under #15 / ADR-0025.
     *
     * @param url      JDBC URL (e.g. {@code jdbc:postgresql://localhost:5432/postgres})
     * @param username database username
     * @param password database password
     */
    public record Source(String url, String username, String password) {
        @Override
        public String toString() {
            return "Source[url=***, username=***, password=***]";
        }
    }

    /**
     * Connection parameters for the replication target database.
     *
     * <p>{@link #toString()} masks every field — see {@link Source} for the rationale.
     * Tracked under #15 / ADR-0025.
     *
     * @param url      JDBC URL
     * @param username database username
     * @param password database password
     */
    public record Target(String url, String username, String password) {
        @Override
        public String toString() {
            return "Target[url=***, username=***, password=***]";
        }
    }
}
