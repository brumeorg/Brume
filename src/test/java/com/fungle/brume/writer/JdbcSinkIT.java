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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link JdbcSink}.
 *
 * <p><strong>Requires Docker infrastructure</strong> — run {@code docker-compose up -d} first:
 * <ul>
 *   <li>Source DB on {@code localhost:5432} with schema {@code test_brume}</li>
 *   <li>Target DB on {@code localhost:5460} — schema created by setup if absent</li>
 * </ul>
 *
 * <p>{@link ReplicationAgent} is mocked to prevent the CommandLineRunner from running
 * the full replication pipeline on context startup.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class JdbcSinkIT {

    /** Mocked to prevent CommandLineRunner from executing the full pipeline on context startup. */
    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    private JdbcSink jdbcSink;

    /** JdbcTemplate bound to the target DB for assertions and cleanup. */
    private JdbcTemplate targetJdbc;

    @Autowired
    @Qualifier("targetDataSource")
    private DataSource targetDataSource;

    @Autowired
    @Qualifier("targetTransactionManager")
    private PlatformTransactionManager targetTransactionManager;

    private static final DatabaseSchema EMPTY_SCHEMA = new DatabaseSchema(new HashMap<>());

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    /**
     * Ensures that the {@code test_brume.products} table exists on the target DB.
     * The target container starts empty; Brume normally runs {@code SchemaReplicator} first,
     * but for this focused IT we create the DDL directly.
     */
    @BeforeEach
    void ensureTargetSchema() {
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
        // Clean up any leftover test rows from previous runs
        targetJdbc.execute(
                "DELETE FROM test_brume.products WHERE id IN (9001, 9002, 9003, 9004)");
    }

    @AfterEach
    void cleanup() {
        targetJdbc.execute(
                "DELETE FROM test_brume.products WHERE id IN (9001, 9002, 9003, 9004)");
    }

    private WriteContext ctx(int batchSize, ExecutionReport report) {
        ExtractionConfig extraction = new ExtractionConfig(3, batchSize, Collections.emptyList());
        AnonymizationConfig anonymization = new AnonymizationConfig(
                Collections.emptyList(), Collections.emptyList());
        AnonymizerConfig config = new AnonymizerConfig(extraction, anonymization);
        return new WriteContext("test_brume", config, EMPTY_SCHEMA, report);
    }

    /**
     * Verifies that {@link JdbcSink#writeChunk} inserts the given rows into the target table
     * and that the rows are retrievable afterwards.
     */
    @Test
    @DisplayName("Should insert 3 rows into test_brume.products and retrieve them")
    void shouldInsertRowsIntoTargetTable() {
        List<ExtractedRow> rows = List.of(
                new ExtractedRow("products", Map.of(
                        "id", 9001L,
                        "name", "Test Product A",
                        "description", "Description A",
                        "ip_address", "127.0.0.1",
                        "created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"))),
                new ExtractedRow("products", Map.of(
                        "id", 9002L,
                        "name", "Test Product B",
                        "description", "Description B",
                        "ip_address", "127.0.0.2",
                        "created_at", java.sql.Timestamp.valueOf("2025-01-02 00:00:00"))),
                new ExtractedRow("products", Map.of(
                        "id", 9003L,
                        "name", "Test Product C",
                        "description", "Description C",
                        "ip_address", "127.0.0.3",
                        "created_at", java.sql.Timestamp.valueOf("2025-01-03 00:00:00")))
        );

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        jdbcSink.open(ctx(100, report));
        try {
            jdbcSink.writeChunk("products", rows);
        } finally {
            jdbcSink.close();
        }

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id IN (9001, 9002, 9003)",
                Integer.class);

        assertThat(count)
                .as("All 3 test rows must be present in the target table")
                .isEqualTo(3);
    }

    /**
     * Verifies that {@link JdbcSink#writeChunk} continues processing subsequent batches when
     * an earlier batch fails due to a NOT NULL constraint violation on the {@code name} column.
     *
     * <p>Uses {@code batchSize=1} so each row is an independent batch and transaction.
     * <p>Uses a custom JdbcSink with {@code maxBatchErrorRate=1.0} to tolerate all errors.
     */
    @Test
    @DisplayName("Should continue after bad batch and insert valid rows")
    void shouldContinueAfterBadBatch() {
        var brumeProps = new BrumeProperties(
                "config/config.yaml", "hmac", "fpekey1234567890", "HmacSHA256", "fr", 1.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        JdbcSink tolerantSink = new JdbcSink(targetDataSource, targetTransactionManager, brumeProps);

        // badRow has null 'name' → violates NOT NULL on products.name → batch will fail
        // Using HashMap to allow null values (Map.of() rejects nulls)
        Map<String, Object> badData = new HashMap<>();
        badData.put("id", 9001L);
        badData.put("name", null);
        badData.put("description", "should fail");
        badData.put("ip_address", "0.0.0.0");
        badData.put("created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));
        ExtractedRow badRow = new ExtractedRow("products", badData);

        ExtractedRow goodRow = new ExtractedRow("products", Map.of(
                "id", 9004L,
                "name", "Good Product",
                "description", "should succeed",
                "ip_address", "10.0.0.1",
                "created_at", java.sql.Timestamp.valueOf("2025-01-04 00:00:00")
        ));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        tolerantSink.open(ctx(1, report));
        try {
            tolerantSink.writeChunk("products", List.of(badRow, goodRow));
        } finally {
            tolerantSink.close();
        }

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id = 9004",
                Integer.class);

        assertThat(count)
                .as("The valid row (id=9004) must be inserted even though the preceding bad batch failed")
                .isEqualTo(1);
    }

    /**
     * Verifies that {@link JdbcSink#writeChunk} records insertion and conflict counts
     * in the {@link ExecutionReport}.
     *
     * <p>First write inserts 3 rows; second write with identical rows produces 3 conflicts
     * (because {@code ON CONFLICT DO NOTHING} skips already-existing rows).
     */
    @Test
    @DisplayName("Should record insertions and conflicts in ExecutionReport")
    void shouldRecordInsertionsAndConflictsInReport() {
        List<ExtractedRow> rows = List.of(
                new ExtractedRow("products", Map.of(
                        "id", 9001L,
                        "name", "Report Test A",
                        "description", "desc A",
                        "ip_address", "1.1.1.1",
                        "created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"))),
                new ExtractedRow("products", Map.of(
                        "id", 9002L,
                        "name", "Report Test B",
                        "description", "desc B",
                        "ip_address", "1.1.1.2",
                        "created_at", java.sql.Timestamp.valueOf("2025-01-02 00:00:00"))),
                new ExtractedRow("products", Map.of(
                        "id", 9003L,
                        "name", "Report Test C",
                        "description", "desc C",
                        "ip_address", "1.1.1.3",
                        "created_at", java.sql.Timestamp.valueOf("2025-01-03 00:00:00")))
        );

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");

        jdbcSink.open(ctx(100, report));
        try {
            jdbcSink.writeChunk("products", rows);
            jdbcSink.writeChunk("products", rows);
        } finally {
            jdbcSink.close();
        }

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));

        assertThat(summary.totalInserted())
                .as("First write must have inserted 3 rows")
                .isEqualTo(3);
        assertThat(summary.totalConflicts())
                .as("Second write must produce 3 conflicts (ON CONFLICT DO NOTHING)")
                .isEqualTo(3);
        assertThat(summary.totalBatchErrors())
                .as("No batch should have failed")
                .isEqualTo(0);
    }

    /**
     * Verifies that {@link JdbcSink} throws {@link BatchErrorThresholdExceededException}
     * when a batch error occurs and {@code maxBatchErrorRate = 0.0} (zero tolerance, default).
     */
    @Test
    @DisplayName("Should throw BatchErrorThresholdExceededException with maxBatchErrorRate = 0.0 (default)")
    void shouldFailFastWithStrictErrorThreshold() {
        var brumeProps = new BrumeProperties(
                "config/config.yaml", "hmac", "fpekey1234567890", "HmacSHA256", "fr", 0.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        JdbcSink strictSink = new JdbcSink(targetDataSource, targetTransactionManager, brumeProps);

        Map<String, Object> badData = new HashMap<>();
        badData.put("id", 9001L);
        badData.put("name", null);
        badData.put("description", "fail");
        badData.put("ip_address", "0.0.0.0");
        badData.put("created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));

        List<ExtractedRow> rows = List.of(new ExtractedRow("products", badData));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        strictSink.open(ctx(1, report));
        try {
            assertThatThrownBy(() -> strictSink.writeChunk("products", rows))
                    .isInstanceOf(BatchErrorThresholdExceededException.class)
                    .hasMessageContaining("Batch error rate exceeded for table 'products'")
                    .hasMessageContaining("1/1 batches failed")
                    .hasMessageContaining("100.00%");
        } finally {
            strictSink.close();
        }
    }

    /**
     * Verifies that {@link JdbcSink} does NOT throw when {@code maxBatchErrorRate = 1.0}
     * (all errors tolerated).
     */
    @Test
    @DisplayName("Should not throw with maxBatchErrorRate = 1.0 (all errors tolerated)")
    void shouldTolerateAllErrorsWithMaxRate() {
        var brumeProps = new BrumeProperties(
                "config/config.yaml", "hmac", "fpekey1234567890", "HmacSHA256", "fr", 1.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        JdbcSink lenientSink = new JdbcSink(targetDataSource, targetTransactionManager, brumeProps);

        Map<String, Object> badData = new HashMap<>();
        badData.put("id", 9001L);
        badData.put("name", null);
        badData.put("description", "fail");
        badData.put("ip_address", "0.0.0.0");
        badData.put("created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));

        List<ExtractedRow> rows = List.of(new ExtractedRow("products", badData));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        lenientSink.open(ctx(1, report));
        try {
            lenientSink.writeChunk("products", rows);
        } finally {
            lenientSink.close();
        }

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id = 9001",
                Integer.class);
        assertThat(count).isZero();
    }

    /**
     * Verifies that {@link JdbcSink} throws when 10% of batches fail and threshold is 5%.
     */
    @Test
    @DisplayName("Should throw when error rate (10%) exceeds threshold (5%)")
    void shouldThrowWhenErrorRateExceedsThreshold() {
        var brumeProps = new BrumeProperties(
                "config/config.yaml", "hmac", "fpekey1234567890", "HmacSHA256", "fr", 0.05,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager, brumeProps);

        // 9 good rows + 1 bad row = 10 batches with batchSize=1 → 10% error rate
        List<ExtractedRow> rows = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            rows.add(new ExtractedRow("products", Map.of(
                    "id", 9000L + i,
                    "name", "Good Product " + i,
                    "description", "good",
                    "ip_address", "127.0.0." + i,
                    "created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"))));
        }
        Map<String, Object> badData = new HashMap<>();
        badData.put("id", 9010L);
        badData.put("name", null);
        badData.put("description", "fail");
        badData.put("ip_address", "0.0.0.0");
        badData.put("created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));
        rows.add(new ExtractedRow("products", badData));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(ctx(1, report));
        try {
            assertThatThrownBy(() -> sink.writeChunk("products", rows))
                    .isInstanceOf(BatchErrorThresholdExceededException.class)
                    .hasMessageContaining("1/10 batches failed")
                    .hasMessageContaining("10.00%");
        } finally {
            sink.close();
        }

        targetJdbc.execute("DELETE FROM test_brume.products WHERE id BETWEEN 9001 AND 9010");
    }

    /**
     * Verifies that {@link JdbcSink} does NOT throw when 10% of batches fail
     * and threshold is 10% (exactly at threshold).
     */
    @Test
    @DisplayName("Should not throw when error rate (10%) equals threshold (10%)")
    void shouldNotThrowWhenErrorRateEqualsThreshold() {
        var brumeProps = new BrumeProperties(
                "config/config.yaml", "hmac", "fpekey1234567890", "HmacSHA256", "fr", 0.10,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        JdbcSink sink = new JdbcSink(targetDataSource, targetTransactionManager, brumeProps);

        List<ExtractedRow> rows = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            rows.add(new ExtractedRow("products", Map.of(
                    "id", 9000L + i,
                    "name", "Good Product " + i,
                    "description", "good",
                    "ip_address", "127.0.0." + i,
                    "created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"))));
        }
        Map<String, Object> badData = new HashMap<>();
        badData.put("id", 9010L);
        badData.put("name", null);
        badData.put("description", "fail");
        badData.put("ip_address", "0.0.0.0");
        badData.put("created_at", java.sql.Timestamp.valueOf("2025-01-01 00:00:00"));
        rows.add(new ExtractedRow("products", badData));

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        sink.open(ctx(1, report));
        try {
            sink.writeChunk("products", rows);
        } finally {
            sink.close();
        }

        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products WHERE id BETWEEN 9001 AND 9009",
                Integer.class);
        assertThat(count).isEqualTo(9);

        targetJdbc.execute("DELETE FROM test_brume.products WHERE id BETWEEN 9001 AND 9010");
    }
}
