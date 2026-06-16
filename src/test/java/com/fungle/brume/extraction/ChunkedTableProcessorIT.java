package com.fungle.brume.extraction;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.ConfigValidator;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.schema.SchemaAnalyzer;
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

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.pipeline-mode=STREAMING",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class ChunkedTableProcessorIT {

    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    private ChunkedTableProcessor chunkedTableProcessor;

    @Autowired
    private SchemaAnalyzer schemaAnalyzer;

    @Autowired
    private ConfigLoader configLoader;

    @Autowired
    private ConfigValidator configValidator;

    private JdbcTemplate targetJdbc;

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource targetDataSource) {
        this.targetJdbc = new JdbcTemplate(targetDataSource);
    }

    @BeforeEach
    void prepareTargetSchema() {
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS test_brume");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.order_items CASCADE");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.orders CASCADE");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.products CASCADE");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.users CASCADE");

        targetJdbc.execute("""
                CREATE TABLE test_brume.users (
                    id           BIGINT PRIMARY KEY,
                    email        VARCHAR(255) NOT NULL,
                    first_name   VARCHAR(100) NOT NULL,
                    last_name    VARCHAR(100) NOT NULL,
                    phone        VARCHAR(20),
                    iban         VARCHAR(34),
                    ip_address   VARCHAR(45),
                    address_json JSONB,
                    manager_id   BIGINT,
                    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);
        targetJdbc.execute("""
                CREATE TABLE test_brume.products (
                    id          BIGINT PRIMARY KEY,
                    name        VARCHAR(255) NOT NULL,
                    description TEXT,
                    ip_address  VARCHAR(45),
                    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);
        targetJdbc.execute("""
                CREATE TABLE test_brume.orders (
                    id               BIGINT PRIMARY KEY,
                    user_id          BIGINT NOT NULL,
                    shipping_address TEXT,
                    total_amount     NUMERIC(10, 2) NOT NULL DEFAULT 0,
                    notes            TEXT,
                    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);
        targetJdbc.execute("""
                CREATE TABLE test_brume.order_items (
                    id         BIGINT PRIMARY KEY,
                    order_id   BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    quantity   INTEGER NOT NULL DEFAULT 1
                )
                """);
    }

    @AfterEach
    void cleanupTargetSchema() {
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.order_items CASCADE");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.orders CASCADE");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.products CASCADE");
        targetJdbc.execute("DROP TABLE IF EXISTS test_brume.users CASCADE");
    }

    @Test
    @DisplayName("Le processeur chunké produit le dataset attendu sur test_brume")
    void shouldProcessConfiguredTablesInChunkedStreamingMode() {
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");
        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");

        chunkedTableProcessor.processAll(config, schema, report);

        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.orders", Integer.class)).isEqualTo(8);
        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.order_items", Integer.class)).isEqualTo(13);
        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.products", Integer.class)).isEqualTo(5);
        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.users", Integer.class)).isEqualTo(5);

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalExtracted()).isEqualTo(31);
        assertThat(summary.totalInserted()).isEqualTo(31);
        assertThat(summary.totalConflicts()).isZero();
        assertThat(summary.totalBatchErrors()).isZero();
    }

    @Test
    @DisplayName("Le processeur chunké reste correct avec un chunk_size très petit")
    void shouldProcessMultipleSmallChunksConsistently() {
        AnonymizerConfig loaded = configLoader.load();
        configValidator.validate(loaded);
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(
                        loaded.extraction().fkDepth(),
                        loaded.extraction().batchSize(),
                        3,
                        loaded.extraction().tables()
                ),
                loaded.anonymization()
        );
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");
        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");

        chunkedTableProcessor.processAll(config, schema, report);

        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.orders", Integer.class)).isEqualTo(8);
        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.order_items", Integer.class)).isEqualTo(13);
        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.products", Integer.class)).isEqualTo(5);
        assertThat(targetJdbc.queryForObject("SELECT COUNT(*) FROM test_brume.users", Integer.class)).isEqualTo(5);

        Integer anonymizedOrders = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.orders WHERE total_amount IS NOT NULL", Integer.class);
        assertThat(anonymizedOrders).isEqualTo(8);
    }
}

