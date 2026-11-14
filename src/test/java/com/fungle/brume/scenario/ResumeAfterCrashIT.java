package com.fungle.brume.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.checkpoint.CheckpointService;
import com.fungle.brume.checkpoint.CheckpointState;
import com.fungle.brume.checkpoint.CheckpointStore;
import com.fungle.brume.checkpoint.ConfigHash;
import com.fungle.brume.command.CommandEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #25 / A19 — IT validating the resume mechanism end-to-end against Docker.
 *
 * <p>Until #25i / #25j these tests asserted the defect : a "completed" table ended with
 * 0 rows on the target, because every run started with {@code DROP SCHEMA CASCADE} and the
 * checkpoint then skipped the table. They now assert the contract of a resume : what the
 * interrupted run wrote is kept, the rest is written, the checkpoint disappears with success.
 *
 * <p>This is the most realistic "crash simulation" we can run in CI without
 * killing the JVM mid-run : we materialize the state a real crash would have
 * left behind, then exercise the resume code path.
 *
 * <p>The checkpoint file path is registered dynamically via
 * {@link DynamicPropertySource} so each test gets a fresh, isolated path.
 *
 * <p><strong>Requires Docker</strong> : {@code brume-source} (5432) and
 * {@code brume-target} (5460), cf. {@code docker-compose.yml}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=JDBC",
        "brume.checkpoint.enabled=true",
        "replication.source.url=${it.db.source-url}",
        "replication.target.url=${it.db.target-url}",
        "replication.schema=test_brume",
        "replication.pgdump-path=${it.db.pgdump-path}",
        "replication.pool-size=3"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResumeAfterCrashIT {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerCheckpointPath(DynamicPropertyRegistry r) {
        r.add("brume.checkpoint.path", () -> tempDir.resolve("brume-checkpoint.json").toString());
    }

    @Autowired
    private ReplicationAgent agent;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ObjectMapper objectMapper;

    private JdbcTemplate targetJdbc;
    private Path checkpointPath;

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void resetState() {
        this.checkpointPath = tempDir.resolve("brume-checkpoint.json");
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
        try {
            java.nio.file.Files.deleteIfExists(checkpointPath);
        } catch (Exception ignored) { /* fresh state guaranteed */ }
    }

    @AfterEach
    void cleanup() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
    }

    @Test
    @DisplayName("#25j — resume keeps what the interrupted run wrote : no DDL replay, written table skipped, "
            + "the rest completed")
    void resumeKeepsWrittenTablesAndCompletesTheRest() throws Exception {
        // A full run materializes the target the interrupted run would have left behind.
        agent.run(CommandEnum.EXECUTE);
        long orders = rowCount("orders");
        assertThat(orders).isPositive();
        // "Crash" after 'orders' : its rows are on the target (one tagged to detect a rewrite),
        // 'order_items' was never written.
        targetJdbc.execute("UPDATE test_brume.orders SET notes = 'written-before-the-crash' "
                + "WHERE id = (SELECT min(id) FROM test_brume.orders)");
        targetJdbc.execute("DELETE FROM test_brume.order_items");
        seedCheckpoint("orders");

        agent.run(CommandEnum.EXECUTE);

        assertThat(rowCount("orders")).as("'orders' kept as written — not dropped by a DDL replay").isEqualTo(orders);
        assertThat(targetJdbc.queryForObject("SELECT count(*) FROM test_brume.orders "
                + "WHERE notes = 'written-before-the-crash'", Long.class))
                .as("'orders' was skipped, not rewritten").isEqualTo(1L);
        assertThat(rowCount("order_items")).as("the remaining table is written").isPositive();
        assertThat(checkpointPath).as("#25i — the checkpoint is removed once the resumed run completes").doesNotExist();
    }

    @Test
    @DisplayName("#25i — a successful run removes its checkpoint : the next execute skips nothing")
    void successfulRunRemovesTheCheckpoint() throws Exception {
        agent.run(CommandEnum.EXECUTE);

        assertThat(checkpointPath).doesNotExist();
        targetJdbc.execute("DELETE FROM test_brume.order_items");

        agent.run(CommandEnum.EXECUTE);

        assertThat(rowCount("order_items")).as("second run writes every table again").isPositive();
    }

    @Test
    @DisplayName("#25j — checkpoint listing written tables but target schema gone : fresh run, nothing skipped")
    void resumeWithoutTargetSchemaStartsFresh() throws Exception {
        seedCheckpoint("order_items"); // target schema dropped by @BeforeEach

        agent.run(CommandEnum.EXECUTE);

        assertThat(rowCount("orders")).isPositive();
        assertThat(rowCount("order_items")).as("not skipped : its rows were lost with the schema").isPositive();
        assertThat(checkpointPath).doesNotExist();
    }

    private void seedCheckpoint(String... completed) throws Exception {
        String hash = ConfigHash.of(Path.of("src/test/resources/test-config-integration.yaml"));
        CheckpointState seed = CheckpointState.initial(UUID.randomUUID().toString(), "test_brume", hash, Instant.now());
        for (String table : completed) {
            seed = seed.withTableCompleted(table, Instant.now());
        }
        new CheckpointStore(checkpointPath, objectMapper).write(seed);
    }

    private long rowCount(String table) {
        Long c = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume." + table, Long.class);
        return c == null ? 0L : c;
    }
}
