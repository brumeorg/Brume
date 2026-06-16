package com.fungle.brume.scenario;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.replicator.SchemaReplicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J1 — Plan-mode safety post-execute (regression guard for #6c).
 *
 * <p>User journey : a user configures the DUMP sink, runs {@code brume execute}
 * to produce a dump, then later runs {@code brume plan} to preview a future
 * extraction. The previously produced dump file MUST survive untouched.
 *
 * <p>Before #6c (commit ed7a5cb), {@code SqlFileSink.writingToFile(...)} eagerly
 * called {@code Files.newOutputStream(outputPath)} at Spring bean creation,
 * which truncated any pre-existing file before the pipeline even dispatched.
 * In plan mode the pipeline exits before {@code sink.open()} / {@code close()}
 * are ever called → empty file, gzip/zstd trailer never written.
 *
 * <p>The fix landed in two layers : (a) {@code applyReadOnlySinkOverride}
 * forces {@code sink.type=NULL} for the {@code plan} subcommand
 * ({@link com.fungle.brume.BrumeApplication}), and (b) {@code SqlFileSink}
 * now opens its file lazily inside {@code open()}. This IT validates layer
 * (b) directly : with {@code sink.type=DUMP} forced throughout (no override),
 * the dump file produced by {@code execute} is byte-identical after a
 * subsequent {@code plan} run.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose
 * up -d}. {@link SchemaReplicator} is mocked so the test pipeline does not depend
 * on the {@code pg_dump} binary; the #19/A13 preflight runner does check pg_dump
 * upfront though, so we point {@code replication.pgdump-path} at the binary inside
 * the {@code brume-source} container.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=DUMP",
        "brume.sink.compression=NONE",
        "brume.sink.output-path=target/scenario-its/j1-dump.sql",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump"
})
@DirtiesContext
class PlanModePostExecuteIT {

    private static final String SENTINEL_SCHEMA = "j1_sentinel";

    @MockitoBean
    private SchemaReplicator schemaReplicator;

    @Autowired
    private ReplicationAgent agent;

    @Autowired
    private BrumeProperties brumeProperties;

    /**
     * Standalone JdbcTemplate — with sink.type=DUMP, the {@code targetDataSource} bean is
     * not created (#6b, ADR-0028), so this IT wires its own target connection from the
     * known test creds. The sentinel schema lives in the target DB to prove that plan/execute
     * write paths do not leak writes into the target.
     */
    private final JdbcTemplate targetJdbc = standaloneTargetJdbc();
    private Path dumpPath;

    private static JdbcTemplate standaloneTargetJdbc() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new org.postgresql.Driver());
        ds.setUrl("jdbc:postgresql://localhost:5460/postgres");
        ds.setUsername("postgres");
        ds.setPassword("postgres");
        return new JdbcTemplate(ds);
    }

    @BeforeEach
    void setup() throws Exception {
        Mockito.when(schemaReplicator.dumpSchema(Mockito.anyString()))
                .thenReturn("-- mocked DDL for scenario J1\n");

        dumpPath = Path.of(brumeProperties.sink().outputPath());
        if (dumpPath.getParent() != null) {
            Files.createDirectories(dumpPath.getParent());
        }
        Files.deleteIfExists(dumpPath);

        targetJdbc.execute("DROP SCHEMA IF EXISTS " + SENTINEL_SCHEMA + " CASCADE");
        targetJdbc.execute("CREATE SCHEMA " + SENTINEL_SCHEMA);
        targetJdbc.execute("CREATE TABLE " + SENTINEL_SCHEMA + ".marker (id INT PRIMARY KEY, label TEXT)");
        targetJdbc.update("INSERT INTO " + SENTINEL_SCHEMA + ".marker VALUES (?, ?)", 1, "alice");
        targetJdbc.update("INSERT INTO " + SENTINEL_SCHEMA + ".marker VALUES (?, ?)", 2, "bob");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dumpPath != null) {
            Files.deleteIfExists(dumpPath);
        }
        targetJdbc.execute("DROP SCHEMA IF EXISTS " + SENTINEL_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("plan after execute leaves the DUMP file byte-identical (regression #6c)")
    void planAfterExecutePreservesDump() throws Exception {
        // Phase 1 — execute produces the dump
        agent.run(CommandEnum.EXECUTE);

        assertThat(Files.exists(dumpPath))
                .as("execute with sink.type=DUMP must produce the dump file")
                .isTrue();
        long sizeAfterExecute = Files.size(dumpPath);
        String checksumAfterExecute = sha256(dumpPath);
        assertThat(sizeAfterExecute)
                .as("dump file must contain header + DDL + COPY blocks + trailer")
                .isPositive();

        // Sentinel snapshot before plan
        Integer markerCountBefore = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SENTINEL_SCHEMA + ".marker", Integer.class);
        Integer markerSumBefore = targetJdbc.queryForObject(
                "SELECT COALESCE(SUM(id), 0) FROM " + SENTINEL_SCHEMA + ".marker", Integer.class);

        // Phase 2 — plan must not modify the dump file or the target
        agent.run(CommandEnum.PLAN);

        assertThat(Files.size(dumpPath))
                .as("plan must not modify the dump file size — regression #6c (commit ed7a5cb)")
                .isEqualTo(sizeAfterExecute);
        assertThat(sha256(dumpPath))
                .as("plan must not modify the dump file content — regression #6c (commit ed7a5cb)")
                .isEqualTo(checksumAfterExecute);

        Integer markerCountAfter = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SENTINEL_SCHEMA + ".marker", Integer.class);
        Integer markerSumAfter = targetJdbc.queryForObject(
                "SELECT COALESCE(SUM(id), 0) FROM " + SENTINEL_SCHEMA + ".marker", Integer.class);
        assertThat(markerCountAfter)
                .as("plan must not delete or insert sentinel rows")
                .isEqualTo(markerCountBefore);
        assertThat(markerSumAfter)
                .as("plan must not modify sentinel rows")
                .isEqualTo(markerSumBefore);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
