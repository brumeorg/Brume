package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fk_depth} radius and {@code orphan_policy} on the binary under test (ADR-0050), with
 * the {@code e2e_en} fixture and only {@code orders} (2025) and {@code audit_logs} declared :
 * {@code order_items} is reached by descending from the orders, {@code products} by climbing
 * from the items.
 *
 * <p>Regression for #71a : STREAMING used to copy those items without their products — 13
 * orphan FKs in the target, exit 0.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FkDepthE2E {

    private static final String DECLARED_ORDER_ITEMS = """
                - table: order_items
                  filter: "order_id IN (SELECT id FROM e2e_en.orders WHERE created_at >= '2025-01-01')"
            """;

    private static final String ORPHAN_PRODUCTS = """
            SELECT count(*) FROM e2e_en.order_items i
            LEFT JOIN e2e_en.products p ON p.id = i.product_id WHERE p.id IS NULL""";

    private final E2eProject project = E2eProject.create("fk-depth");
    private final JdbcTemplate target = E2eSettings.target().jdbc();

    @BeforeAll
    void createProject() {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
    }

    @Test
    @DisplayName("#71a — orders only, fk_depth 1, KEEP_PARENTS : the items come with their products, "
            + "and the target equals the golden dataset of the declared config")
    void keepParentsCopiesTheParentsOfFkChildren() throws IOException {
        project.useConfig(ordersOnly(1, "KEEP_PARENTS"));

        assertThat(E2eProject.plannedRows(project.run("plan", "--json"))).containsExactlyInAnyOrderEntriesOf(rows(8, 13, 5, 5, 5));
        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertThat(targetRows()).containsExactlyInAnyOrderEntriesOf(rows(8, 13, 5, 5, 5));
        assertThat(target.queryForObject(ORPHAN_PRODUCTS, Long.class)).as("order_items.product_id orphans").isZero();
        Map<String, List<String>> diffs = new LinkedHashMap<>();
        for (String table : List.of("users", "products", "orders", "order_items", "audit_logs")) {
            List<String> tableDiffs = GoldenDataset.compareWith(
                    E2eSettings.GOLDEN_DIR.resolve("e2e_en").resolve(table + ".tsv"),
                    GoldenDataset.snapshot(target, "e2e_en", table, "id"));
            if (!tableDiffs.isEmpty()) diffs.put(table, tableDiffs);
        }
        assertThat(diffs).as("same rows and values as when order_items is declared").isEmpty();
    }

    @Test
    @DisplayName("orders only, fk_depth 1, ALLOW_ORPHANS : products stay out, the 13 dangling references "
            + "are announced by plan and execute and reported in the JSON output")
    void allowOrphansKeepsTheRadiusStrictAndReportsIt() throws IOException {
        project.useConfig(ordersOnly(1, "ALLOW_ORPHANS"));
        String warning = "13 row(s) of order_items reference 5 row(s) of products outside fk_depth via product_id";

        BrumeCli.Result plan = project.run("plan", "--json");
        assertThat(E2eProject.plannedRows(plan)).containsExactlyInAnyOrderEntriesOf(
                Map.of("orders", 8, "audit_logs", 5, "users", 5, "order_items", 13));
        assertThat(plan.stderr()).contains("[ORPHANS] plan: " + warning);

        BrumeCli.Result execute = project.run("execute", "--json");
        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertThat(execute.stderr()).contains("[ORPHANS] " + warning);
        assertThat(targetRows()).containsExactlyInAnyOrderEntriesOf(rows(8, 13, 0, 5, 5));
        assertThat(target.queryForObject(ORPHAN_PRODUCTS, Long.class)).isEqualTo(13L);

        JsonNode orphans = new ObjectMapper().readTree(execute.stdout()).path("execution").path("orphanReferences");
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).path("table").asText()).isEqualTo("order_items");
        assertThat(orphans.get(0).path("parentTable").asText()).isEqualTo("products");
        assertThat(orphans.get(0).path("rows").asLong()).isEqualTo(13L);
        assertThat(orphans.get(0).path("missingParents").asLong()).isEqualTo(5L);
    }

    @Test
    @DisplayName("fk_depth is a radius in both directions : at 2, the 2024 order of Alice is reached "
            + "through Alice (up then down), its item at distance 3 is not")
    void radiusTwoReachesRowsThroughAParent() throws IOException {
        project.useConfig(ordersOnly(2, "KEEP_PARENTS"));

        assertThat(E2eProject.plannedRows(project.run("plan", "--json"))).containsExactlyInAnyOrderEntriesOf(rows(9, 13, 5, 5, 5));
        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertThat(targetRows()).containsExactlyInAnyOrderEntriesOf(rows(9, 13, 5, 5, 5));
        assertThat(target.queryForList("SELECT created_at::text FROM e2e_en.orders WHERE created_at < '2025-01-01'", String.class))
                .as("order 3001 (Alice, 2024) — order 3002 belongs to Frank, never reached")
                .containsExactly("2024-06-15 10:00:00");
    }

    /** The versioned E2E config with {@code order_items} no longer declared. */
    private static String ordersOnly(int fkDepth, String orphanPolicy) throws IOException {
        String yaml = Files.readString(E2eSettings.RESOURCES.resolve("brume.yml"));
        assertThat(yaml).contains(DECLARED_ORDER_ITEMS, "  fk_depth: 1\n", "  orphan_policy: KEEP_PARENTS\n");
        return yaml.replace(DECLARED_ORDER_ITEMS, "")
                .replace("  fk_depth: 1\n", "  fk_depth: " + fkDepth + "\n")
                .replace("  orphan_policy: KEEP_PARENTS\n", "  orphan_policy: " + orphanPolicy + "\n");
    }

    private static Map<String, Integer> rows(int orders, int items, int products, int users, int auditLogs) {
        return Map.of("orders", orders, "order_items", items, "products", products, "users", users, "audit_logs", auditLogs);
    }

    private Map<String, Integer> targetRows() {
        Map<String, Integer> rows = new LinkedHashMap<>();
        for (String table : List.of("orders", "order_items", "products", "users", "audit_logs")) {
            rows.put(table, target.queryForObject("SELECT count(*) FROM e2e_en." + table, Integer.class));
        }
        return rows;
    }
}
