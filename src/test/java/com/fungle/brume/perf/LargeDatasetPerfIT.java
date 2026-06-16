package com.fungle.brume.perf;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.ConfigValidator;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.extraction.ChunkedTableProcessor;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.schema.SchemaAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in load test for the 500k-row performance dataset used by T16.
 *
 * <p>Disabled by default to keep the regular test suite fast. Enable explicitly with:
 * {@code -Dbrume.perf.enabled=true -Dtest=LargeDatasetPerfIT}.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "brume.perf.enabled", matches = "true")
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-perf.yaml",
        "brume.pipeline-mode=STREAMING",
        "brume.max-target-rows=600000",
        "brume.heap-warning-threshold-percent=95",
        "replication.schema=test_brume_perf",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class LargeDatasetPerfIT {

    private static final Logger log = LoggerFactory.getLogger(LargeDatasetPerfIT.class);

    private static final long EXPECTED_USERS = 50_000L;
    private static final long EXPECTED_PRODUCTS = 5_000L;
    private static final long EXPECTED_ORDERS = 200_000L;
    private static final long EXPECTED_ORDER_ITEMS = 200_000L;
    private static final long EXPECTED_AUDIT_LOGS = 45_000L;
    private static final long EXPECTED_TOTAL = 500_000L;

    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    private SchemaReplicator schemaReplicator;

    @Autowired
    private SchemaAnalyzer schemaAnalyzer;

    @Autowired
    private ConfigLoader configLoader;

    @Autowired
    private ConfigValidator configValidator;

    @Autowired
    private ChunkedTableProcessor chunkedTableProcessor;

    @Autowired
    private ReplicationProperties replicationProperties;

    private JdbcTemplate sourceJdbc;
    private JdbcTemplate targetJdbc;
    private DataSource sourceDataSource;
    private DataSource targetDataSource;

    @Autowired
    void setSourceJdbc(@Qualifier("sourceDataSource") DataSource sourceDataSource) {
        this.sourceDataSource = sourceDataSource;
        this.sourceJdbc = new JdbcTemplate(sourceDataSource);
    }

    @Autowired
    void setTargetJdbc(@Qualifier("targetDataSource") DataSource targetDataSource) {
        this.targetJdbc = new JdbcTemplate(targetDataSource);
        this.targetDataSource = targetDataSource;
    }

    @BeforeAll
    void loadPerfDataset() throws Exception {
        executeScript(Objects.requireNonNull(sourceDataSource, "sourceDataSource must be injected"), "scripts/04_schema_perf.sql");
        executeScript(Objects.requireNonNull(sourceDataSource, "sourceDataSource must be injected"), "scripts/05_data_perf_500k.sql");
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_brume_perf CASCADE");
    }

    @Test
    @DisplayName("Le pipeline streaming chunké traite 500k lignes sur le dataset perf")
    void shouldProcessFiveHundredThousandRowsInStreamingMode() throws Exception {
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);

        schemaReplicator.replicate(
                replicationProperties.source().url(),
                replicationProperties.source().username(),
                replicationProperties.source().password(),
                replicationProperties.schema(),
                targetDataSource
        );

        DatabaseSchema schema = schemaAnalyzer.analyze(replicationProperties.schema());
        ExecutionReport report = new ExecutionReport(replicationProperties.schema(), replicationProperties.schema());

        long start = System.currentTimeMillis();
        chunkedTableProcessor.processAll(config, schema, report);
        long elapsed = System.currentTimeMillis() - start;

        ExecutionSummary summary = report.toSummary(new PhaseTimings(elapsed, 0L, 0L, elapsed));

        assertThat(countTarget("users")).isEqualTo(EXPECTED_USERS);
        assertThat(countTarget("products")).isEqualTo(EXPECTED_PRODUCTS);
        assertThat(countTarget("orders")).isEqualTo(EXPECTED_ORDERS);
        assertThat(countTarget("order_items")).isEqualTo(EXPECTED_ORDER_ITEMS);
        assertThat(countTarget("audit_logs")).isEqualTo(EXPECTED_AUDIT_LOGS);

        assertThat(summary.totalInserted()).isEqualTo(EXPECTED_TOTAL);
        assertThat(summary.totalExtracted()).isEqualTo(EXPECTED_TOTAL);
        assertThat(summary.totalBatchErrors()).isZero();
        assertThat(summary.totalConflicts()).isZero();
        assertThat(summary.heap().peakUsedBytes()).isGreaterThan(0L);
        assertThat(summary.heap().maxBytes()).isGreaterThan(0L);
        assertThat(elapsed).isGreaterThan(0L);

        // PERF-BL marker for dev-features/perf-phase2-baseline.md (P2-BL, ADR-0002).
        // Pattern: "[PERF-BL] elapsed_ms=… peak_heap_mb=… throughput_rows_per_s=… dict_entries=…"
        long peakHeapMb = summary.heap().peakUsedBytes() / (1024L * 1024L);
        long throughputRowsPerSec = elapsed > 0 ? (EXPECTED_TOTAL * 1000L / elapsed) : 0L;
        long dictEntries = summary.substitutionDict() != null
                ? summary.substitutionDict().entries()
                : 0L;
        log.info("[PERF-BL] elapsed_ms={} peak_heap_mb={} throughput_rows_per_s={} dict_entries={}",
                elapsed, peakHeapMb, throughputRowsPerSec, dictEntries);
    }

    private long countTarget(String table) {
        Long count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume_perf." + table,
                Long.class
        );
        return count == null ? 0L : count;
    }

    private static void executeScript(DataSource dataSource, String path) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        }
    }
}


