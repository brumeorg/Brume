package com.fungle.brume.agent;

import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.writer.NullSink;
import com.fungle.brume.writer.Sink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the dry-run mode (#6, A18).
 *
 * <p>Configures {@code brume.sink.type=NULL} so that {@link NullSink} is wired by
 * {@code SinkConfig}, then invokes {@link ReplicationAgent#run(CommandEnum)} with
 * {@link CommandEnum#DRY_RUN}. Verifies that:
 * <ul>
 *   <li>The wired {@link Sink} is a {@link NullSink}.</li>
 *   <li>A sentinel row pre-inserted in the target survives the run — proving that
 *       {@code SchemaReplicator.replicate} (which would {@code DROP SCHEMA … CASCADE})
 *       was not invoked.</li>
 *   <li>The pipeline ran end-to-end (source extraction worked, NullSink counted
 *       rows): the run completes without throwing.</li>
 * </ul>
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=NULL",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class DryRunIT {

    private static final String SENTINEL_SCHEMA = "dry_run_sentinel";

    @Autowired
    private ReplicationAgent agent;

    @Autowired
    private Sink sink;

    /**
     * Standalone JdbcTemplate — with sink.type=NULL, the {@code targetDataSource} bean is
     * not created (#6b, ADR-0028), so this IT wires its own target connection from the
     * known test creds.
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
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS " + SENTINEL_SCHEMA);
        targetJdbc.execute("DROP TABLE IF EXISTS " + SENTINEL_SCHEMA + ".marker");
        targetJdbc.execute("CREATE TABLE " + SENTINEL_SCHEMA + ".marker (id INT PRIMARY KEY)");
        targetJdbc.update("INSERT INTO " + SENTINEL_SCHEMA + ".marker (id) VALUES (?)", 42);
    }

    @AfterEach
    void cleanupSentinel() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS " + SENTINEL_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Sink wired by Spring is NullSink when brume.sink.type=NULL")
    void sinkIsNullSink() {
        assertThat(sink).isInstanceOf(NullSink.class);
    }

    @Test
    @DisplayName("Dry-run leaves the target untouched (sentinel row preserved)")
    void dryRunDoesNotTouchTarget() throws Exception {
        agent.run(CommandEnum.DRY_RUN);

        // Sentinel must still be present — proves SchemaReplicator.replicate was skipped
        Integer sentinel = targetJdbc.queryForObject(
                "SELECT id FROM " + SENTINEL_SCHEMA + ".marker WHERE id = 42",
                Integer.class);
        assertThat(sentinel)
                .as("Pre-existing sentinel must be intact — dry-run must not invoke DROP SCHEMA")
                .isEqualTo(42);
    }
}
