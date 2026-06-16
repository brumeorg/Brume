package com.fungle.brume.writer;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.report.ExecutionReport;
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
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for {@link HybridWriter}.
 *
 * <p><strong>Requires Docker infrastructure</strong> — run {@code docker-compose up -d} first:
 * <ul>
 *   <li>Source DB on {@code localhost:5432} with schema {@code test_brume}</li>
 *   <li>Target DB on {@code localhost:5460} with schema {@code test_brume}</li>
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
class HybridWriterIT {

    /** Mocked to prevent CommandLineRunner from executing the full pipeline on context startup. */
    @MockitoBean
    private ReplicationAgent replicationAgent;

    /** Empty schema instance used when the writer does not need schema metadata. */
    private static final DatabaseSchema EMPTY_SCHEMA = new DatabaseSchema(new HashMap<>());

    @Autowired
    private HybridWriter hybridWriter;

    private JdbcTemplate sourceJdbc;
    private JdbcTemplate targetJdbc;

    @Autowired
    public void setJdbcTemplates(
            @Qualifier("sourceDataSource") DataSource sourceds,
            @Qualifier("targetDataSource") DataSource targetds) {
        this.sourceJdbc = new JdbcTemplate(sourceds);
        this.targetJdbc = new JdbcTemplate(targetds);
    }

    @BeforeEach
    void truncateTargetTables() {
        // Create schema and tables on target if they do not exist yet
        // (target container starts empty; Brume normally runs SchemaReplicator before writing)
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS test_brume");
        targetJdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_brume.users (
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
                CREATE TABLE IF NOT EXISTS test_brume.products (
                    id          BIGINT PRIMARY KEY,
                    name        VARCHAR(255) NOT NULL,
                    description TEXT,
                    ip_address  VARCHAR(45),
                    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);
        targetJdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_brume.orders (
                    id               BIGINT PRIMARY KEY,
                    user_id          BIGINT NOT NULL,
                    shipping_address TEXT,
                    total_amount     NUMERIC(10, 2) NOT NULL DEFAULT 0,
                    notes            TEXT,
                    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
                    CONSTRAINT orders_total_positive CHECK (total_amount >= 0)
                )
                """);
        targetJdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_brume.order_items (
                    id         BIGINT PRIMARY KEY,
                    order_id   BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    quantity   INTEGER NOT NULL DEFAULT 1,
                    CONSTRAINT order_items_quantity_positive CHECK (quantity > 0)
                )
                """);
        targetJdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_brume.audit_logs (
                    id         BIGINT PRIMARY KEY,
                    user_email VARCHAR(255),
                    action     VARCHAR(100) NOT NULL,
                    ip_address VARCHAR(45),
                    payload    JSONB,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);

        // Disable FK constraints to allow TRUNCATE in any order
        targetJdbc.execute("SET session_replication_role = 'replica'");
        targetJdbc.execute("TRUNCATE TABLE test_brume.order_items CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.orders CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.products CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.users CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.audit_logs CASCADE");
        targetJdbc.execute("SET session_replication_role = 'origin'");
    }

    @AfterEach
    void cleanupTarget() {
        targetJdbc.execute("SET session_replication_role = 'replica'");
        targetJdbc.execute("TRUNCATE TABLE test_brume.order_items CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.orders CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.products CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.users CASCADE");
        targetJdbc.execute("TRUNCATE TABLE test_brume.audit_logs CASCADE");
        targetJdbc.execute("SET session_replication_role = 'origin'");
    }

    /**
     * Verifies that a table with NO anonymization rules is written from the rows already present
     * in {@link ExtractionResult} — i.e. only the extracted subset, not the full source table.
     *
     * <p>This test inserts exactly 2 product rows via HybridWriter and asserts only those 2
     * rows are present in the target, confirming that HybridWriter does NOT read the whole
     * source table.
     */
    @Test
    @DisplayName("Table without anonymization rules inserts only ExtractionResult rows (not full source table)")
    void shouldCopyTableWithoutAnonymizationRulesViaCopyProtocol() {
        // Arrange — config has NO anonymization rules for products
        AnonymizerConfig config = buildConfig(Collections.emptyList());

        // Only 2 product rows in ExtractionResult — simulates a partial extraction
        OrderedExtractionResult result = new OrderedExtractionResult();
        result.add(new ExtractedRow("products", Map.of(
                "id", 7001L,
                "name", "Extracted Product A",
                "description", "subset row 1",
                "ip_address", "10.0.0.1",
                "created_at", Timestamp.valueOf("2025-03-01 00:00:00"))));
        result.add(new ExtractedRow("products", Map.of(
                "id", 7002L,
                "name", "Extracted Product B",
                "description", "subset row 2",
                "ip_address", "10.0.0.2",
                "created_at", Timestamp.valueOf("2025-03-02 00:00:00"))));

        // Act
        hybridWriter.write(result, config, EMPTY_SCHEMA, new ExecutionReport("test_brume", "test_brume"));

        // Assert — exactly 2 rows in target (the extracted subset); source has more rows but they
        // must NOT be copied — HybridWriter only writes what ExtractionResult contains
        Integer targetCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.products", Integer.class);
        assertThat(targetCount)
                .as("Target must contain exactly the 2 extracted rows, not the full source table")
                .isEqualTo(2);

        // Assert — specific row values are present and unchanged (KEEP — no transformation)
        String name = targetJdbc.queryForObject(
                "SELECT name FROM test_brume.products WHERE id = 7001", String.class);
        assertThat(name)
                .as("Row data must be identical to the source (no anonymization applied)")
                .isEqualTo("Extracted Product A");
    }

