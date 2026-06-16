package com.fungle.brume.writer;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link JdbcSink}'s {@link CopyMode#PREFER} and
 * {@link CopyMode#FORCE} write strategies — exercises {@code COPY FROM stdin} via
 * {@link org.postgresql.copy.CopyManager} against the real target database, plus
 * the PREFER fallback path on PK conflicts.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 *
 * <p>{@link CopyMode#NEVER} is already covered by {@link JdbcSinkIT}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class JdbcSinkCopyIT {

    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    @Qualifier("targetDataSource")
    private DataSource targetDataSource;

    @Autowired
    @Qualifier("targetTransactionManager")
    private PlatformTransactionManager targetTransactionManager;

    private JdbcTemplate targetJdbc;

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void ensureSchema() {
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS test_brume");
        targetJdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_brume.products (
                    id          BIGINT PRIMARY KEY,
                    name        VARCHAR(255) NOT NULL,
                    description TEXT,
                    ip_address  VARCHAR(45),
                    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);
        targetJdbc.execute("DELETE FROM test_brume.products WHERE id BETWEEN 9000 AND 9100");
    }

    @AfterEach
    void cleanup() {
        targetJdbc.execute("DELETE FROM test_brume.products WHERE id BETWEEN 9000 AND 9100");
    }

    private static BrumeProperties props(CopyMode copyMode, double maxBatchErrorRate) {
        return new BrumeProperties(
                "config/config.yaml", "hmac", "fpekey1234567890", "HmacSHA256", "fr",
                maxBatchErrorRate,
                0L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""),
                new BrumeProperties.SinkProperties(
                        SinkType.JDBC, null, CompressionType.NONE,
                        new BrumeProperties.JdbcSinkProperties(copyMode)),
                new BrumeProperties.PlanProperties(com.fungle.brume.plan.PlanMode.EXACT)
        );
    }

    private static WriteContext context(ExecutionReport report) {
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 100, Collections.emptyList()),
                new AnonymizationConfig(Collections.emptyList(), Collections.emptyList()));
        return new WriteContext(
                "test_brume",
                config,
                new DatabaseSchema(new HashMap<>()),
                report);
    }

    private static ExtractedRow product(long id, String name) {
        return new ExtractedRow("products", Map.of(
                "id", id,
                "name", name,
                "description", "d",
                "ip_address", "1.1.1.1",
                "created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00")));
    }

    @Test
    @DisplayName("PREFER without conflict: COPY succeeds, rows inserted")
    void preferNoConflict() {
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager,
                props(CopyMode.PREFER, 0.0));
        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(context(report));
        try {
            sink.writeChunk("products", List.of(
                    product(9001L, "alice"),
                    product(9002L, "bob"),
                    product(9003L, "carol")));
        } finally {
            sink.close();
        }

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id BETWEEN 9001 AND 9003",
                Integer.class);
        assertThat(count).isEqualTo(3);

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalInserted()).isEqualTo(3);
        assertThat(summary.totalConflicts()).isZero();
        assertThat(summary.totalBatchErrors()).isZero();
    }

    @Test
    @DisplayName("PREFER with PK conflict: COPY fails, falls back to INSERT, conflicts counted")
    void preferWithConflictFallsBackToInsert() {
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager,
                props(CopyMode.PREFER, 0.0));

        // Pre-insert one row so the second writeChunk hits a PK conflict
        targetJdbc.update(
                "INSERT INTO test_brume.products (id, name, description, ip_address, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                9001L, "preexisting", "d", "1.1.1.1",
                java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(context(report));
        try {
            // batch contains 9001 (conflict) + 9002 (new) — COPY will fail entirely,
            // fallback INSERT will insert 9002 and skip 9001 via ON CONFLICT DO NOTHING
            sink.writeChunk("products", List.of(
                    product(9001L, "from-batch"),
                    product(9002L, "new-row")));
        } finally {
            sink.close();
        }

        // 9001 remains "preexisting" (not overwritten); 9002 is inserted
        String name9001 = targetJdbc.queryForObject(
                "SELECT name FROM test_brume.products WHERE id = 9001", String.class);
        Integer count9002 = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id = 9002", Integer.class);
        assertThat(name9001).isEqualTo("preexisting");
        assertThat(count9002).isEqualTo(1);

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalInserted()).isEqualTo(1);
        assertThat(summary.totalConflicts()).isEqualTo(1);
        assertThat(summary.totalBatchErrors()).isZero();
    }

    @Test
    @DisplayName("FORCE without conflict: COPY succeeds, rows inserted (no conflict counter)")
    void forceNoConflict() {
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager,
                props(CopyMode.FORCE, 0.0));
        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(context(report));
        try {
            sink.writeChunk("products", List.of(
                    product(9010L, "x"),
                    product(9011L, "y")));
        } finally {
            sink.close();
        }

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id IN (9010, 9011)",
                Integer.class);
        assertThat(count).isEqualTo(2);

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalInserted()).isEqualTo(2);
        assertThat(summary.totalBatchErrors()).isZero();
    }

    @Test
    @DisplayName("FORCE with PK conflict: batch error recorded, threshold respected")
    void forceWithConflictRecordsBatchError() {
        // tolerant threshold so the test does not abort the run
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager,
                props(CopyMode.FORCE, 1.0));

        targetJdbc.update(
                "INSERT INTO test_brume.products (id, name, description, ip_address, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                9020L, "preexisting", "d", "1.1.1.1",
                java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(context(report));
        try {
            sink.writeChunk("products", List.of(
                    product(9020L, "from-batch"),
                    product(9021L, "new-row")));
        } finally {
            sink.close();
        }

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalBatchErrors())
                .as("FORCE mode must record a batch error on PK conflict (no fallback)")
                .isEqualTo(1);

        // 9021 must NOT be inserted because the COPY block was aborted entirely
        Integer count9021 = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id = 9021", Integer.class);
        assertThat(count9021).isZero();
    }

    @Test
    @DisplayName("FORCE with PK conflict and threshold=0: throws BatchErrorThresholdExceededException")
    void forceWithConflictAndStrictThresholdThrows() {
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager,
                props(CopyMode.FORCE, 0.0));

        targetJdbc.update(
                "INSERT INTO test_brume.products (id, name, description, ip_address, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                9030L, "preexisting", "d", "1.1.1.1",
                java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(context(report));
        try {
            assertThatThrownBy(() -> sink.writeChunk("products", List.of(product(9030L, "from-batch"))))
                    .isInstanceOf(BatchErrorThresholdExceededException.class);
        } finally {
            sink.close();
        }
    }

    @Test
    @DisplayName("PREFER with NOT NULL violation: COPY and INSERT both fail, batch error recorded")
    void preferWithNotNullViolation() {
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager,
                props(CopyMode.PREFER, 1.0));

        // bad row: name is NULL → violates NOT NULL
        Map<String, Object> badData = new java.util.HashMap<>();
        badData.put("id", 9040L);
        badData.put("name", null);
        badData.put("description", "d");
        badData.put("ip_address", "1.1.1.1");
        badData.put("created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(context(report));
        try {
            sink.writeChunk("products", List.of(new ExtractedRow("products", badData)));
        } finally {
            sink.close();
        }

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalBatchErrors()).isEqualTo(1);

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id = 9040", Integer.class);
        assertThat(count).isZero();
    }
}
