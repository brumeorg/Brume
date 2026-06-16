package com.fungle.brume.schema;

import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GraphAnalyzer}.
 *
 * <p>All tests use hardcoded {@link DatabaseSchema} instances — no database connection required.
 */
class GraphAnalyzerTest {

    private GraphAnalyzer graphAnalyzer;

    @BeforeEach
    void setUp() {
        graphAnalyzer = new GraphAnalyzer();
    }

    // -------------------------------------------------------------------------
    // Topological sort — linear chain
    // -------------------------------------------------------------------------

    /**
     * Schema: products <- order_items -> orders -> users (3-level FK hierarchy).
     * Expected insertion order: users, products, orders, order_items
     * (parents before children).
     */
    @Test
    void topologicalSort_threeLevel_parentBeforeChild() {
        DatabaseSchema schema = buildThreeLevelSchema();

        List<String> order = graphAnalyzer.topologicalSort(schema);

        // users and products must come before orders; orders before order_items
        assertThat(order).containsExactlyInAnyOrder("users", "products", "orders", "order_items");
        assertThat(order.indexOf("users")).isLessThan(order.indexOf("orders"));
        assertThat(order.indexOf("products")).isLessThan(order.indexOf("order_items"));
        assertThat(order.indexOf("orders")).isLessThan(order.indexOf("order_items"));
    }

    @Test
    void topologicalSort_singleTable_noFk() {
        DatabaseSchema schema = schemaOf(tableWithNoFk("standalone"));

        List<String> order = graphAnalyzer.topologicalSort(schema);

        assertThat(order).containsExactly("standalone");
    }

    @Test
    void topologicalSort_selfReferentialTable_appendedAtEnd() {
        // users has a self-referential FK: manager_id -> users.id
        DatabaseSchema schema = buildSchemaWithSelfRef();

        List<String> order = graphAnalyzer.topologicalSort(schema);

        // "users" should still appear in the result (appended after non-cyclic tables)
        assertThat(order).contains("users");
        // Non-cyclic table "products" must be present
        assertThat(order).contains("products");
    }

    // -------------------------------------------------------------------------
    // Cycle detection
    // -------------------------------------------------------------------------

    @Test
    void detectCycles_acyclicSchema_returnsEmpty() {
        DatabaseSchema schema = buildThreeLevelSchema();

        Set<String> cycles = graphAnalyzer.detectCycles(schema);

        assertThat(cycles).isEmpty();
    }

    @Test
    void detectCycles_selfReferentialTable_detectedAsCyclic() {
        DatabaseSchema schema = buildSchemaWithSelfRef();

        Set<String> cycles = graphAnalyzer.detectCycles(schema);

        assertThat(cycles).contains("users");
    }

    @Test
    void detectCycles_mutualFk_bothTablesDetected() {
        // a -> b and b -> a
        TableMetadata a = new TableMetadata("a",
                List.of(col("id"), col("b_id")),
                List.of(new ForeignKey("a", "b_id", "b", "id")),
                "id");
        TableMetadata b = new TableMetadata("b",
                List.of(col("id"), col("a_id")),
                List.of(new ForeignKey("b", "a_id", "a", "id")),
                "id");
        DatabaseSchema schema = schemaOf(a, b);

        Set<String> cycles = graphAnalyzer.detectCycles(schema);

        assertThat(cycles).contains("a").contains("b");
    }

    // -------------------------------------------------------------------------
    // resolveAncestors
    // -------------------------------------------------------------------------

    @Test
    void resolveAncestors_leafTable_returnsAllAncestors() {
        DatabaseSchema schema = buildThreeLevelSchema();

        // order_items -> orders -> users (and also -> products)
        Set<String> ancestors = graphAnalyzer.resolveAncestors("order_items", schema, 3);

        assertThat(ancestors).contains("orders", "users", "products");
        assertThat(ancestors).doesNotContain("order_items");
    }

    @Test
    void resolveAncestors_maxDepthLimitsTraversal() {
        DatabaseSchema schema = buildThreeLevelSchema();

        // Depth 1 from order_items -> only direct parents: orders and products
        Set<String> ancestors = graphAnalyzer.resolveAncestors("order_items", schema, 1);

        assertThat(ancestors).containsExactlyInAnyOrder("orders", "products");
        assertThat(ancestors).doesNotContain("users");
    }

    @Test
    void resolveAncestors_rootTable_returnsEmpty() {
        DatabaseSchema schema = buildThreeLevelSchema();

        Set<String> ancestors = graphAnalyzer.resolveAncestors("users", schema, 3);

        assertThat(ancestors).isEmpty();
    }

    @Test
    void resolveAncestors_selfReferentialTable_doesNotLoop() {
        DatabaseSchema schema = buildSchemaWithSelfRef();

        // Must not throw StackOverflowError
        Set<String> ancestors = graphAnalyzer.resolveAncestors("users", schema, 5);

        // Self-reference is skipped; no other parent
        assertThat(ancestors).doesNotContain("users");
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    /**
     * Builds: users (root) <- orders <- order_items -> products (root).
     * order_items has two FKs: order_id -> orders and product_id -> products.
     */
    private DatabaseSchema buildThreeLevelSchema() {
        TableMetadata users = tableWithNoFk("users");
        TableMetadata products = tableWithNoFk("products");
        TableMetadata orders = new TableMetadata("orders",
                List.of(col("id"), col("user_id")),
                List.of(new ForeignKey("orders", "user_id", "users", "id")),
                "id");
        TableMetadata orderItems = new TableMetadata("order_items",
                List.of(col("id"), col("order_id"), col("product_id")),
                List.of(
                        new ForeignKey("order_items", "order_id", "orders", "id"),
                        new ForeignKey("order_items", "product_id", "products", "id")),
                "id");
        return schemaOf(users, products, orders, orderItems);
    }

    /**
     * Builds: products (root) + users with self-referential manager_id -> users.id.
     */
    private DatabaseSchema buildSchemaWithSelfRef() {
        TableMetadata products = tableWithNoFk("products");
        TableMetadata users = new TableMetadata("users",
                List.of(col("id"), col("manager_id")),
                List.of(new ForeignKey("users", "manager_id", "users", "id")),
                "id");
        return schemaOf(products, users);
    }

    private TableMetadata tableWithNoFk(String name) {
        return new TableMetadata(name, List.of(col("id")), List.of(), "id");
    }

    private ColumnMetadata col(String name) {
        return new ColumnMetadata(name, "bigint", false);
    }

    private DatabaseSchema schemaOf(TableMetadata... tables) {
        var map = new java.util.LinkedHashMap<String, TableMetadata>();
        for (TableMetadata t : tables) {
            map.put(t.name(), t);
        }
        return new DatabaseSchema(Map.copyOf(map));
    }
}

