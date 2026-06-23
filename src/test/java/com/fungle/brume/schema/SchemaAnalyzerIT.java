package com.fungle.brume.schema;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SchemaAnalyzer}.
 *
 * <p><strong>Requires Docker infrastructure</strong> — run {@code docker-compose up -d source} first:
 * <ul>
 *   <li>Source DB on {@code localhost:5432} with schema {@code test_brume}</li>
 *   <li>Target DB on {@code localhost:5460} (not interrogated here, but Spring context needs the pool)</li>
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
class SchemaAnalyzerIT {

    /** Mocked to prevent CommandLineRunner from executing the full pipeline on context startup. */
    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    private SchemaAnalyzer schemaAnalyzer;

    @Autowired
    private GraphAnalyzer graphAnalyzer;

    /**
     * Verifies that {@code analyze("test_brume")} returns a schema with exactly the 5 expected tables.
     */
    @Test
    @DisplayName("analyze() returns exactly 5 tables for schema test_brume")
    void shouldReturnFiveTablesForTestBrumeSchema() {
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        assertThat(schema.tableNames())
                .as("test_brume must contain exactly: users, products, orders, order_items, audit_logs")
                .containsExactlyInAnyOrder("users", "products", "orders", "order_items", "audit_logs");
    }

    /**
     * Verifies that the {@code orders} table has a foreign key {@code user_id → users.id}.
     */
    @Test
    @DisplayName("orders has FK user_id → users.id")
    void shouldDetectOrdersUserIdForeignKey() {
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        List<ForeignKey> ordersFks = schema.get("orders").foreignKeys();

        assertThat(ordersFks)
                .as("orders must declare at least one FK")
                .isNotEmpty();

        assertThat(ordersFks)
                .as("orders must have FK: fromColumn=user_id, toTable=users, toColumn=id")
                .anyMatch(fk -> "user_id".equals(fk.fromColumn())
                        && "users".equals(fk.toTable())
                        && "id".equals(fk.toColumn()));
    }

    /**
     * Verifies that {@code order_items} declares two FK constraints:
     * {@code order_id → orders.id} and {@code product_id → products.id}.
     */
    @Test
    @DisplayName("order_items has FKs order_id→orders.id AND product_id→products.id")
    void shouldDetectOrderItemsForeignKeys() {
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        List<ForeignKey> fks = schema.get("order_items").foreignKeys();

        assertThat(fks)
                .as("order_items must have FK order_id → orders.id")
                .anyMatch(fk -> "order_id".equals(fk.fromColumn())
                        && "orders".equals(fk.toTable())
                        && "id".equals(fk.toColumn()));

        assertThat(fks)
                .as("order_items must have FK product_id → products.id")
                .anyMatch(fk -> "product_id".equals(fk.fromColumn())
                        && "products".equals(fk.toTable())
                        && "id".equals(fk.toColumn()));
    }

    /**
     * Verifies that {@code users} has a self-referential FK {@code manager_id → users.id}.
     */
    @Test
    @DisplayName("users has self-referential FK manager_id → users.id")
    void shouldDetectUsersSelfReferentialForeignKey() {
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        List<ForeignKey> usersFks = schema.get("users").foreignKeys();

        assertThat(usersFks)
                .as("users must have a self-referential FK: manager_id → users.id")
                .anyMatch(fk -> "manager_id".equals(fk.fromColumn())
                        && "users".equals(fk.toTable())
                        && "id".equals(fk.toColumn()));
    }

    /**
     * Verifies that {@link GraphAnalyzer#detectCycles} identifies {@code users} as cyclic
     * because of its self-referential FK {@code manager_id → users.id}.
     */
    @Test
    @DisplayName("detectCycles identifies users as cyclic (self-referential manager_id)")
    void shouldDetectUsersAsCyclic() {
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        Set<String> cyclic = graphAnalyzer.detectCycles(schema);

        assertThat(cyclic)
                .as("users must be detected as cyclic due to self-referential manager_id FK")
                .contains("users");
    }

