package com.fungle.brume.scenario;

import com.fungle.brume.BrumeApplication;
import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J5 — JDBC and DUMP write paths produce equivalent target snapshots.
 *
 * <p>User journey : a user can choose between {@code brume.sink.type=JDBC}
 * (write straight to a target DB) and {@code brume.sink.type=DUMP} (write a
 * {@code .sql} file later restored via {@code psql -f}). Both paths must yield
 * the same end state — same rows, same anonymized values, same FK integrity.
 *
 * <p>This catches divergence between the two write paths : a TSV escape bug
 * that only manifests in DUMP, a JDBC binding bug that only manifests in
 * JDBC, ordering differences when the dump is restored, or a strategy that
 * accidentally relies on the JDBC code path for its determinism.
 *
 * <p>Implementation : two child Spring contexts in sequence (no
 * {@code @SpringBootTest} on this class, so we own the bootstrapping). Each
 * context is wired with the relevant sink config, drives one
 * {@code agent.run(EXECUTE)}, snapshots the target rows, then is closed.
 * Snapshots are compared at the end.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose
 * up -d}. Reuses the {@code docker exec brume-source pg_dump} +
 * {@code docker exec brume-target psql} pattern from {@code DumpRoundTripIT}
 * so no local PostgreSQL CLI is required.
 */
class JdbcVsDumpEquivalenceIT {

    private static final List<String> TABLES = List.of("users", "products", "orders", "order_items");
    private static final Path DUMP_PATH = Path.of("target", "scenario-its", "j5-dump.sql");

    private static final Map<String, Object> COMMON_PROPS = Map.of(
            "brume.config-path", "src/test/resources/test-config-integration.yaml",
            "replication.source.url", "jdbc:postgresql://localhost:5432/postgres",
            "replication.target.url", "jdbc:postgresql://localhost:5460/postgres",
            "replication.schema", "test_brume",
            "replication.pgdump-path", "docker exec -e PGPASSWORD=postgres brume-source pg_dump",
            "replication.pool-size", "3"
    );

    @BeforeEach
    void setup() throws Exception {
        if (DUMP_PATH.getParent() != null) {
            Files.createDirectories(DUMP_PATH.getParent());
        }
        Files.deleteIfExists(DUMP_PATH);
    }

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(DUMP_PATH);
    }

    @Test
    @DisplayName("JDBC and DUMP+restore produce identical row counts and content")
    void jdbcAndDumpProduceEquivalentSnapshot() throws Exception {
        // Phase 1 — JDBC path : agent writes directly to target
        Snapshot jdbcSnapshot;
        try (ConfigurableApplicationContext ctx = bootstrap(Map.of(
                "brume.sink.type", "JDBC"
        ))) {
            JdbcTemplate target = jdbc(ctx);
            target.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
            ctx.getBean(ReplicationAgent.class).run(CommandEnum.EXECUTE);
            jdbcSnapshot = snapshot(target);
        }

        // Phase 2 — DUMP path : agent writes to file, then psql restores
        // Note : with #6b (ADR-0028), targetDataSource is gated on sink.type=JDBC, so it does NOT
        // exist in the DUMP context. We therefore wire a standalone JdbcTemplate from the same
        // creds as COMMON_PROPS to inspect the target post-restore.
        Snapshot dumpSnapshot;
        JdbcTemplate dumpTarget = standaloneTargetJdbc();
        dumpTarget.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
        try (ConfigurableApplicationContext ctx = bootstrap(Map.of(
                "brume.sink.type", "DUMP",
                "brume.sink.compression", "NONE",
                "brume.sink.output-path", DUMP_PATH.toString()
        ))) {
            ctx.getBean(ReplicationAgent.class).run(CommandEnum.EXECUTE);
            assertThat(Files.exists(DUMP_PATH))
                    .as("DUMP path must produce the dump file before restore")
                    .isTrue();
            restoreDumpViaPsql();
            dumpSnapshot = snapshot(dumpTarget);
        }

        // The two paths must converge to the same target state
        assertThat(dumpSnapshot.counts)
                .as("row counts per table must match between JDBC and DUMP+restore paths")
                .containsExactlyInAnyOrderEntriesOf(jdbcSnapshot.counts);
        assertThat(dumpSnapshot.checksums)
                .as("row content per table must match between JDBC and DUMP+restore paths "
                        + "— catches TSV escape vs JDBC binding divergence")
                .containsExactlyInAnyOrderEntriesOf(jdbcSnapshot.checksums);
    }

    private static ConfigurableApplicationContext bootstrap(Map<String, Object> overrides) {
        // Build --key=value command-line args : higher precedence than application.yaml,
        // so test overrides actually win over the project's default config.
        Map<String, Object> props = new LinkedHashMap<>(COMMON_PROPS);
        props.putAll(overrides);
        String[] args = props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(BrumeApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run(args);
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext ctx) {
        DataSource ds = (DataSource) ctx.getBean("targetDataSource");
        return new JdbcTemplate(ds);
    }

    /**
     * Builds a JdbcTemplate connected to the target DB without going through the Spring
     * context — needed in Phase 2 where {@code sink.type=DUMP} disables the
     * {@code targetDataSource} bean (#6b, ADR-0028).
     */
    private static JdbcTemplate standaloneTargetJdbc() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new org.postgresql.Driver());
        ds.setUrl((String) COMMON_PROPS.get("replication.target.url"));
        ds.setUsername("postgres");
        ds.setPassword("postgres");
        return new JdbcTemplate(ds);
    }

    private static Snapshot snapshot(JdbcTemplate target) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> checksums = new LinkedHashMap<>();
        for (String table : TABLES) {
            Long count = target.queryForObject(
                    "SELECT COUNT(*) FROM test_brume." + table, Long.class);
            counts.put(table, count == null ? 0L : count);

            // md5 over to_jsonb(t)::text rather than t::text — canonical JSONB serialization
            // is sensitive to JSONB key-order, numeric scale, and explicit type info that the
            // row-text representation masks. Audit C4 (2026-05-05).
            String checksum = target.queryForObject(
                    "SELECT md5(string_agg(to_jsonb(t)::text, ',' ORDER BY to_jsonb(t)::text)) "
                            + "FROM test_brume." + table + " t",
                    String.class);
            checksums.put(table, checksum == null ? "" : checksum);
        }
        return new Snapshot(counts, checksums);
    }

    private static void restoreDumpViaPsql() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "exec", "-i",
                "-e", "PGPASSWORD=postgres",
                "brume-target",
                "psql", "-U", "postgres", "-d", "postgres",
                "-v", "ON_ERROR_STOP=1",
                "--quiet"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (InputStream raw = Files.newInputStream(DUMP_PATH);
             OutputStream stdin = process.getOutputStream()) {
            raw.transferTo(stdin);
        }

        StringBuilder output = new StringBuilder();
        try (var stdout = process.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = stdout.read(buf)) > 0) {
                output.append(new String(buf, 0, n));
            }
        }
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException("psql restore timed out after 120s");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "psql restore failed (exit " + exitCode + "). Output:\n" + output);
        }
    }

    private record Snapshot(Map<String, Long> counts, Map<String, String> checksums) {}
}
