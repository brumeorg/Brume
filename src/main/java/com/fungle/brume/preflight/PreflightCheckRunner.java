package com.fungle.brume.preflight;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.error.ConnectionException;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.SchemaException;
import com.fungle.brume.writer.SinkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase-0 preflight: 7 sub-checks that must pass before {@code ReplicationAgent} opens
 * any pool, runs {@code pg_dump}, or starts the extraction. Each check fails fast with
 * a typed {@code BrumeException} carrying a {@code PREFLIGHT_*} (or reused
 * {@code CONNECTION_*}) error code and an actionable suggestion. Total wall-clock is
 * bounded by a 2-second JDBC {@code loginTimeout} and a 5-second {@code pg_dump
 * --version} subprocess timeout — total budget &lt; 3s on a down scenario.
 *
 * <p>Order — local first, then source, then target (cf. ADR pour #19/A13) :
 * <ol>
 *   <li>{@code pg_dump --version} on the local PATH (subprocess, no network).</li>
 *   <li>Source database reachable (TCP + auth) and {@code SELECT version()} succeeds.</li>
 *   <li>{@code pg_dump} major version &gt;= source major version (cf. Postgres compatibility policy).</li>
 *   <li>Source schema is visible in {@code information_schema.schemata}.</li>
 *   <li>Source role has {@code USAGE} on the schema.</li>
 *   <li>Target reachable and {@code SELECT 1} succeeds (skipped when {@code sink.type != JDBC}).</li>
 *   <li>Target role can {@code CREATE} on the database AND owns/is a role-member of the
 *       existing target schema, if any (covers {@code DROP SCHEMA CASCADE} in
 *       {@link com.fungle.brume.replicator.SchemaReplicator#replicate}).</li>
 * </ol>
 *
 * <p>Skipped entirely when {@code sink.type ∈ {DUMP, NULL}}? No — only the target-side
 * checks (6 and 7) are skipped. Source-side checks always run because the source is
 * read in every mode (plan / dry-run / execute / dump).
 *
 * <p>This runner deliberately uses {@link DriverManager} (not the Hikari pools) for its
 * own probe connections, so it can force a short {@code loginTimeout} regardless of the
 * pool configuration. Hikari's {@code connectionTimeout} does not bound the JDBC
 * driver's initial TCP/startup phase, which is the worst-case wait when the target host
 * silently drops SYN.
 */
@Component
public class PreflightCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(PreflightCheckRunner.class);

    /**
     * Bounds the worst-case wait in {@link DriverManager#getConnection} when the host is
     * unreachable. Without this, a target behind a firewall that drops SYN packets makes
     * the JVM block 60-127s (Windows TCP retransmission defaults) — defeats the &lt;3s
     */
    static final int LOGIN_TIMEOUT_SECONDS = 2;

    /** {@code pg_dump --version} is local and instantaneous; 5s covers Windows cold-start. */
    static final int PG_DUMP_VERSION_TIMEOUT_SECONDS = 5;

    /**
     * Matches {@code PostgreSQL 16.2 on ...}, {@code pg_dump (PostgreSQL) 16.2}, etc.
     * Captures the major number. Pre-10 (9.x) versions are unsupported by Brume — the
     * regex would still parse "9" as the major but the version check rejects servers
     * &lt; 10 by virtue of the &gt;= comparison anyway.
     */
    private static final Pattern POSTGRES_MAJOR = Pattern.compile("PostgreSQL\\)?\\s+(\\d+)");

    private final ReplicationProperties replicationProperties;
    private final BrumeProperties brumeProperties;

    public PreflightCheckRunner(ReplicationProperties replicationProperties,
                                BrumeProperties brumeProperties) {
        this.replicationProperties = replicationProperties;
        this.brumeProperties = brumeProperties;
    }

    /**
     * Runs all preflight checks in order. Throws the first failing check's
     * {@link com.fungle.brume.error.BrumeException} subtype; everything that came before
     * has already logged an INFO line. On success, logs the total wall-clock.
     */
    public void run() {
        long start = System.currentTimeMillis();
        SinkType sinkType = brumeProperties.sink().type();
        BrumeProperties.PreflightProperties.Mode mode = brumeProperties.preflight().mode();
        log.info("Preflight checks starting (login-timeout {}s, sink={}, mode={})...",
                LOGIN_TIMEOUT_SECONDS, sinkType, mode);

        // AUDIT mode (#73 / ADR-0036): skip source-side checks (1-5) and target write
        // checks (7). Audit only reads the target via SELECT — source may be down
        // post-migration, DROP SCHEMA CASCADE is never invoked.
        if (mode == BrumeProperties.PreflightProperties.Mode.AUDIT) {
            runAuditModeChecks();
            long elapsed = System.currentTimeMillis() - start;
            log.info("Preflight (AUDIT mode) passed in {} ms", elapsed);
            return;
        }

        // 1 — pg_dump local. Required only when the pipeline will actually invoke pg_dump:
        // JDBC (SchemaReplicator.replicate) and DUMP (SqlFileSink.open -> dumpSchema).
        // NULL sink (plan / dry-run) never invokes pg_dump, so requiring the binary would
        // make plan / dry-run fail on hosts without a local PostgreSQL client install.
        boolean pgDumpUsed = sinkType == SinkType.JDBC || sinkType == SinkType.DUMP;
        Integer pgDumpMajor = null;
        if (pgDumpUsed) {
            pgDumpMajor = checkPgDumpAvailable();
            log.info("Preflight 1/7 OK: pg_dump major = {}", pgDumpMajor);
        } else {
            log.info("Preflight 1/7 SKIPPED: sink.type={} does not invoke pg_dump", sinkType);
        }

        // 2 — source reachable (always: every mode reads from source)
        int sourceMajor = checkSourceReachableAndGetVersion();
        log.info("Preflight 2/7 OK: source PostgreSQL major = {}", sourceMajor);

        // 3 — pg_dump major >= source major (only if pg_dump is used)
        if (pgDumpMajor != null) {
            checkPgDumpVersionCompatibility(pgDumpMajor, sourceMajor);
            log.info("Preflight 3/7 OK: pg_dump {} >= source {}", pgDumpMajor, sourceMajor);
        } else {
            log.info("Preflight 3/7 SKIPPED: pg_dump not used in sink.type={}", sinkType);
        }

        // 4 + 5 — source schema visible + USAGE (always)
        checkSourceSchemaExistsAndUsage();
        log.info("Preflight 4/7 + 5/7 OK: source schema exists + USAGE granted");

        // 6 + 7 — target reachable + CREATE/ownership (JDBC only — DUMP/NULL skip target)
        if (sinkType == SinkType.JDBC) {
            checkTargetReachableAndPrivileges();
            log.info("Preflight 6/7 + 7/7 OK: target reachable + CREATE/ownership granted");
        } else {
            log.info("Preflight 6/7 + 7/7 SKIPPED: sink.type={} (target not needed, cf. ADR-0028)", sinkType);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Preflight checks passed in {} ms", elapsed);
    }

    // ---------------------------------------------------------------------
    // AUDIT mode — minimal target-only reachability check
    // ---------------------------------------------------------------------

    /**
     * Runs the bare-minimum check for {@code brume audit} : the target must be
     * reachable and respond to {@code SELECT 1}. Source is never read in audit mode,
     * so its connectivity is irrelevant. Schema USAGE/ownership probes are skipped
     * because audit only issues {@code SELECT count(*) ... GROUP BY} queries.
     *
     * <p>If the schema does not exist on the target, the audit will fail later with
     * an explicit empty-tables message — no need to probe here.
     */
    void runAuditModeChecks() {
        ReplicationProperties.Target tgt = replicationProperties.target();
        try (Connection c = openConnection(tgt.url(), tgt.username(), tgt.password())) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw new ConnectionException(
                            BrumeErrorCode.CONNECTION_TARGET_UNREACHABLE,
                            "Target `SELECT 1` returned an unexpected result.",
                            "Verify the target endpoint really is a PostgreSQL instance.");
                }
            }
            log.info("Preflight (AUDIT) OK: target reachable");
        } catch (SQLException e) {
            throw new ConnectionException(
                    BrumeErrorCode.CONNECTION_TARGET_UNREACHABLE,
                    "Target database unreachable: " + e.getMessage(),
                    "Verify replication.target.url, credentials, and network reachability "
                            + "(JDBC loginTimeout=" + LOGIN_TIMEOUT_SECONDS + "s).",
                    e);
        }
    }

    // ---------------------------------------------------------------------
    // Check 1 — pg_dump executable + version
    // ---------------------------------------------------------------------

    /**
     * Invokes {@code <pgdumpPath> --version} as a subprocess and parses the major
     * number. {@code pgdumpPath} may be multi-token (e.g. {@code "docker exec
     * brume-source pg_dump"} in CI/dev compose), so we split before appending the flag.
     */
    int checkPgDumpAvailable() {
        String pgDumpPath = replicationProperties.pgdumpPath();
        List<String> cmd = new ArrayList<>();
        for (String tok : pgDumpPath.split("\\s+")) {
            if (!tok.isBlank()) cmd.add(tok);
        }
        cmd.add("--version");

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
        } catch (IOException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND,
                    "pg_dump not found or not executable: " + e.getMessage(),
                    "Install PostgreSQL client tools or set replication.pgdump-path to an absolute path (current: '"
                            + pgDumpPath + "').",
                    e);
        }

        String output;
        try {
            output = readAll(p);
            boolean exited = p.waitFor(PG_DUMP_VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                throw new ConfigurationException(
                        BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND,
                        "pg_dump --version timed out after " + PG_DUMP_VERSION_TIMEOUT_SECONDS
                                + "s (command: " + String.join(" ", cmd) + ").",
                        "Verify replication.pgdump-path points to a responsive binary.");
            }
        } catch (IOException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND,
                    "Failed to read pg_dump --version output: " + e.getMessage(),
                    "Check disk/IO state on the host running Brume.",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfigurationException(
                    BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND,
                    "Interrupted while waiting for pg_dump --version",
                    "Retry the operation.",
                    e);
        }

        if (p.exitValue() != 0) {
            throw new ConfigurationException(
                    BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND,
                    "pg_dump --version exited with code " + p.exitValue() + ": " + output.trim(),
                    "Verify replication.pgdump-path; default is 'pg_dump' on PATH.");
        }
        return parsePostgresMajor(output, "pg_dump --version output",
                BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND);
    }

    private static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Check 2 + 3 — source reachable + version compatibility
    // ---------------------------------------------------------------------

    /**
     * Opens a probe connection to the source with a 2s loginTimeout, runs
     * {@code SELECT version()}, returns the major number. Wraps any SQLException in a
     * {@code ConnectionException} with the operator-facing URL omitted (URLs may carry
     * inline credentials — cf. ADR-0025 logging policy).
     */
    int checkSourceReachableAndGetVersion() {
        ReplicationProperties.Source src = replicationProperties.source();
        try (Connection c = openConnection(src.url(), src.username(), src.password())) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version()")) {
                if (!rs.next()) {
                    throw new ConnectionException(
                            BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE,
                            "Source `SELECT version()` returned no row.",
                            "Verify the source endpoint really is a PostgreSQL instance.");
                }
                return parsePostgresMajor(rs.getString(1), "source SELECT version()",
                        BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE);
            }
        } catch (SQLException e) {
            throw new ConnectionException(
                    BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE,
                    "Source database unreachable: " + e.getMessage(),
                    "Verify replication.source.url, credentials, and network reachability "
                            + "(JDBC loginTimeout=" + LOGIN_TIMEOUT_SECONDS + "s).",
                    e);
        }
    }

    void checkPgDumpVersionCompatibility(int pgDumpMajor, int sourceMajor) {
        if (pgDumpMajor < sourceMajor) {
            throw new ConfigurationException(
                    BrumeErrorCode.PREFLIGHT_PG_DUMP_VERSION_MISMATCH,
                    "pg_dump major version " + pgDumpMajor + " is older than the source PostgreSQL "
                            + "major version " + sourceMajor + ". pg_dump must be >= server version.",
                    "Install pg_dump >= " + sourceMajor + " and update replication.pgdump-path "
                            + "(on Windows, point to the matching PostgreSQL bin directory).");
        }
    }

    // ---------------------------------------------------------------------
    // Check 4 + 5 — source schema + USAGE
    // ---------------------------------------------------------------------

    void checkSourceSchemaExistsAndUsage() {
        ReplicationProperties.Source src = replicationProperties.source();
        String schema = replicationProperties.schema();
        try (Connection c = openConnection(src.url(), src.username(), src.password())) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SchemaException(
                                BrumeErrorCode.PREFLIGHT_SOURCE_SCHEMA_NOT_FOUND,
                                "Source schema '" + schema
                                        + "' does not exist or is not visible to the current role.",
                                "Verify replication.schema and that the source role can see the schema.");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT has_schema_privilege(current_user, ?, 'USAGE')")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        throw new ConfigurationException(
                                BrumeErrorCode.PREFLIGHT_NO_USAGE_ON_SOURCE,
                                "Source role lacks USAGE on schema '" + schema + "'.",
                                "GRANT USAGE ON SCHEMA " + schema + " TO " + src.username() + ";");
                    }
                }
            }
        } catch (SQLException e) {
            throw new ConnectionException(
                    BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE,
                    "Source preflight query failed: " + e.getMessage(),
                    "Verify the source role has SELECT on information_schema/pg_catalog.",
                    e);
        }
    }

    // ---------------------------------------------------------------------
    // Check 6 + 7 — target reachable + CREATE/ownership
    // ---------------------------------------------------------------------

    /**
     * The two-step ownership check (CREATE on database + role-membership of the
     * schema owner if the schema already exists) is precisely what catches the failure
     * mode raised by the challenger: a target role with CREATE on the database but
     * non-owner of the existing schema lets the preflight pass while
     * {@code DROP SCHEMA CASCADE} dies seconds later. Cf. challenger objection #1.
     */
    void checkTargetReachableAndPrivileges() {
        ReplicationProperties.Target tgt = replicationProperties.target();
        String schema = replicationProperties.schema();
        try (Connection c = openConnection(tgt.url(), tgt.username(), tgt.password())) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw new ConnectionException(
                            BrumeErrorCode.CONNECTION_TARGET_UNREACHABLE,
                            "Target `SELECT 1` returned an unexpected result.",
                            "Verify the target endpoint really is a PostgreSQL instance.");
                }
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT has_database_privilege(current_user, current_database(), 'CREATE')")) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    throw new ConfigurationException(
                            BrumeErrorCode.PREFLIGHT_NO_CREATE_ON_TARGET,
                            "Target role '" + tgt.username() + "' lacks CREATE on the current database.",
                            "GRANT CREATE ON DATABASE <db> TO " + tgt.username() + ";");
                }
            }
            // If the schema already exists, also verify ownership (or role-membership
            // of the owner). DROP SCHEMA CASCADE — run by SchemaReplicator — requires it.
            String owner;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pg_catalog.pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = ?")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    owner = rs.next() ? rs.getString(1) : null;
                }
            }
            if (owner != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT pg_has_role(current_user, ?, 'MEMBER')")) {
                    ps.setString(1, owner);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !rs.getBoolean(1)) {
                            throw new ConfigurationException(
                                    BrumeErrorCode.PREFLIGHT_NO_OWNERSHIP_ON_TARGET_SCHEMA,
                                    "Target role '" + tgt.username()
                                            + "' is not a member of the owner ('" + owner
                                            + "') of existing schema '" + schema
                                            + "'. DROP SCHEMA CASCADE would fail at runtime.",
                                    "ALTER SCHEMA " + schema + " OWNER TO " + tgt.username()
                                            + "; or GRANT " + owner + " TO " + tgt.username() + ";");
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new ConnectionException(
                    BrumeErrorCode.CONNECTION_TARGET_UNREACHABLE,
                    "Target database unreachable: " + e.getMessage(),
                    "Verify replication.target.url, credentials, and network reachability "
                            + "(JDBC loginTimeout=" + LOGIN_TIMEOUT_SECONDS + "s).",
                    e);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Connection openConnection(String url, String user, String pwd) throws SQLException {
        return DriverManager.getConnection(appendLoginTimeout(url, LOGIN_TIMEOUT_SECONDS), user, pwd);
    }

    /**
     * Appends {@code loginTimeout=N} to {@code url}. We always append (no de-dup):
     * pgJDBC honors the rightmost occurrence of a repeated key, so this overrides any
     * user-supplied longer value and guarantees the &lt;3s preflight budget.
     */
    static String appendLoginTimeout(String url, int seconds) {
        char sep = url.indexOf('?') >= 0 ? '&' : '?';
        return url + sep + "loginTimeout=" + seconds;
    }

    static int parsePostgresMajor(String banner, String context, BrumeErrorCode code) {
        Matcher m = POSTGRES_MAJOR.matcher(banner);
        if (!m.find()) {
            throw new ConfigurationException(
                    code,
                    "Could not parse PostgreSQL major version from " + context + ": " + banner.trim(),
                    "Run the command manually and verify the output format; pre-10 PostgreSQL versions are unsupported.");
        }
        return Integer.parseInt(m.group(1));
    }
}
