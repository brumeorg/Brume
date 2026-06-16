package com.fungle.brume.scenario;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.replicator.SchemaReplicator;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J2 — Dry-run preserves a multi-row, multi-table target snapshot.
 *
 * <p>User journey : a user with a populated target schema runs
 * {@code brume dry-run} as a confidence check before a real run. The full
 * pipeline (extract + anonymize + report) executes, but every existing row
 * in every existing table on the target must remain bitwise identical.
 *
 * <p>This strengthens the existing {@code DryRunIT}, which only verifies a
 * single sentinel row in a single sentinel schema. J2 covers two tables with
 * primary keys + non-key text values + numeric checksums, catching regressions
 * where a future code path could leak writes into the target despite the
 * NullSink wiring (e.g. a TRUNCATE inadvertently routed outside the sink, an
 * eager schema replication branch, an accidental {@code DROP SCHEMA} in
 * preflight).
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose
 * up -d}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=NULL",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
@DirtiesContext
class DryRunPreservesTargetIT {

    private static final String SENTINEL_SCHEMA = "j2_sentinel";

    /** Mocked so this IT does not depend on a local pg_dump binary. */
    @MockitoBean
    private SchemaReplicator schemaReplicator;

    @Autowired
    private ReplicationAgent agent;

    /**
     * Standalone JdbcTemplate — with sink.type=NULL, the {@code targetDataSource} bean is
     * not created (#6b, ADR-0028), so this IT wires its own target connection from the
     * known test creds (mirrors what JdbcVsDumpEquivalenceIT does for its DUMP phase).
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
    void seedSentinel() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS " + SENTINEL_SCHEMA + " CASCADE");
        targetJdbc.execute("CREATE SCHEMA " + SENTINEL_SCHEMA);
        targetJdbc.execute("CREATE TABLE " + SENTINEL_SCHEMA + ".customers ("
                + "id INT PRIMARY KEY, name TEXT NOT NULL, balance NUMERIC(10,2))");
        targetJdbc.execute("CREATE TABLE " + SENTINEL_SCHEMA + ".invoices ("
                + "id INT PRIMARY KEY, customer_id INT NOT NULL, amount NUMERIC(10,2))");

        for (int i = 1; i <= 5; i++) {
            targetJdbc.update(
                    "INSERT INTO " + SENTINEL_SCHEMA + ".customers VALUES (?, ?, ?)",
                    i, "customer_" + i, i * 100.0);
        }
        for (int i = 101; i <= 106; i++) {
            targetJdbc.update(
                    "INSERT INTO " + SENTINEL_SCHEMA + ".invoices VALUES (?, ?, ?)",
                    i, ((i - 101) % 5) + 1, i * 13.5);
        }
    }

    @AfterEach
    void cleanupSentinel() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS " + SENTINEL_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("dry-run leaves every row in every sentinel table bitwise identical")
    void dryRunDoesNotMutateTarget() throws Exception {
        Snapshot before = snapshotSentinel();

        agent.run(CommandEnum.DRY_RUN);

        Snapshot after = snapshotSentinel();

        assertThat(after.customersCount)
                .as("dry-run must not touch customers row count")
                .isEqualTo(before.customersCount);
        assertThat(after.customersChecksum)
                .as("dry-run must not modify any customers row")
                .isEqualTo(before.customersChecksum);
        assertThat(after.invoicesCount)
                .as("dry-run must not touch invoices row count")
                .isEqualTo(before.invoicesCount);
        assertThat(after.invoicesChecksum)
                .as("dry-run must not modify any invoices row")
                .isEqualTo(before.invoicesChecksum);
    }

    private Snapshot snapshotSentinel() {
        Integer customersCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SENTINEL_SCHEMA + ".customers", Integer.class);
        Integer invoicesCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SENTINEL_SCHEMA + ".invoices", Integer.class);
        // md5(string_agg(...)) over a deterministic ORDER BY gives a stable per-table fingerprint
        String customersChecksum = targetJdbc.queryForObject(
                "SELECT md5(string_agg(id || '|' || name || '|' || balance, ',' ORDER BY id)) "
                        + "FROM " + SENTINEL_SCHEMA + ".customers", String.class);
        String invoicesChecksum = targetJdbc.queryForObject(
                "SELECT md5(string_agg(id || '|' || customer_id || '|' || amount, ',' ORDER BY id)) "
                        + "FROM " + SENTINEL_SCHEMA + ".invoices", String.class);
        return new Snapshot(customersCount, customersChecksum, invoicesCount, invoicesChecksum);
    }

    private record Snapshot(
            Integer customersCount, String customersChecksum,
            Integer invoicesCount, String invoicesChecksum) {}
}
