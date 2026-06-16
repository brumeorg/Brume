package com.fungle.brume.writer;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for {@link SqlFileSink} — verify the produced bytes against a
 * captured {@link ByteArrayOutputStream}, with {@link SchemaReplicator} mocked
 * to avoid the {@code pg_dump} subprocess.
 */
class SqlFileSinkTest {

    private ByteArrayOutputStream out;
    private SchemaReplicator schemaReplicator;
    private SqlFileSink sink;
    private WriteContext context;

    @BeforeEach
    void setUp() throws Exception {
        out = new ByteArrayOutputStream();
        schemaReplicator = Mockito.mock(SchemaReplicator.class);
        Mockito.when(schemaReplicator.dumpSchema(eq("test_schema")))
                .thenReturn("CREATE SCHEMA test_schema;\nCREATE TABLE test_schema.foo (id INT);\n");

        sink = new SqlFileSink(out, schemaReplicator);

        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 100, Collections.emptyList()),
                new AnonymizationConfig(Collections.emptyList(), Collections.emptyList()));
        context = new WriteContext(
                "test_schema",
                config,
                new DatabaseSchema(new HashMap<>()),
                new ExecutionReport("test_schema", "test_schema"));
    }

    private String dumpAsString() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static ExtractedRow row(String table, Object... kvPairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            data.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return new ExtractedRow(table, data);
    }

    @Test
    @DisplayName("Header includes timestamp, version and schema names")
    void headerContainsMetadata() {
        sink.open(context);
        sink.close();

        String dump = dumpAsString();
        assertThat(dump)
                .contains("-- Brume dump")
                .contains("-- brume_version: 1.0.0")
                .contains("-- source_schema: test_schema")
                .contains("-- target_schema: test_schema")
                .contains("-- generated_at: ");
    }

    @Test
    @DisplayName("#25d — stripTimestamps=true omits the generated_at header line")
    void headerOmitsGeneratedAtWhenStripTimestampsIsTrue() throws Exception {
        // Re-construct the sink with stripTimestamps=true (the @BeforeEach default is false).
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        SqlFileSink strippingSink = new SqlFileSink(out2, schemaReplicator, true);

        strippingSink.open(context);
        strippingSink.close();

        String dump = out2.toString(StandardCharsets.UTF_8);
        assertThat(dump)
                .as("with --strip-timestamps, the generated_at line must NOT appear")
                .doesNotContain("-- generated_at:");
        // Other header lines remain so the dump is still self-describing.
        assertThat(dump)
                .contains("-- Brume dump")
                .contains("-- brume_version: 1.0.0")
                .contains("-- source_schema: test_schema");
    }

    @Test
    @DisplayName("#25d — default header still carries the generated_at line (opt-in only)")
    void headerKeepsGeneratedAtByDefault() {
        sink.open(context);
        sink.close();
        assertThat(dumpAsString()).contains("-- generated_at: ");
    }

    @Test
    @DisplayName("DDL block from SchemaReplicator is written verbatim")
    void ddlBlockIsWritten() {
        sink.open(context);
        sink.close();

        String dump = dumpAsString();
        assertThat(dump)
                .contains("-- DDL block (pg_dump --schema-only)")
                .contains("CREATE SCHEMA test_schema;")
                .contains("CREATE TABLE test_schema.foo (id INT);");
    }

    @Test
    @DisplayName("session_replication_role wraps the data section")
    void sessionReplicationRoleIsSetAndReset() {
        sink.open(context);
        sink.close();

        String dump = dumpAsString();
        assertThat(dump)
                .contains("SET session_replication_role = 'replica';")
                .contains("SET session_replication_role = 'origin';");
        // 'replica' must come before 'origin'
        assertThat(dump.indexOf("'replica'"))
                .isLessThan(dump.indexOf("'origin'"));
    }

    @Test
    @DisplayName("First chunk opens a COPY block with quoted schema, table and columns")
    void copyBlockOpensCorrectly() {
        sink.open(context);
        sink.writeChunk("foo", List.of(row("foo", "id", 1L, "name", "alice")));
        sink.close();

        String dump = dumpAsString();
        assertThat(dump).contains("COPY \"test_schema\".\"foo\" (\"id\", \"name\") FROM stdin;");
    }

    @Test
    @DisplayName("Single row writes tab-separated, newline-terminated values")
    void singleRowEmitsTsv() {
        sink.open(context);
        sink.writeChunk("foo", List.of(row("foo", "id", 1L, "name", "alice")));
        sink.close();

        String dump = dumpAsString();
        assertThat(dump).contains("1\talice\n");
    }

    @Test
    @DisplayName("Multiple rows in one chunk all emitted, in order")
    void multipleRowsInOneChunk() {
        sink.open(context);
        sink.writeChunk("foo", List.of(
                row("foo", "id", 1L, "name", "alice"),
                row("foo", "id", 2L, "name", "bob"),
                row("foo", "id", 3L, "name", "carol")));
        sink.close();

        String dump = dumpAsString();
        assertThat(dump).contains("1\talice\n2\tbob\n3\tcarol\n");
    }

    @Test
    @DisplayName("Multiple chunks of the same table share a single COPY block")
    void multipleChunksSameTableSingleCopyBlock() {
        sink.open(context);
        sink.writeChunk("foo", List.of(row("foo", "id", 1L, "name", "alice")));
        sink.writeChunk("foo", List.of(row("foo", "id", 2L, "name", "bob")));
        sink.close();

        String dump = dumpAsString();
        long copyOpenCount = dump.lines().filter(l -> l.startsWith("COPY ")).count();
        assertThat(copyOpenCount)
                .as("Only one COPY block should be opened across two chunks of the same table")
                .isEqualTo(1);
        assertThat(dump).contains("1\talice\n2\tbob\n");
    }

    @Test
    @DisplayName("Switching tables closes the previous COPY block and opens a new one")
    void tableBoundaryTriggersNewCopyBlock() throws Exception {
        Mockito.when(schemaReplicator.dumpSchema(eq("test_schema"))).thenReturn("DDL\n");

        sink.open(context);
        sink.writeChunk("foo", List.of(row("foo", "id", 1L)));
        sink.writeChunk("bar", List.of(row("bar", "label", "x")));
        sink.close();

        String dump = dumpAsString();
        // Expected order: COPY foo / 1 / \. / COPY bar / x / \. / SET origin
        int copyFoo = dump.indexOf("COPY \"test_schema\".\"foo\"");
        int dot1 = dump.indexOf("\\.\n", copyFoo);
        int copyBar = dump.indexOf("COPY \"test_schema\".\"bar\"");
        int dot2 = dump.indexOf("\\.\n", copyBar);

        assertThat(copyFoo).as("COPY foo present").isPositive();
        assertThat(dot1).as("first \\. terminator after foo").isGreaterThan(copyFoo);
        assertThat(copyBar).as("COPY bar after first \\.").isGreaterThan(dot1);
        assertThat(dot2).as("second \\. terminator after bar").isGreaterThan(copyBar);
    }

    @Test
    @DisplayName("Empty rows list does not emit a COPY block")
    void emptyRowsSkipped() {
        sink.open(context);
        sink.writeChunk("foo", List.of());
        sink.close();

        String dump = dumpAsString();
        assertThat(dump)
                .as("No COPY ... FROM stdin block should be emitted for an empty chunk")
                .doesNotContain("COPY ");
    }

    @Test
    @DisplayName("Trailer is written at close")
    void trailerIsWritten() {
        sink.open(context);
        sink.close();

        String dump = dumpAsString();
        assertThat(dump).contains("-- Brume dump end");
    }

    @Test
    @DisplayName("NULL values in row are escaped as \\N")
    void nullValuesAreEscapedAsBackslashN() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", 1L);
        data.put("name", null);

        sink.open(context);
        sink.writeChunk("foo", List.of(new ExtractedRow("foo", data)));
        sink.close();

        String dump = dumpAsString();
        assertThat(dump).contains("1\t\\N\n");
    }

    @Test
    @DisplayName("writeChunk before open() throws IllegalStateException")
    void writeBeforeOpenIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> sink.writeChunk("foo", List.of(row("foo", "id", 1L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before open()");
    }

    @Test
    @DisplayName("#79d — open() closes openedChain when writeDdl fails (no descriptor leak)")
    void openClosesChainIfWriteDdlFails(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("dump.sql");

        SchemaReplicator failing = Mockito.mock(SchemaReplicator.class);
        Mockito.when(failing.dumpSchema(eq("test_schema")))
                .thenThrow(new RuntimeException("pg_dump unavailable"));
        SqlFileSink leakingSink = SqlFileSink.writingToFile(output, CompressionType.NONE, failing);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> leakingSink.open(context))
                .isInstanceOf(com.fungle.brume.error.WriteException.class)
                .hasMessageContaining("failed during open()");

        // Pre-fix: on Windows, the FileOutputStream stayed open and the next open() on the
        // same path threw FileSystemException ("used by another process"). Post-fix, the
        // chain is closed in the catch block — re-opening must succeed.
        try (OutputStream ignored = Files.newOutputStream(output, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Empty body — just asserting the open succeeds.
        }
    }
}
