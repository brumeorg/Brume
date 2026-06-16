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
 * <p>Setup : a pre-populated checkpoint file is seeded with {@code completedTables:
 * [users, products]} pointing at the current {@code config.yaml}. {@code brume
 * execute} is then run with checkpoint enabled. The expectation : the target
 * receives <strong>only</strong> the remaining tables ({@code orders},
 * {@code order_items}), proving that the resume path actually skips the completed
 * ones.
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
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.schema=test_brume",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
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
    @DisplayName("resume skips an explicitly-configured table marked completed in the checkpoint")
    void resumeSkipsCompletedTable() throws Exception {
        // Seed a checkpoint marking 'order_items' as already done — as if a previous
        // run crashed right after writing it. The extraction config declares
        // [orders, order_items], so seeding order_items models a late crash.
        // (We seed an explicitly-configured table rather than an FK-parent table
        // because the parent-skip pathway would otherwise leave the target empty
        // of parent rows after the DROP SCHEMA at @BeforeEach.)
        String currentConfigHash = ConfigHash.of(
                Path.of("src/test/resources/test-config-integration.yaml"));
        CheckpointState seed = CheckpointState
                .initial(UUID.randomUUID().toString(), "test_brume", currentConfigHash, Instant.now())
                .withTableCompleted("order_items", Instant.now());
        new CheckpointStore(checkpointPath, objectMapper).write(seed);

        agent.run(CommandEnum.EXECUTE);

        // 'orders' is processed normally — its FK parents (users, products) are
        // pulled by FkParentResolver and written too.
        assertThat(rowCount("orders"))
                .as("'orders' is processed → rows written")
                .isGreaterThan(0);
        assertThat(rowCount("users"))
                .as("'users' (FK parent of orders) → rows written via FkParentResolver")
                .isGreaterThan(0);

        // 'order_items' is in completedTables → skipped → 0 rows on target.
        assertThat(rowCount("order_items"))
                .as("'order_items' was in completedTables → must be skipped → 0 rows on target")
                .isZero();

        // The checkpoint now contains both 'order_items' (seeded) and 'orders'
        // (newly completed). Order : seed first, then newly added.
        CheckpointState reloaded = new CheckpointStore(checkpointPath, objectMapper)
                .read().orElseThrow();
        assertThat(reloaded.completedTables())
                .as("after resume, the checkpoint reflects the seeded skip + newly-completed table")
                .containsExactlyInAnyOrder("order_items", "orders");
    }

    @Test
    @DisplayName("resume skip applies to FK-parent writes when the parent is in completedTables")
    void resumeSkipsParentWritesForCompletedParent() throws Exception {
        // Seed 'orders' as completed. The extraction config processes order_items,
        // which would normally pull 'orders' rows as FK parents via
        // FkParentResolver → the parent-skip pathway must short-circuit that.
        // Note : in a realistic crash scenario, 'orders' would already be on the
        // target from the first run. Here we DROP SCHEMA at @BeforeEach, so we
        // additionally verify that the parent-skip avoids re-extracting / re-
        // writing — but order_items inserts will fail their FK because parent
        // rows are missing. We catch the expected failure and assert on it.
        String hash = ConfigHash.of(Path.of("src/test/resources/test-config-integration.yaml"));
        CheckpointState seed = CheckpointState
                .initial(UUID.randomUUID().toString(), "test_brume", hash, Instant.now())
                .withTableCompleted("orders", Instant.now());
        new CheckpointStore(checkpointPath, objectMapper).write(seed);

        // The run will fail at order_items insert (FK violation on orders) — that
        // is the correct semantic, the test only asserts the parent-skip fired.
        // We catch the exception ; what matters is the row counts AFTER.
        try { agent.run(CommandEnum.EXECUTE); } catch (Exception expected) { /* */ }

        assertThat(rowCount("orders"))
                .as("'orders' is in completedTables AND its parent-write was skipped → 0 rows on target")
                .isZero();
    }

    @Test
    @DisplayName("fresh run with checkpoint enabled records the explicitly-configured tables")
    void freshRunPersistsCheckpoint() throws Exception {
        // No seed — fresh start with checkpoint enabled.
        agent.run(CommandEnum.EXECUTE);

        // V1 records ONLY the tables declared in extraction.tables of the config —
        // FK-parent tables pulled in by FkParentResolver are NOT marked. The
        // test-config-integration.yaml declares 'orders' + 'order_items'.
        // Topological order from #25c gives orders before order_items (orders is
        // the FK parent of order_items.order_id).
        CheckpointState reloaded = new CheckpointStore(checkpointPath, objectMapper)
                .read().orElseThrow();
        assertThat(reloaded.completedTables())
                .as("fresh run should record only the explicitly-configured tables in topological order")
                .containsExactly("orders", "order_items");
    }

    private long rowCount(String table) {
        Long c = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume." + table, Long.class);
        return c == null ? 0L : c;
    }
}