    /**
     * Verifies that {@link GraphAnalyzer#topologicalSort} places non-cyclic parents
     * ({@code products}, {@code orders}) before {@code order_items}, and that the cyclic
     * table {@code users} is appended last.
     *
     * <p>Note: {@code users} is appended last because it participates in a cycle; FK constraints
     * will be bypassed at write time via {@code session_replication_role = 'replica'}.
     * This is correct behavior, not a bug.
     */
    @Test
    @DisplayName("topologicalSort places products and orders before order_items; users is last (cyclic)")
    void shouldRespectTopologicalOrder() {
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        List<String> order = graphAnalyzer.topologicalSort(schema);

        int productsIdx   = order.indexOf("products");
        int ordersIdx     = order.indexOf("orders");
        int orderItemsIdx = order.indexOf("order_items");
        int usersIdx      = order.indexOf("users");

        assertThat(productsIdx)
                .as("products must appear before order_items")
                .isLessThan(orderItemsIdx);

        assertThat(ordersIdx)
                .as("orders must appear before order_items")
                .isLessThan(orderItemsIdx);

        assertThat(usersIdx)
                .as("users (cyclic) must be appended AFTER non-cyclic order_items")
                .isGreaterThan(orderItemsIdx);
    }

    /**
     * Phase 1 fix — verifies that {@code SchemaAnalyzer} correctly loads the single-column
     * primary key for each table in {@code test_brume}.
     */
    @Test
    @DisplayName("analyze() loads primaryKeyColumn for each table in test_brume")
    void shouldLoadPrimaryKeyColumnsForAllTables() {
        DatabaseSchema schema = schemaAnalyzer.analyze("test_brume");

        assertThat(schema.get("users").singlePrimaryKeyColumn())
                .as("users PK must be 'id'")
                .isEqualTo("id");

        assertThat(schema.get("orders").singlePrimaryKeyColumn())
                .as("orders PK must be 'id'")
                .isEqualTo("id");

        assertThat(schema.get("products").singlePrimaryKeyColumn())
                .as("products PK must be 'id'")
                .isEqualTo("id");

        assertThat(schema.get("order_items").singlePrimaryKeyColumn())
                .as("order_items PK must be 'id'")
                .isEqualTo("id");

        assertThat(schema.get("audit_logs").singlePrimaryKeyColumn())
                .as("audit_logs PK must be 'id'")
                .isEqualTo("id");
    }

    /**
     * #25c — Spike HMAC 2026-05-12 had observed that the COPY block order in the dump
     * varied between consecutive runs (audit_logs/orders vs orders/audit_logs). Root
     * cause : {@code SchemaAnalyzer} used {@code HashMap} + {@code Map.copyOf} which
     * randomize iteration order. Fix : {@code LinkedHashMap} + {@code
     * Collections.unmodifiableMap} preserving the alphabetic order set by the SQL
     * {@code ORDER BY table_name}. This test verifies that two consecutive analyses
     * return the SAME table iteration order — pré-requis pour {@code DeterminismIT}
     * (#30, Phase 4).
     */
    @Test
    @DisplayName("#25c — analyze() yields a deterministic table iteration order across runs")
    void shouldYieldDeterministicTableIterationOrder() {
        DatabaseSchema first  = schemaAnalyzer.analyze("test_brume");
        DatabaseSchema second = schemaAnalyzer.analyze("test_brume");

        List<String> firstOrder  = List.copyOf(first.tableNames());
        List<String> secondOrder = List.copyOf(second.tableNames());

        assertThat(secondOrder)
                .as("two consecutive analyze() calls must yield the same table iteration order")
                .containsExactlyElementsOf(firstOrder);

        // And that order is the alphabetic one inherited from the SQL ORDER BY table_name.
        assertThat(firstOrder)
                .as("table iteration order must be alphabetic (inherited from SQL ORDER BY table_name)")
                .containsExactly("audit_logs", "order_items", "orders", "products", "users");
    }
}

