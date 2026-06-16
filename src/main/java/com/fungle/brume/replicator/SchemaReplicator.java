package com.fungle.brume.replicator;

import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.report.DdlExecutionResult;
import com.fungle.brume.report.DdlFailure;
import com.fungle.brume.shutdown.CancellationRegistry;
import com.fungle.brume.util.JdbcUrlValidator;
import com.fungle.brume.util.JdbcUrlValidator.ParsedJdbcUrl;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Replicates a PostgreSQL schema from the source database to the target database.
 *
 * <p>The process has three steps:
 * <ol>
 *   <li>DROP the target schema (CASCADE) to remove any stale objects.</li>
 *   <li>Run {@code pg_dump --schema-only} on the source to obtain DDL.</li>
 *   <li>Split the DDL into individual statements and execute them on the target.</li>
 * </ol>
 *
 * <p>The path to {@code pg_dump} is configured via {@code replication.pgdump-path}
 * (defaults to {@code pg_dump} on the system PATH).
 */
@Component
public class SchemaReplicator {

    private static final Logger log = LoggerFactory.getLogger(SchemaReplicator.class);

    /** Cache root directory; created lazily when {@code ddlCache} is enabled. */
    private static final Path DDL_CACHE_DIR = Path.of(".brume", "ddl-cache");

    private final String pgDumpPath;
    private final int pgdumpTimeoutSeconds;
    private final ReplicationProperties.DdlErrorMode ddlErrorMode;
    private final ReplicationProperties.Source source;
    private final boolean ddlCacheEnabled;
    private final CancellationRegistry cancellationRegistry;

    /** Test affordance: status of the most recent {@link #dumpSchema(String)} call. */
    private boolean lastDumpWasCached;

    /**
     * @param replicationProperties replication configuration, including the pg_dump path and timeout
     * @param cancellationRegistry  used to register the pg_dump subprocess so SIGTERM can
     *                              destroy it (#24/A22). Without this, {@code Process.waitFor}
     *                              keeps the pipeline blocked on a subprocess that ignores
     *                              the volatile cancel flag.
     */
    public SchemaReplicator(ReplicationProperties replicationProperties,
                            CancellationRegistry cancellationRegistry) {
        this.pgDumpPath = replicationProperties.pgdumpPath();
        this.pgdumpTimeoutSeconds = replicationProperties.pgdumpTimeoutSeconds();
        this.ddlErrorMode = replicationProperties.ddlErrorMode();
        this.source = replicationProperties.source();
        this.ddlCacheEnabled = replicationProperties.ddlCache();
        this.cancellationRegistry = cancellationRegistry;
        if (this.ddlCacheEnabled) {
            log.info("SchemaReplicator: DDL cache enabled (cache dir: {})", DDL_CACHE_DIR);
        }
    }

    /**
     * Test affordance: returns {@code true} if the most recent {@link #dumpSchema(String)} call
     * was served from the local DDL cache (B14). Returns {@code false} when caching is disabled,
     * the cache file did not exist, or the cache file was unreadable (in which case
     * {@code pg_dump} was re-run as a fallback).
     */
    public boolean lastDumpWasCached() {
        return lastDumpWasCached;
    }

    public DdlExecutionResult replicate(
            String sourceUrl, String sourceUser, String sourcePassword,
            String targetSchema,
            DataSource targetDataSource) throws SQLException, IOException, InterruptedException {

        log.info("Démarrage réplication schéma '{}'", targetSchema);

        // 1. DROP + CREATE schema sur la target
        dropAndRecreateSchema(targetDataSource, targetSchema);

        // 2. pg_dump --schema-only sur la source (via cache si activé, B14)
        String ddl = dumpSchemaCached(sourceUrl, sourceUser, sourcePassword, targetSchema);

        // 3. Découpage en statements et exécution sur la target
        DdlExecutionResult result = executeDdl(targetDataSource, ddl);

        log.info("Réplication schéma '{}' terminée", targetSchema);
        return result;
    }

    // -------------------------------------------------------------------------

