package com.fungle.brume.writer;

import com.fungle.brume.agent.ReplicationAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip integration tests for {@link TsvEscape} — feeds escaped values to a
 * real PostgreSQL instance via {@code COPY FROM stdin} and verifies the database
 * returns the original input.
 *
 * <p>The TEXT-format spec is brittle: a malformed escape does not error, it
 * silently mutates the data. This IT validates the contract against the
 * PostgreSQL parser itself rather than relying solely on byte-for-byte unit
 * tests.
 *
 * <p><strong>Requires Docker infrastructure</strong> — run {@code docker-compose
 * up -d} first. Uses the target DB on {@code localhost:5460}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class TsvEscapeIT {

    /** Mocked to prevent CommandLineRunner from executing the full pipeline on context startup. */
    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    @Qualifier("targetDataSource")
    private DataSource targetDataSource;

    private JdbcTemplate targetJdbc;

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void ensureSchemaAndTable() {
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS test_brume");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.tsv_escape_roundtrip");
        targetJdbc.execute("""
                CREATE TABLE test_brume.tsv_escape_roundtrip (
                    id    BIGINT PRIMARY KEY,
                    value TEXT
                )
                """);
    }

    @AfterEach
    void dropTable() {
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.tsv_escape_roundtrip");
    }

    /**
     * Sends one row through {@code COPY FROM stdin} and returns the value PostgreSQL
     * stored back. {@code id} keeps rows distinguishable when several cases share a
     * test class instance (one per test thanks to {@code @BeforeEach} drop/recreate).
     */
    private Object copyAndReadBack(long id, Object value) throws Exception {
        StringBuilder line = new StringBuilder();
        line.append(id).append('\t');
        TsvEscape.escape(line, value);
        line.append('\n');

        try (Connection conn = targetDataSource.getConnection()) {
            CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
            copyManager.copyIn(
                    "COPY test_brume.tsv_escape_roundtrip (id, value) FROM stdin",
                    new StringReader(line.toString()));
        }

        return targetJdbc.queryForObject(
                "SELECT value FROM test_brume.tsv_escape_roundtrip WHERE id = ?",
                Object.class, id);
    }

    @Test
    @DisplayName("Plain ASCII round-trips unchanged")
    void plainAsciiRoundTrips() throws Exception {
        Object got = copyAndReadBack(1L, "Hello, World!");
        assertThat(got).isEqualTo("Hello, World!");
    }

    @Test
    @DisplayName("Backslash round-trips unchanged")
    void backslashRoundTrips() throws Exception {
        Object got = copyAndReadBack(2L, "C:\\path\\to\\file");
        assertThat(got).isEqualTo("C:\\path\\to\\file");
    }

    @Test
    @DisplayName("Newline round-trips unchanged")
    void newlineRoundTrips() throws Exception {
        Object got = copyAndReadBack(3L, "first line\nsecond line");
        assertThat(got).isEqualTo("first line\nsecond line");
    }

    @Test
    @DisplayName("Carriage return round-trips unchanged")
    void carriageReturnRoundTrips() throws Exception {
        Object got = copyAndReadBack(4L, "first line\rsecond line");
        assertThat(got).isEqualTo("first line\rsecond line");
    }

    @Test
    @DisplayName("Tab round-trips unchanged")
    void tabRoundTrips() throws Exception {
        Object got = copyAndReadBack(5L, "a\tb\tc");
        assertThat(got).isEqualTo("a\tb\tc");
    }

    @Test
    @DisplayName("NULL → \\N → SQL NULL after restoration")
    void nullRoundTripsAsSqlNull() throws Exception {
        Object got = copyAndReadBack(6L, null);
        assertThat(got).isNull();
    }

    @Test
    @DisplayName("UTF-8 multi-byte round-trips unchanged")
    void utf8RoundTrips() throws Exception {
        Object got = copyAndReadBack(7L, "café — 日本語 — éàïøù");
        assertThat(got).isEqualTo("café — 日本語 — éàïøù");
    }

    @Test
    @DisplayName("Empty string round-trips as empty (not NULL)")
    void emptyStringRoundTrips() throws Exception {
        Object got = copyAndReadBack(8L, "");
        assertThat(got).isEqualTo("");
    }

    @Test
    @DisplayName("Combined escapes (mixed) round-trip unchanged")
    void combinedEscapesRoundTrip() throws Exception {
        String input = "line1\nline2\twith\\backslash\rand\ttab";
        Object got = copyAndReadBack(9L, input);
        assertThat(got).isEqualTo(input);
    }

    @Test
    @DisplayName("Multiple values in a single COPY block all round-trip")
    void multipleRowsRoundTrip() throws Exception {
        StringBuilder buf = new StringBuilder();
        List<String> values = List.of("plain", "with\ttab", "with\nnewline", "with\\backslash");
        long base = 100L;
        for (int i = 0; i < values.size(); i++) {
            buf.append(base + i).append('\t');
            TsvEscape.escape(buf, values.get(i));
            buf.append('\n');
        }

        try (Connection conn = targetDataSource.getConnection()) {
            CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
            copyManager.copyIn(
                    "COPY test_brume.tsv_escape_roundtrip (id, value) FROM stdin",
                    new StringReader(buf.toString()));
        }

        for (int i = 0; i < values.size(); i++) {
            Object got = targetJdbc.queryForObject(
                    "SELECT value FROM test_brume.tsv_escape_roundtrip WHERE id = ?",
                    Object.class, base + i);
            assertThat(got).as("row %d", base + i).isEqualTo(values.get(i));
        }
    }
}
