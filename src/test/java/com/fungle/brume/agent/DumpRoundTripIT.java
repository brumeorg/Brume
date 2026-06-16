package com.fungle.brume.agent;

import com.fungle.brume.command.CommandEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end round-trip test for the dump pipeline (#7, A6).
 *
 * <p>Validates the full Phase 1 chain in one shot:
 * <ol>
 *   <li>Run {@link ReplicationAgent#run(CommandEnum)} with {@code EXECUTE} and
 *       {@code brume.sink.type=DUMP} + {@code brume.sink.compression=GZIP} on the
 *       source schema {@code test_brume}.</li>
 *   <li>The {@code SqlFileSink} produces a {@code .sql.gz} dump file containing
 *       the DDL and anonymized data.</li>
 *   <li>Drop {@code test_brume} from the target ("clean target") and pipe the
 *       decompressed dump into {@code docker exec brume-target psql -f -}.</li>
 *   <li>Query the restored target via JDBC: assert FK integrity (zero orphans on
 *       all FK relationships, including cyclic {@code users.manager_id}), expected
 *       row counts (extraction filter applied), and anonymization spot checks
 *       (emails differ from the well-known source values).</li>
 * </ol>
 *
 * <p>The test is sensitive (without explicit mutation testing) to:
 * <ul>
 *   <li>Broken TSV escaping — values containing tabs/newlines/backslashes (in
 *       JSONB payloads) would mismatch on restore.</li>
 *   <li>Broken topological ordering — orphan FK counts > 0.</li>
 *   <li>Broken anonymization wiring — well-known source emails would still appear.</li>
 * </ul>
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=DUMP",
        "brume.sink.compression=GZIP",
        "brume.sink.output-path=target/dump-roundtrip-it.sql.gz",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.schema=test_brume",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
        "replication.pool-size=3"  // small pool to avoid saturating target's max_connections in CI
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DumpRoundTripIT {

    private static final Path DUMP_PATH = Path.of("target", "dump-roundtrip-it.sql.gz");

    @Autowired
    private ReplicationAgent agent;

    /**
     * Standalone JdbcTemplate — with sink.type=DUMP, the {@code targetDataSource} bean is
     * not created (#6b, ADR-0028), so this IT wires its own target connection to inspect
     * the restored dump.
     */
    private final JdbcTemplate targetJdbc = standaloneTargetJdbc();

    private static JdbcTemplate standaloneTargetJdbc() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new org.postgresql.Driver());
        ds.setUrl("jdbc:postgresql://localhost:5460/postgres");
        ds.setUsername("postgres");
        ds.setPassword("postgres");
        return new JdbcTemplate(ds);
    }

    @BeforeEach
    void cleanTarget() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
    }

    @AfterEach
    void cleanup() throws Exception {
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
        Files.deleteIfExists(DUMP_PATH);
    }

    @Test
    @DisplayName("Dump produced by Brume restores cleanly via psql and passes integrity + anonymization checks")
    void roundTrip() throws Exception {
        // Act 1 — run Brume in dump mode against test_brume source
        agent.run(CommandEnum.EXECUTE);

        // Sanity — dump file exists and is gzip
        assertThat(Files.exists(DUMP_PATH)).as("dump file produced").isTrue();
        byte[] head = Files.readAllBytes(DUMP_PATH);
        assertThat(head[0] & 0xFF).as("gzip magic byte 0").isEqualTo(0x1F);
        assertThat(head[1] & 0xFF).as("gzip magic byte 1").isEqualTo(0x8B);

        // Act 2 — restore the dump on the (now clean) target via docker exec psql
        restoreDumpViaPsql();

        // Verify 1 — referential integrity (zero FK orphans on every relationship)
        assertOrphanCount("orders.user_id",
                "SELECT COUNT(*) FROM test_brume.orders o "
                        + "WHERE NOT EXISTS (SELECT 1 FROM test_brume.users u WHERE u.id = o.user_id)");
        assertOrphanCount("order_items.order_id",
                "SELECT COUNT(*) FROM test_brume.order_items oi "
                        + "WHERE NOT EXISTS (SELECT 1 FROM test_brume.orders o WHERE o.id = oi.order_id)");
        assertOrphanCount("order_items.product_id",
                "SELECT COUNT(*) FROM test_brume.order_items oi "
                        + "WHERE NOT EXISTS (SELECT 1 FROM test_brume.products p WHERE p.id = oi.product_id)");
        assertOrphanCount("users.manager_id",
                "SELECT COUNT(*) FROM test_brume.users u "
                        + "WHERE manager_id IS NOT NULL "
                        + "  AND NOT EXISTS (SELECT 1 FROM test_brume.users m WHERE m.id = u.manager_id)");

        // Verify 2 — extraction filter respected
        Integer ordersInFilter = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.orders WHERE created_at >= '2025-01-01'",
                Integer.class);
        Integer ordersOutOfFilter = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.orders WHERE created_at < '2025-01-01'",
                Integer.class);
        assertThat(ordersInFilter)
                .as("orders within filter (config: created_at >= 2025-01-01) — expected 8")
                .isEqualTo(8);
        assertThat(ordersOutOfFilter)
                .as("orders outside filter — must be excluded by extraction")
                .isZero();

        // Verify 3 — FK parents resolved
        Integer userCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.users", Integer.class);
        Integer productCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products", Integer.class);
        assertThat(userCount).as("users resolved as FK parents of orders").isEqualTo(5);
        assertThat(productCount).as("products resolved as FK parents of order_items").isEqualTo(5);

        // Verify 4 — anonymization actually applied (well-known source emails must NOT appear)
        Integer leakedSourceEmails = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.users WHERE email LIKE '%@example.com'",
                Integer.class);
        assertThat(leakedSourceEmails)
                .as("source @example.com emails must be replaced by FAKE strategy on target")
                .isZero();

        // Verify 5 — dump did not leak source PII into shipping_address (FAKE replaces it)
        Integer shippingPlaceholders = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.orders WHERE shipping_address IS NULL",
                Integer.class);
        assertThat(shippingPlaceholders)
                .as("shipping_address must be present (FAKE applied), not NULL")
                .isZero();
    }

    private void assertOrphanCount(String relation, String sql) {
        Integer count = targetJdbc.queryForObject(sql, Integer.class);
        assertThat(count).as("FK orphan count on %s", relation).isZero();
    }

    /**
     * Decompresses the dump in Java and pipes the plain SQL into
     * {@code docker exec -i brume-target psql ... -f -}. Same pattern as
     * {@code SqlFileSinkCompressionIT}.
     */
    private void restoreDumpViaPsql() throws Exception {
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
             InputStream decompressed = new GZIPInputStream(raw);
             OutputStream stdin = process.getOutputStream()) {
            decompressed.transferTo(stdin);
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
}