    private void dropAndRecreateSchema(DataSource ds, String schema) throws SQLException {
        log.info("DROP SCHEMA CASCADE '{}'", schema);
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + SqlIdentifiers.quote(schema) + " CASCADE");
            // DROP les autres schémas potentiellement créés par pg_dump (ex: forms)
            // On les recréera depuis le DDL
        }
        log.info("Schema '{}' dropped", schema);
    }

    /**
     * Runs {@code pg_dump --schema-only} on the configured source and returns the
     * captured DDL as a single {@code String}. Does not touch any target database.
     *
     * <p>Used by the dump-mode sink ({@code SqlFileSink}) to obtain DDL for inclusion
     * in the dump file without running it against a target.
     *
     * <p>When {@code brume.replication.ddl-cache=true}, the result is cached locally at
     * {@code .brume/ddl-cache/<sha256(sourceUrl|schema|fingerprint)>.sql}. The
     * fingerprint is recomputed on every call (cheap SQL on
     * {@code information_schema}); if it matches a cached file, {@code pg_dump} is
     * skipped entirely (B14).
     *
     * @param schema the source schema name to dump
     * @return the full DDL output of {@code pg_dump --schema-only}
     * @throws IOException if the {@code pg_dump} subprocess cannot be started or its output cannot be read
     * @throws InterruptedException if the current thread is interrupted while waiting for {@code pg_dump}
     */
    public String dumpSchema(String schema) throws IOException, InterruptedException {
        return dumpSchemaCached(source.url(), source.username(), source.password(), schema);
    }

    /**
     * Cache-aware wrapper around {@link #dumpSchema(String, String, String, String)}.
     * When {@code ddlCacheEnabled} is false, simply delegates and clears
     * {@link #lastDumpWasCached}. When true, computes a fingerprint of the source schema,
     * looks up a cache file under {@code .brume/ddl-cache/}, returns the cached DDL on
     * hit, or runs {@code pg_dump} and populates the cache on miss.
     */
    private String dumpSchemaCached(String sourceUrl, String user, String password, String schema)
            throws IOException, InterruptedException {
        if (!ddlCacheEnabled) {
            lastDumpWasCached = false;
            return dumpSchema(sourceUrl, user, password, schema);
        }

        String fingerprint;
        try {
            fingerprint = computeSchemaFingerprint(sourceUrl, user, password, schema);
        } catch (SQLException e) {
            log.warn("SchemaReplicator: failed to compute schema fingerprint for '{}.{}', falling back to pg_dump (no cache): {}",
                    sourceUrl, schema, e.getMessage());
            lastDumpWasCached = false;
            return dumpSchema(sourceUrl, user, password, schema);
        }

        Path cacheFile = cacheFileFor(sourceUrl, schema, fingerprint);
        if (Files.isRegularFile(cacheFile)) {
            try {
                String cached = Files.readString(cacheFile, StandardCharsets.UTF_8);
                lastDumpWasCached = true;
                log.info("SchemaReplicator: DDL cache HIT for schema '{}' (file: {}, {} chars)",
                        schema, cacheFile.getFileName(), cached.length());
                return cached;
            } catch (IOException e) {
                log.warn("SchemaReplicator: DDL cache file unreadable ({}), falling back to pg_dump: {}",
                        cacheFile, e.getMessage());
            }
        }

        log.info("SchemaReplicator: DDL cache MISS for schema '{}' — running pg_dump", schema);
        String ddl = dumpSchema(sourceUrl, user, password, schema);
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, ddl, StandardCharsets.UTF_8);
            log.info("SchemaReplicator: DDL cache populated at {}", cacheFile);
        } catch (IOException e) {
            log.warn("SchemaReplicator: failed to write DDL cache file {} (run will succeed but cache won't help next time): {}",
                    cacheFile, e.getMessage());
        }
        lastDumpWasCached = false;
        return ddl;
    }

    /**
     * Computes a structural fingerprint of the source schema by hashing the column list
     * (information_schema.columns) and the foreign-key list (information_schema.table_constraints).
     * Captures the changes that matter for DDL: tables, columns, types, nullability, FKs.
     * Misses CHECK constraints, indexes and triggers — acceptable for a dev cache.
     */
    private String computeSchemaFingerprint(String sourceUrl, String user, String password, String schema)
            throws SQLException {
        String columnsHash;
        String fkHash;
        try (Connection conn = DriverManager.getConnection(sourceUrl, user, password)) {
            columnsHash = querySingleString(conn,
                    "SELECT COALESCE(md5(string_agg("
                            + "table_name||'|'||column_name||'|'||data_type||'|'||is_nullable, "
                            + "',' ORDER BY table_name, ordinal_position)), '')"
                            + " FROM information_schema.columns WHERE table_schema = ?",
                    schema);
            fkHash = querySingleString(conn,
                    "SELECT COALESCE(md5(string_agg("
                            + "constraint_name||'|'||table_name, ',' ORDER BY constraint_name)), '')"
                            + " FROM information_schema.table_constraints"
                            + " WHERE table_schema = ? AND constraint_type = 'FOREIGN KEY'",
                    schema);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((columnsHash + ":" + fkHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private String querySingleString(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    return s == null ? "" : s;
                }
                return "";
            }
        }
    }

    private static Path cacheFileFor(String sourceUrl, String schema, String fingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((sourceUrl + "|" + schema + "|" + fingerprint).getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(md.digest());
            return DDL_CACHE_DIR.resolve(hash + ".sql");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private String dumpSchema(
            String sourceUrl, String user, String password, String schema) throws IOException, InterruptedException {

        // Validate schema name before passing to pg_dump
        SqlIdentifiers.validate(schema);

        // Defense-in-depth — ReplicationPropertiesValidator already ran the same
        // check at @PostConstruct ; calling it here gives us the typed ParsedJdbcUrl
        // and guarantees pg_dump receives a bare DB name (no '?' suffix). Audit § A5.
        ParsedJdbcUrl parsed = JdbcUrlValidator.validate(sourceUrl, "replication.source.url");

        // pgDumpPath may contain multiple tokens (e.g. "docker exec brume-source pg_dump")
        // so we split it before appending the pg_dump arguments.
        List<String> cmd = new ArrayList<>();
        for (String token : pgDumpPath.split("\\s+")) {
            if (!token.isBlank()) cmd.add(token);
        }
        cmd.addAll(List.of(
                "--schema-only",
                "--no-owner",
                "--no-privileges",
                "-n", schema,
                "-h", parsed.host(),
                "-p", String.valueOf(parsed.port()),
                "-U", user,
                parsed.database()
        ));

        log.info("Lancement pg_dump : {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", password);
        // Propagate the JDBC URL's sslmode to pg_dump via libpq env (ADR-0021 Q4) so
        // pg_dump doesn't connect in the clear while Hikari negotiates SSL.
        if (parsed.sslMode() != null && !parsed.sslMode().isBlank()) {
            pb.environment().put("PGSSLMODE", parsed.sslMode());
        }
        // Merge stderr into stdout to avoid pipe deadlock
        // (if we read stdout first and stderr fills up, pg_dump can hang)
        pb.redirectErrorStream(true);

        Process process = pb.start();
        cancellationRegistry.register(process); // #24/A22 — SIGTERM destroys this subprocess
        String output;
        int exitCode;
        try {
            output = readOutputWithTimeout(process, pgdumpTimeoutSeconds);
            exitCode = process.exitValue();
        } finally {
            cancellationRegistry.deregister(process);
        }
        if (exitCode != 0) {
            throw new com.fungle.brume.error.SchemaException(
                    com.fungle.brume.error.BrumeErrorCode.SCHEMA_PGDUMP_FAILED,
                    "pg_dump failed (exit code " + exitCode + "). Output: " + output,
                    "Verify pg_dump is in the replication.pgdump-path, the source DB is reachable, "
                            + "and the user has SELECT privileges on the schema.");
        }

        // Any warnings/errors would have been mixed in the output; log them if non-empty
        if (output.contains("WARNING") || output.contains("ERROR")) {
            log.warn("pg_dump produced warnings/errors in output (but exit code was 0)");
        }

        log.info("pg_dump terminé, {} caractères de DDL récupérés", output.length());
        return output;
    }

    /**
     * Reads the merged stdout/stderr of {@code process} on a background thread, then waits for
     * the process to exit within {@code timeoutSeconds}. If the timeout fires, the subprocess
     * is destroyed forcibly and a {@link RuntimeException} is thrown with an actionable message.
     *
     * <p>Bug fix #23 / audit § B1 / ticket T06 — the previous implementation read the input
     * stream synchronously on the calling thread before {@code waitFor(timeout)} was reached.
     * If the subprocess hung without writing output (network freeze, auth pending), the reader
     * blocked indefinitely and the timeout never fired. Reading on a background thread lets
     * {@code waitFor(timeout)} run concurrently and fire even when the stream produces nothing.
     *
     * <p>Package-private to allow direct unit testing with a real-but-controlled subprocess
     * (cf. {@code SchemaReplicatorTimeoutTest}).
     *
     * @param process        the process to monitor — its input stream MUST be merged via
     *                       {@code redirectErrorStream(true)} so a single reader is sufficient
     * @param timeoutSeconds maximum wall time to wait for {@code process} to exit
     * @return the full merged output as a String (including a trailing newline per line)
     * @throws RuntimeException if the timeout expires (subprocess is killed) or the output
     *                          cannot be read after a successful exit
     */
    static String readOutputWithTimeout(Process process, int timeoutSeconds) throws InterruptedException {
        ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pg_dump-reader");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<String> outputFuture = io.submit(() -> readAll(process.getInputStream()));

            boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new com.fungle.brume.error.SchemaException(
                        com.fungle.brume.error.BrumeErrorCode.SCHEMA_PGDUMP_TIMEOUT,
                        "pg_dump timed out after " + timeoutSeconds + " seconds.",
                        "Increase replication.pgdump-timeout-seconds if the source DB is slow "
                                + "or the schema is large.");
            }

            // Process exited — give the reader a brief grace window to finish flushing the stream.
            try {
                return outputFuture.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new com.fungle.brume.error.SchemaException(
                        com.fungle.brume.error.BrumeErrorCode.SCHEMA_PGDUMP_IO,
                        "pg_dump exited but its output reader did not finish within 5s after exit — "
                                + "likely a JVM-level pipe issue.",
                        "Rerun; if the issue persists, file a bug with the platform details.", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new com.fungle.brume.error.SchemaException(
                        com.fungle.brume.error.BrumeErrorCode.SCHEMA_PGDUMP_IO,
                        "Failed to read pg_dump output: " + cause.getMessage(),
                        "Check disk and filesystem state on the host running Brume.", cause);
            }
        } finally {
            io.shutdownNow();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    // Package-private for direct unit testing — cf. SchemaReplicatorDdlExecutionTest (#28/A17).
    DdlExecutionResult executeDdl(DataSource ds, String ddl) throws SQLException {
        List<String> statements = splitStatements(ddl);
        log.info("{} DDL statements to execute (mode: {})", statements.size(), ddlErrorMode);

        int ok = 0;
        List<DdlFailure> failures = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(true); // each statement is its own transaction
            try (Statement stmt = conn.createStatement()) {
                for (int i = 0; i < statements.size(); i++) {
                    String sql = statements.get(i);
                    try {
                        stmt.execute(sql);
                        ok++;
                    } catch (SQLException e) {
                        if (ddlErrorMode == ReplicationProperties.DdlErrorMode.STRICT) {
                            throw new SQLException(formatDdlError(i + 1, sql, e), e);
                        }
                        String preview = sqlPreview(sql);
                        log.warn("DDL statement {} ignored (LENIENT mode): {} -> {}",
                                i + 1, preview, e.getMessage());
                        failures.add(new DdlFailure(i + 1, preview, e.getMessage()));
                    }
                }
            }
        }

        log.info("DDL executed: {} OK, {} ignored", ok, failures.size());
        return new DdlExecutionResult(ok, failures.size(), failures);
    }

    /**
     * Trims the SQL statement to a single line truncated at 80 chars — matches the format
     * used in the WARN log so the rapport entry and the log line are easy to correlate.
     */
    private static String sqlPreview(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) : oneLine;
    }

    /**
     * Formats a DDL error message for STRICT mode.
     *
     * @param idx the 1-based statement index
     * @param sql the SQL statement that failed
     * @param e   the exception thrown by PostgreSQL
     * @return a formatted error message with remediation advice
     */
    private String formatDdlError(int idx, String sql, SQLException e) {
        return "DDL statement #" + idx + " failed:\n"
             + "  SQL: " + sql.substring(0, Math.min(200, sql.length())) + (sql.length() > 200 ? "..." : "") + "\n"
             + "  Cause: " + e.getMessage() + "\n"
             + "  To skip this statement, set replication.ddl-error-mode=LENIENT (NOT recommended for production).";
    }

    /**
     * Découpe le SQL de pg_dump en statements individuels.
     * Gère les $$ (fonctions), les commentaires --, les ; dans les strings.
     */
    private List<String> splitStatements(String ddl) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollarQuote = false;
        boolean inSingleQuote = false;
        String dollarTag = "";

        String[] lines = ddl.split("\n");


        for (String line : lines) {
            // Ignorer les commentaires pg_dump (-- lignes seules)
            String trimmed = line.trim();

            if (trimmed.startsWith("--")
                    || trimmed.isEmpty()
                    || trimmed.startsWith("\\")
                    || trimmed.startsWith("SET transaction_timeout")) {
                continue;
            }

            current.append(line).append("\n");

            // Détection dollar-quoting ($$ ou $tag$)
            String currentStr = current.toString();
            if (!inSingleQuote) {
                int idx = 0;
                while (idx < line.length()) {
                    if (!inDollarQuote && line.charAt(idx) == '$') {
                        int end = line.indexOf('$', idx + 1);
                        if (end != -1) {
                            dollarTag = line.substring(idx, end + 1);
                            inDollarQuote = true;
                            idx = end + 1;
                            continue;
                        }
                    } else if (inDollarQuote && line.contains(dollarTag)) {
                        inDollarQuote = false;
                        dollarTag = "";
                    }
                    idx++;
                }
            }

            // Un statement se termine par ; hors quote
            if (!inDollarQuote && !inSingleQuote && trimmed.endsWith(";")) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }

        // Résidu sans ;
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            statements.add(remaining);
        }

        return statements;
    }
}