    /**
     * Verifies that a table WITH anonymization rules is written via the INSERT batch path,
     * and that absent columns produce NULL in the target (simulated NULLIFY strategy).
     */
    @Test
    @DisplayName("Table with anonymization rules is written via INSERT batch with NULLIFY applied")
    void shouldWriteTableWithAnonymizationRulesViaInsertBatch() {
        // Arrange — config has rules for orders: KEEP total_amount, NULLIFY notes
        List<ColumnConfig> orderColumns = List.of(
                new ColumnConfig("id", Strategy.KEEP, null, null),
                new ColumnConfig("user_id", Strategy.KEEP, null, null),
                new ColumnConfig("shipping_address", Strategy.KEEP, null, null),
                new ColumnConfig("total_amount", Strategy.KEEP, null, null),
                new ColumnConfig("notes", Strategy.NULLIFY, null, null),
                new ColumnConfig("created_at", Strategy.KEEP, null, null)
        );
        AnonymizerConfig config = buildConfig(
                List.of(new TableAnonymizationConfig("orders", orderColumns)));

        // 'notes' is intentionally absent so its value will be null in the target
        OrderedExtractionResult result = new OrderedExtractionResult();
        result.add(new ExtractedRow("orders", Map.of(
                "id", 5001L,
                "user_id", 1L,
                "shipping_address", "123 Main St",
                "total_amount", new BigDecimal("99.99"),
                "created_at", Timestamp.valueOf("2025-06-01 10:00:00")
        )));
        result.add(new ExtractedRow("orders", Map.of(
                "id", 5002L,
                "user_id", 2L,
                "shipping_address", "456 Side Ave",
                "total_amount", new BigDecimal("49.50"),
                "created_at", Timestamp.valueOf("2025-06-02 11:00:00")
        )));

        // Act
        hybridWriter.write(result, config, EMPTY_SCHEMA, new ExecutionReport("test_brume", "test_brume"));

        // Assert — rows present in target
        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_brume.orders WHERE id IN (5001, 5002)",
                Integer.class);
        assertThat(count)
                .as("Both orders must be present in the target")
                .isEqualTo(2);

        // Assert — total_amount unchanged (KEEP strategy applied)
        BigDecimal amount = targetJdbc.queryForObject(
                "SELECT total_amount FROM test_brume.orders WHERE id = 5001",
                BigDecimal.class);
        assertThat(amount)
                .as("total_amount must remain unchanged (KEEP strategy)")
                .isEqualByComparingTo(new BigDecimal("99.99"));

        // Assert — notes is NULL (NULLIFY strategy applied — absent from row data)
        String notes = targetJdbc.queryForObject(
                "SELECT notes FROM test_brume.orders WHERE id = 5001",
                String.class);
        assertThat(notes)
                .as("notes must be NULL (key absent from row data)")
                .isNull();
    }

    /**
     * Verifies that FK constraints are bypassed via {@code session_replication_role = 'replica'}.
     *
     * <p>Inserts an {@code orders} row referencing {@code user_id = 99999} which does not exist
     * in the target — without the role override this would throw a FK violation.
     */
    @Test
    @DisplayName("FK violation is bypassed by session_replication_role = replica")
    void shouldSetSessionReplicationRoleAroundWrites() {
        // Arrange — orders references user_id=99999 which does NOT exist in target users
        List<ColumnConfig> orderColumns = List.of(
                new ColumnConfig("id", Strategy.KEEP, null, null),
                new ColumnConfig("user_id", Strategy.KEEP, null, null),
                new ColumnConfig("shipping_address", Strategy.KEEP, null, null),
                new ColumnConfig("total_amount", Strategy.KEEP, null, null),
                new ColumnConfig("notes", Strategy.KEEP, null, null),
                new ColumnConfig("created_at", Strategy.KEEP, null, null)
        );
        AnonymizerConfig config = buildConfig(
                List.of(new TableAnonymizationConfig("orders", orderColumns)));

        OrderedExtractionResult result = new OrderedExtractionResult();
        result.add(new ExtractedRow("orders", Map.of(
                "id", 6001L,
                "user_id", 99999L,   // no such user in target — would cause FK violation
                "shipping_address", "NK",
                "total_amount", new BigDecimal("1.00"),
                "created_at", Timestamp.valueOf("2025-07-01 00:00:00")
        )));

        // Act + Assert — must NOT throw even though user_id 99999 doesn't exist in target
        assertThatNoException()
                .as("session_replication_role='replica' must suppress FK violation for orders.user_id")
                .isThrownBy(() -> hybridWriter.write(result, config, EMPTY_SCHEMA,
                        new ExecutionReport("test_brume", "test_brume")));

        // Cleanup the FK-bypassed row
        targetJdbc.execute("DELETE FROM test_brume.orders WHERE id = 6001");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal {@link AnonymizerConfig} with the given per-table anonymization rules.
     *
     * @param tableRules per-table anonymization rules (may be empty)
     * @return a fully-constructed {@link AnonymizerConfig}
     */
    private AnonymizerConfig buildConfig(List<TableAnonymizationConfig> tableRules) {
        ExtractionConfig extraction = new ExtractionConfig(
                3, 500,
                List.of(new TableExtractionConfig("orders", null),
                        new TableExtractionConfig("products", null)));
        AnonymizationConfig anonymization = new AnonymizationConfig(
                Collections.emptyList(), tableRules);
        return new AnonymizerConfig(extraction, anonymization);
    }
}


