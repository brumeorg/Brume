package com.fungle.brume.scenario;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J4 — Idempotency : two consecutive {@code execute} runs against the same
 * source produce byte-identical target snapshots.
 *
 * <p>User journey : a user runs {@code brume execute} once, then re-runs it (to
 * refresh after source updates, or to recover from a partial failure). With a
 * stable HMAC seed and FPE key, the anonymization is deterministic — so the
 * same source row always anonymizes to the same target row. The end state of
 * the second run must be bitwise identical to the first.
 *
 * <p>This catches : non-deterministic anonymization (forgotten seed plumbing,
 * implicit randomness in a strategy), FPE_ID overflow on re-encryption,
 * substitution dictionary leak across runs, or {@code ON CONFLICT DO NOTHING}
 * regressions that would mask divergence.
 *
 * <p>Also satisfies the existing roadmap ticket {@code #29 / A20 — Test E2E
 * d'idempotence} (mark ✅ when this IT lands).
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose
 * up -d}. Uses {@code docker exec brume-source pg_dump} so no local
 * {@code pg_dump} binary is required.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=JDBC",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.schema=test_brume",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
        "replication.pool-size=3"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyDoubleRunIT {

    /** Tables snapshotted per run — names match the {@code test_brume} fixture. */
    private static final List<String> TABLES = List.of("users", "products", "orders", "order_items");

    @Autowired
    private ReplicationAgent agent;

    private JdbcTemplate targetJdbc;

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void cleanTarget() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
    }

    @AfterEach
    void cleanup() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
    }

    @Test
    @DisplayName("execute twice produces byte-identical target snapshots (deterministic anonymization)")
    void doubleRunIsIdempotent() throws Exception {
        agent.run(CommandEnum.EXECUTE);
        Snapshot first = snapshot();

        agent.run(CommandEnum.EXECUTE);
        Snapshot second = snapshot();

        assertThat(second.counts)
                .as("row counts per table must be identical between consecutive runs")
                .containsExactlyInAnyOrderEntriesOf(first.counts);
        assertThat(second.checksums)
                .as("row content per table must be byte-identical between consecutive runs "
                        + "— catches non-deterministic anonymization or dictionary leaks")
                .containsExactlyInAnyOrderEntriesOf(first.checksums);
    }

    private Snapshot snapshot() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> checksums = new LinkedHashMap<>();
        for (String table : TABLES) {
            Long count = targetJdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_brume." + table, Long.class);
            counts.put(table, count == null ? 0L : count);

            // md5 over to_jsonb(t)::text rather than t::text — the JSONB serialization is
            // canonical (lexicographic key order via Postgres internals, explicit numeric scale,
            // explicit string quoting) and surfaces JSONB column drift that the row-text
            // representation would mask. Audit C4 (2026-05-05).
            String checksum = targetJdbc.queryForObject(
                    "SELECT md5(string_agg(to_jsonb(t)::text, ',' ORDER BY to_jsonb(t)::text)) "
                            + "FROM test_brume." + table + " t",
                    String.class);
            checksums.put(table, checksum == null ? "" : checksum);
        }
        return new Snapshot(counts, checksums);
    }

    private record Snapshot(Map<String, Long> counts, Map<String, String> checksums) {}
}
