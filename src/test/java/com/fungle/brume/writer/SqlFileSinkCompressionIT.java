package com.fungle.brume.writer;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.github.luben.zstd.ZstdInputStream;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Round-trip integration tests for {@link SqlFileSink} with compression — produces
 * compressed dumps, decompresses them in-memory in Java, then pipes the plain SQL
 * to {@code docker exec brume-target psql}.
 *
 * <p>This validates that the compression chain (gzip / zstd) produces files whose
 * decompressed content is a valid {@code psql -f} restorable dump.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class SqlFileSinkCompressionIT {

    private static final String IT_SCHEMA = "sql_file_sink_compress_it";

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

    private static ExtractedRow row(String table, Object... kvPairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            data.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return new ExtractedRow(table, data);
    }

    private void mockDdl(String ddl) throws Exception {
        Mockito.when(schemaReplicator.dumpSchema(eq(IT_SCHEMA))).thenReturn(ddl);
    }

    /**
     * Decompresses {@code dumpPath} (according to {@code compression}) and pipes the
     * plain SQL to {@code docker exec brume-target psql}. Asserts a zero exit code.
     */
    private void restoreCompressedDumpViaPsql(CompressionType compression) throws Exception {
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

        try (InputStream raw = Files.newInputStream(dumpPath);
             InputStream decompressed = switch (compression) {
                 case NONE -> raw;
                 case GZIP -> new GZIPInputStream(raw);
                 case ZSTD -> new ZstdInputStream(raw);
             };
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
    @DisplayName("Gzip dump round-trips: gunzip + psql produces the expected rows")
    void gzipRoundTrip() throws Exception {
        dumpPath = Files.createTempFile("brume-compress-it-", ".sql.gz");
        mockDdl("""
                CREATE SCHEMA %s;
                CREATE TABLE %s.foo (id BIGINT PRIMARY KEY, name TEXT);
                """.formatted(IT_SCHEMA, IT_SCHEMA));

        SqlFileSink sink = SqlFileSink.writingToFile(dumpPath, CompressionType.GZIP, schemaReplicator);
        sink.open(context());
        sink.writeChunk("foo", List.of(
                row("foo", "id", 1L, "name", "alice"),
                row("foo", "id", 2L, "name", "bob")));
        sink.close();

        // Sanity — file is gzipped (magic bytes)
        byte[] head = Files.readAllBytes(dumpPath);
        assertThat(head[0] & 0xFF).isEqualTo(0x1F);
        assertThat(head[1] & 0xFF).isEqualTo(0x8B);

        restoreCompressedDumpViaPsql(CompressionType.GZIP);

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + IT_SCHEMA + ".foo", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Zstd dump round-trips: zstdcat-equivalent + psql produces the expected rows")
    void zstdRoundTrip() throws Exception {
        dumpPath = Files.createTempFile("brume-compress-it-", ".sql.zst");
        mockDdl("""
                CREATE SCHEMA %s;
                CREATE TABLE %s.foo (id BIGINT PRIMARY KEY, name TEXT);
                """.formatted(IT_SCHEMA, IT_SCHEMA));

        SqlFileSink sink = SqlFileSink.writingToFile(dumpPath, CompressionType.ZSTD, schemaReplicator);
        sink.open(context());
        sink.writeChunk("foo", List.of(
                row("foo", "id", 1L, "name", "alice"),
                row("foo", "id", 2L, "name", "bob"),
                row("foo", "id", 3L, "name", "carol")));
        sink.close();

        // Sanity — file is zstd (magic 0x28B52FFD little-endian)
        byte[] head = Files.readAllBytes(dumpPath);
        assertThat(head[0] & 0xFF).isEqualTo(0x28);
        assertThat(head[1] & 0xFF).isEqualTo(0xB5);
        assertThat(head[2] & 0xFF).isEqualTo(0x2F);
        assertThat(head[3] & 0xFF).isEqualTo(0xFD);

        restoreCompressedDumpViaPsql(CompressionType.ZSTD);

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + IT_SCHEMA + ".foo", Integer.class);
        assertThat(count).isEqualTo(3);
    }
}
