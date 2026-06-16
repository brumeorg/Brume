package com.fungle.brume.writer;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.github.luben.zstd.ZstdInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for {@link SqlFileSink#writingToFile(Path, CompressionType, SchemaReplicator)}
 * — verifies the compression chain dispatch and the resulting file's decompressed
 * content. Uses mocked {@link SchemaReplicator} to avoid the {@code pg_dump} subprocess.
 */
class SqlFileSinkCompressionTest {

    @TempDir
    Path tempDir;

    private SchemaReplicator schemaReplicator;
    private WriteContext context;

    @BeforeEach
    void setUp() throws Exception {
        schemaReplicator = Mockito.mock(SchemaReplicator.class);
        Mockito.when(schemaReplicator.dumpSchema(eq("schema_a")))
                .thenReturn("CREATE SCHEMA schema_a;\nCREATE TABLE schema_a.t (id INT);\n");

        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 100, Collections.emptyList()),
                new AnonymizationConfig(Collections.emptyList(), Collections.emptyList()));
        context = new WriteContext(
                "schema_a",
                config,
                new DatabaseSchema(new HashMap<>()),
                new ExecutionReport("schema_a", "schema_a"));
    }

    private static ExtractedRow row(String table, Object... kvPairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            data.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return new ExtractedRow(table, data);
    }

    private void writeSampleDump(Path target, CompressionType compression) {
        SqlFileSink sink = SqlFileSink.writingToFile(target, compression, schemaReplicator);
        sink.open(context);
        sink.writeChunk("t", List.of(
                row("t", "id", 1L),
                row("t", "id", 2L),
                row("t", "id", 3L)));
        sink.close();
    }

    private static String decompressAsString(Path file, CompressionType compression) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             InputStream decompressed = openDecompressing(raw, compression)) {
            return new String(decompressed.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream openDecompressing(InputStream raw, CompressionType compression) throws IOException {
        return switch (compression) {
            case NONE -> raw;
            case GZIP -> new GZIPInputStream(raw);
            case ZSTD -> new ZstdInputStream(raw);
        };
    }

    @Test
    @DisplayName("writingToFile() performs no I/O until open() is called (read-only command guard)")
    void writingToFileIsLazyUntilOpen() {
        Path file = tempDir.resolve("never-opened.sql");

        SqlFileSink sink = SqlFileSink.writingToFile(file, CompressionType.NONE, schemaReplicator);

        assertThat(Files.exists(file))
                .as("Sink construction must not create the output file — would clobber a previous dump in read-only commands")
                .isFalse();

        // close() before open() must be a safe no-op — never opened, nothing to flush
        sink.close();
        assertThat(Files.exists(file))
                .as("close() before open() must remain a no-op")
                .isFalse();
    }

    @Test
    @DisplayName("NONE: file contains plain UTF-8 SQL")
    void noneProducesPlainSql() throws Exception {
        Path file = tempDir.resolve("dump.sql");
        writeSampleDump(file, CompressionType.NONE);

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content)
                .contains("-- Brume dump")
                .contains("CREATE SCHEMA schema_a;")
                .contains("COPY \"schema_a\".\"t\" (\"id\") FROM stdin;")
                .contains("1\n2\n3\n");
    }

    @Test
    @DisplayName("GZIP: file is valid gzip and decompresses to expected SQL")
    void gzipProducesValidGzipFile() throws Exception {
        Path file = tempDir.resolve("dump.sql.gz");
        writeSampleDump(file, CompressionType.GZIP);

        // gzip magic bytes
        byte[] head = Files.readAllBytes(file);
        assertThat(head[0] & 0xFF).as("gzip magic byte 0").isEqualTo(0x1F);
        assertThat(head[1] & 0xFF).as("gzip magic byte 1").isEqualTo(0x8B);

        String content = decompressAsString(file, CompressionType.GZIP);
        assertThat(content)
                .contains("-- Brume dump")
                .contains("CREATE SCHEMA schema_a;")
                .contains("COPY \"schema_a\".\"t\" (\"id\") FROM stdin;");
    }

    @Test
    @DisplayName("ZSTD: file is valid zstd and decompresses to expected SQL")
    void zstdProducesValidZstdFile() throws Exception {
        Path file = tempDir.resolve("dump.sql.zst");
        writeSampleDump(file, CompressionType.ZSTD);

        // zstd magic number 0x28B52FFD (little-endian on disk: FD 2F B5 28)
        byte[] head = Files.readAllBytes(file);
        assertThat(head[0] & 0xFF).as("zstd magic byte 0").isEqualTo(0x28);
        assertThat(head[1] & 0xFF).as("zstd magic byte 1").isEqualTo(0xB5);
        assertThat(head[2] & 0xFF).as("zstd magic byte 2").isEqualTo(0x2F);
        assertThat(head[3] & 0xFF).as("zstd magic byte 3").isEqualTo(0xFD);

        String content = decompressAsString(file, CompressionType.ZSTD);
        assertThat(content)
                .contains("-- Brume dump")
                .contains("CREATE SCHEMA schema_a;")
                .contains("COPY \"schema_a\".\"t\" (\"id\") FROM stdin;");
    }

    @Test
    @DisplayName("GZIP ratio sanity: redundant 1000-row dataset compresses to <= 1/3 of plain size")
    void gzipRatioSanityCheck() throws Exception {
        Path plain = tempDir.resolve("ratio-plain.sql");
        Path gz = tempDir.resolve("ratio.sql.gz");

        // Build a sink writer for both, with a redundant payload (same name on every row)
        Mockito.when(schemaReplicator.dumpSchema(eq("schema_a")))
                .thenReturn("CREATE TABLE schema_a.t (id INT, name TEXT);\n");

        List<ExtractedRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(row("t", "id", (long) i, "name", "constant_repeated_value"));
        }

        SqlFileSink plainSink = SqlFileSink.writingToFile(plain, CompressionType.NONE, schemaReplicator);
        plainSink.open(context);
        plainSink.writeChunk("t", rows);
        plainSink.close();

        SqlFileSink gzSink = SqlFileSink.writingToFile(gz, CompressionType.GZIP, schemaReplicator);
        gzSink.open(context);
        gzSink.writeChunk("t", rows);
        gzSink.close();

        long plainSize = Files.size(plain);
        long gzSize = Files.size(gz);
        double ratio = (double) plainSize / gzSize;

        assertThat(ratio)
                .as("gzip ratio (plain=%d, gz=%d)", plainSize, gzSize)
                .isGreaterThanOrEqualTo(3.0);
    }

    @Test
    @DisplayName("ZSTD ratio sanity: redundant 1000-row dataset compresses to <= 1/4 of plain size")
    void zstdRatioSanityCheck() throws Exception {
        Path plain = tempDir.resolve("ratio-plain.sql");
        Path zst = tempDir.resolve("ratio.sql.zst");

        Mockito.when(schemaReplicator.dumpSchema(eq("schema_a")))
                .thenReturn("CREATE TABLE schema_a.t (id INT, name TEXT);\n");

        List<ExtractedRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(row("t", "id", (long) i, "name", "constant_repeated_value"));
        }

        SqlFileSink plainSink = SqlFileSink.writingToFile(plain, CompressionType.NONE, schemaReplicator);
        plainSink.open(context);
        plainSink.writeChunk("t", rows);
        plainSink.close();

        SqlFileSink zstSink = SqlFileSink.writingToFile(zst, CompressionType.ZSTD, schemaReplicator);
        zstSink.open(context);
        zstSink.writeChunk("t", rows);
        zstSink.close();

        long plainSize = Files.size(plain);
        long zstSize = Files.size(zst);
        double ratio = (double) plainSize / zstSize;

        assertThat(ratio)
                .as("zstd ratio (plain=%d, zst=%d)", plainSize, zstSize)
                .isGreaterThanOrEqualTo(4.0);
    }
}
