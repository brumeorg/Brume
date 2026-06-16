package com.fungle.brume.writer;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Round-trip integration tests for {@link SqlFileSink} — produces a dump file
 * via the sink, restores it through {@code psql -f} (executed inside the
 * {@code brume-target} Docker container), then verifies the resulting rows via
 * JDBC.
 *
 * <p><strong>Requires Docker infrastructure</strong> — run {@code docker-compose
 * up -d} first. Uses the target DB on {@code localhost:5460} and shells out to
 * {@code docker exec brume-target psql} to apply the dump.
 *
 * <p>The DDL block of the dump is mocked (controlled string returned by a
 * {@link MockitoBean} {@link SchemaReplicator}) — this isolates the format test
 * from {@code pg_dump} availability.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class SqlFileSinkIT {

    private static final String IT_SCHEMA = "sql_file_sink_it";

    @MockitoBean
    private ReplicationAgent replicationAgent;

    @MockitoBean
    private SchemaReplicator schemaReplicator;

    @Autowired
    @Qualifier("targetDataSource")
    private DataSource targetDataSource;

    private JdbcTemplate targetJdbc;
    private Path dumpPath;

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void setUp() throws Exception {
        targetJdbc.execute("DROP SCHEMA IF EXISTS " + IT_SCHEMA + " CASCADE");
        dumpPath = Files.createTempFile("brume-sqlfilesink-it-", ".sql");
    }

    @AfterEach
    void tearDown() throws Exception {
        targetJdbc.execute("DROP SCHEMA IF EXISTS " + IT_SCHEMA + " CASCADE");
        if (dumpPath != null) {
            Files.deleteIfExists(dumpPath);
        }
    }

    private WriteContext context() {
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 100, Collections.emptyList()),
                new AnonymizationConfig(Collections.emptyList(), Collections.emptyList()));
        return new WriteContext(
                IT_SCHEMA,
                config,
                new DatabaseSchema(new HashMap<>()),
                new ExecutionReport(IT_SCHEMA, IT_SCHEMA));
    }

    private void mockDdl(String ddl) throws Exception {
        Mockito.when(schemaReplicator.dumpSchema(eq(IT_SCHEMA))).thenReturn(ddl);
    }

    private static ExtractedRow row(String table, Object... kvPairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            data.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return new ExtractedRow(table, data);
    }

    /**
     * Pipes the dump file to {@code docker exec brume-target psql} on stdin and
     * waits for completion. Asserts a zero exit code so format errors surface
     * the {@code psql} stderr.
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

        try (OutputStream stdin = process.getOutputStream()) {
            Files.copy(dumpPath, stdin);
        }

        StringBuilder output = new StringBuilder();
        try (var reader = process.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                output.append(new String(buf, 0, n));
            }
        }

        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException("psql restore timed out after 60s");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "psql restore failed (exit " + exitCode + "). Output:\n" + output);
        }
    }

    @Test
    @DisplayName("Single-table dump round-trips: rows present in target after psql restore")
    void singleTableRoundTrip() throws Exception {
        mockDdl("""
                CREATE SCHEMA %s;
                CREATE TABLE %s.foo (id BIGINT PRIMARY KEY, name TEXT NOT NULL);
                """.formatted(IT_SCHEMA, IT_SCHEMA));

        try (OutputStream fos = Files.newOutputStream(dumpPath)) {
            SqlFileSink sink = new SqlFileSink(fos, schemaReplicator);
            sink.open(context());
            sink.writeChunk("foo", List.of(
                    row("foo", "id", 1L, "name", "alice"),
                    row("foo", "id", 2L, "name", "bob")));
            sink.close();
        }

        restoreDumpViaPsql();

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + IT_SCHEMA + ".foo", Integer.class);
        assertThat(count).isEqualTo(2);

        String aliceName = targetJdbc.queryForObject(
                "SELECT name FROM " + IT_SCHEMA + ".foo WHERE id = 1", String.class);
        assertThat(aliceName).isEqualTo("alice");
    }

    @Test
    @DisplayName("Multi-table dump round-trips with FK cycles bypassed by session_replication_role")
    void multiTableWithCyclicFk() throws Exception {
        mockDdl("""
                CREATE SCHEMA %s;
                CREATE TABLE %s.users (id BIGINT PRIMARY KEY, manager_id BIGINT REFERENCES %s.users(id));
                CREATE TABLE %s.orders (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES %s.users(id));
                """.formatted(IT_SCHEMA, IT_SCHEMA, IT_SCHEMA, IT_SCHEMA, IT_SCHEMA));

        try (OutputStream fos = Files.newOutputStream(dumpPath)) {
            SqlFileSink sink = new SqlFileSink(fos, schemaReplicator);
            sink.open(context());
            // Insert an order BEFORE its parent user — possible only because session_replication_role='replica'
            sink.writeChunk("orders", List.of(row("orders", "id", 100L, "user_id", 1L)));
            sink.writeChunk("users", List.of(
                    row("users", "id", 1L, "manager_id", 2L),  // cyclic — manager_id 2 also unknown when this row inserted
                    row("users", "id", 2L, "manager_id", 1L)));
            sink.close();
        }

        restoreDumpViaPsql();

        Integer userCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + IT_SCHEMA + ".users", Integer.class);
        Integer orderCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + IT_SCHEMA + ".orders", Integer.class);
        assertThat(userCount).isEqualTo(2);
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Special characters (tab, newline, backslash, NULL) round-trip unchanged")
    void specialCharactersRoundTrip() throws Exception {
        mockDdl("""
                CREATE SCHEMA %s;
                CREATE TABLE %s.payloads (id BIGINT PRIMARY KEY, value TEXT);
                """.formatted(IT_SCHEMA, IT_SCHEMA));

        try (OutputStream fos = Files.newOutputStream(dumpPath)) {
            SqlFileSink sink = new SqlFileSink(fos, schemaReplicator);
            sink.open(context());
            sink.writeChunk("payloads", List.of(
                    row("payloads", "id", 1L, "value", "with\ttab"),
                    row("payloads", "id", 2L, "value", "with\nnewline"),
                    row("payloads", "id", 3L, "value", "with\\backslash"),
                    row("payloads", "id", 4L, "value", null),
                    row("payloads", "id", 5L, "value", "café — 日本語")));
            sink.close();
        }

        restoreDumpViaPsql();

        assertThat(targetJdbc.queryForObject(
                "SELECT value FROM " + IT_SCHEMA + ".payloads WHERE id = 1", String.class))
                .isEqualTo("with\ttab");
        assertThat(targetJdbc.queryForObject(
                "SELECT value FROM " + IT_SCHEMA + ".payloads WHERE id = 2", String.class))
                .isEqualTo("with\nnewline");
        assertThat(targetJdbc.queryForObject(
                "SELECT value FROM " + IT_SCHEMA + ".payloads WHERE id = 3", String.class))
                .isEqualTo("with\\backslash");
        assertThat(targetJdbc.queryForObject(
                "SELECT value FROM " + IT_SCHEMA + ".payloads WHERE id = 4", String.class))
                .isNull();
        assertThat(targetJdbc.queryForObject(
                "SELECT value FROM " + IT_SCHEMA + ".payloads WHERE id = 5", String.class))
                .isEqualTo("café — 日本語");
    }

    @Test
    @DisplayName("Empty dump (no chunks) restores schema with no rows")
    void emptyDumpRestoresSchemaOnly() throws Exception {
        mockDdl("""
                CREATE SCHEMA %s;
                CREATE TABLE %s.empty_table (id BIGINT PRIMARY KEY);
                """.formatted(IT_SCHEMA, IT_SCHEMA));

        try (OutputStream fos = Files.newOutputStream(dumpPath)) {
            SqlFileSink sink = new SqlFileSink(fos, schemaReplicator);
            sink.open(context());
            sink.close();
        }

        restoreDumpViaPsql();

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + IT_SCHEMA + ".empty_table", Integer.class);
        assertThat(count).isZero();
    }
}
