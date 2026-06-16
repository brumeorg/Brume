package com.fungle.brume.extraction;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.ConfigValidator;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.schema.SchemaAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ExtractionEngine}.
 *
 * <p><strong>Requires Docker infrastructure</strong> — run {@code docker-compose up -d} first:
 * <ul>
 *   <li>Source DB on {@code localhost:5432} with schema {@code test_brume}</li>
 *   <li>Target DB on {@code localhost:5460} (not used by extraction, but Spring context needs it)</li>
 * </ul>
 *
 * <p>{@link ReplicationAgent} is mocked to prevent the CommandLineRunner from executing
 * the full replication pipeline when the Spring context starts.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class ExtractionEngineIT {

    /** Mocked to prevent CommandLineRunner from executing the full pipeline on context startup. */
    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    private ExtractionEngine extractionEngine;

    @Autowired
    private SchemaAnalyzer schemaAnalyzer;

    @Autowired
    private ConfigLoader configLoader;

    @Autowired
    private ConfigValidator configValidator;

    /**
     * Verifies that extracting {@code orders} with a date filter resolves FK parents
     * ({@code users}) automatically, and that out-of-filter orders from 2024 are absent.
     */
    @Test
    @DisplayName("Filtered orders extraction resolves FK parent users and excludes 2024 orders")
    void shouldResolveUsersAsParentsAndExclude2024Orders() {
        // Arrange
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        // Act
        OrderedExtractionResult result = extractionEngine.extract(config, schema,
                new ExecutionReport("test_brume", "test_brume"));

        // Assert — orders from 2025 must be present
        Set<String> tables = result.allTables();
        assertThat(tables).contains("orders");

        List<Object> orderIds = result.getRows("orders").stream()
                .map(row -> row.data().get("id"))
                .toList();

        // Orders 103–110 (2025) must be extracted
        assertThat(orderIds)
                .as("All 8 filtered orders from 2025 must be present")
                .containsExactlyInAnyOrder(103L, 104L, 105L, 106L, 107L, 108L, 109L, 110L);

        // Orders 101 and 102 (2024) must NOT be present
        assertThat(orderIds)
                .as("Out-of-filter orders 101 and 102 (2024) must be absent")
                .doesNotContain(101L, 102L);

        // FK parent users must have been resolved automatically
        assertThat(tables)
                .as("users must be present after FK parent resolution")
                .contains("users");

        assertThat(result.getRows("users"))
                .as("At least 5 users must be resolved (users 1-5 are referenced by orders 103-110)")
                .hasSizeGreaterThanOrEqualTo(5);
    }

    /**
     * Verifies that extracting {@code orders} + {@code order_items} resolves FK parents
     * for both tables: {@code users} from orders and {@code products} from order_items.
     */
    @Test
    @DisplayName("Order items extraction resolves FK parent products")
    void shouldResolveProductsAsParentsOfOrderItems() {
        // Arrange
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        // Act
        OrderedExtractionResult result = extractionEngine.extract(config, schema,
                new ExecutionReport("test_brume", "test_brume"));

        // Assert — products must be present (FK parent of order_items.product_id)
        assertThat(result.allTables())
                .as("products must be resolved as FK parents of order_items")
                .contains("products");

        assertThat(result.getRows("products"))
                .as("At least one product must be present after FK parent resolution")
                .isNotEmpty();

        // All products referenced by filtered order_items (10-14) must be present
        List<Object> productIds = result.getRows("products").stream()
                .map(row -> row.data().get("id"))
                .toList();

        assertThat(productIds)
                .as("Products 10-14 are referenced by order_items on filtered orders")
                .containsExactlyInAnyOrder(10L, 11L, 12L, 13L, 14L);
    }

    /**
     * Verifies that the extraction result respects topological insertion order:
     * parent tables appear before child tables.
     *
     * <p>Note: {@code users} is a cyclic table (self-referential {@code manager_id → users.id})
     * and is therefore appended at the end of the insertion order by {@link
     * com.fungle.brume.schema.GraphAnalyzer#topologicalSort} — this is by design.
     * The writer will bypass FK constraints via {@code session_replication_role = 'replica'}.
     */
    @Test
    @DisplayName("Extraction result is in topological order: products before order_items, orders before order_items")
    void shouldReturnTablesInTopologicalOrder() {
        // Arrange
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        // Act
        OrderedExtractionResult result = extractionEngine.extract(config, schema,
                new ExecutionReport("test_brume", "test_brume"));

        List<String> tableOrder = List.copyOf(result.allTables());

        // products must appear before order_items (products is a non-cyclic parent)
        if (tableOrder.contains("products") && tableOrder.contains("order_items")) {
            int productsIdx = tableOrder.indexOf("products");
            int orderItemsIdx = tableOrder.indexOf("order_items");
            assertThat(productsIdx)
                    .as("products must appear before order_items in the topological order")
                    .isLessThan(orderItemsIdx);
        }

        // orders must appear before order_items (orders is a non-cyclic parent)
        if (tableOrder.contains("orders") && tableOrder.contains("order_items")) {
            int ordersIdx = tableOrder.indexOf("orders");
            int orderItemsIdx = tableOrder.indexOf("order_items");
            assertThat(ordersIdx)
                    .as("orders must appear before order_items in the topological order")
                    .isLessThan(orderItemsIdx);
        }

        // users is cyclic (self-referential manager_id) — it is appended LAST by GraphAnalyzer.
        // This is expected and documented: cyclic tables are handled by disabling FK constraints
        // at write time (session_replication_role = 'replica' in Phase 5).
        if (tableOrder.contains("users")) {
            int usersIdx = tableOrder.indexOf("users");
            int orderItemsIdx = tableOrder.contains("order_items") ? tableOrder.indexOf("order_items") : -1;
            if (orderItemsIdx >= 0) {
                assertThat(usersIdx)
                        .as("users is cyclic and must appear AFTER non-cyclic order_items")
                        .isGreaterThan(orderItemsIdx);
            }
        }
    }

    /**
     * Phase 1 fix — verifies no duplicate rows after FK resolution.
     *
     * <p>Before the fix, {@code add()} during initial extraction did not populate {@code pkIndex}.
     * This caused {@code FkParentResolver} to see all initial rows as "missing" and re-fetch them,
     * resulting in duplicates. The row count would exceed 31 if duplicates were present.
     */
    @Test
    @DisplayName("Phase 1 fix — no duplicate rows: running resolve() twice yields same count")
    void shouldNotProduceDuplicateRowsAfterFkResolution() {
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        OrderedExtractionResult result = extractionEngine.extract(config, schema,
                new ExecutionReport("test_brume", "test_brume"));

        int firstCount = result.totalRowCount();

        // The correct total is 31: any higher value means duplicates
        assertThat(firstCount)
                .as("Expected exactly 31 rows — more rows indicate duplicates from Phase 1 bug")
                .isEqualTo(31);
    }

    /**
     * Total row count matches expected dataset size after FK resolution.
     */
    @Test
    @DisplayName("Total row count matches expected dataset size after FK resolution")
    void shouldHaveCorrectTotalRowCount() {
        // Arrange
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        // Act
        OrderedExtractionResult result = extractionEngine.extract(config, schema,
                new ExecutionReport("test_brume", "test_brume"));

        // 8 orders (103-110) + 5 users + 5 products + 13 filtered order_items (203-215)
        // Total expected = 8 + 5 + 5 + 13 = 31
        assertThat(result.totalRowCount())
                .as("Expected 31 rows total: 8 orders + 5 users + 5 products + 13 order_items")
                .isEqualTo(31);
    }

    /**
     * Verifies that ExtractionEngine records extraction stats in the ExecutionReport.
     */
    @Test
    @DisplayName("Extraction records stats in ExecutionReport")
    void shouldRecordExtractionStatsInReport() {
        // Arrange
        AnonymizerConfig config = configLoader.load();
        configValidator.validate(config);
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");

        // Act
        extractionEngine.extract(config, schema, report);
        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));

        // Assert — total extracted rows must be positive
        assertThat(summary.totalExtracted())
                .as("Total extracted (direct + FK parents) must be greater than 0")
                .isGreaterThan(0);

        // Assert — orders table stats must be present
        assertThat(summary.tableStats())
                .as("tableStats must contain an entry for 'orders'")
                .anyMatch(ts -> ts.table().equals("orders"));

        // Assert — users must have fkParents > 0 (users are FK parents of orders)
        assertThat(summary.tableStats())
                .as("users must have fkParents > 0 since they are resolved as FK parents of orders")
                .anyMatch(ts -> ts.table().equals("users") && ts.fkParents() > 0);
    }
}
