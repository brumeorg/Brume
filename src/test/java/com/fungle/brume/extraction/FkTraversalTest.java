package com.fungle.brume.extraction;

import com.fungle.brume.config.model.OrphanPolicy;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.OrphanReference;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.fungle.brume.config.model.OrphanPolicy.ALLOW_ORPHANS;
import static com.fungle.brume.config.model.OrphanPolicy.KEEP_PARENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FkTraversal} — the {@code fk_depth} radius walk shared by STREAMING,
 * BATCH and plan (ADR-0050), on an in-memory shop :
 *
 * <pre>
 * users 1 ─manager→ 2 ─manager→ 3        users 9 (no manager)
 * orders 10 (user 1, 2025) · 11 (user 1, 2024) · 12 (user 9, 2024)
 * order_items 100 (10 → product 50) · 101 (10 → 51) · 102 (11 → 50) · 103 (12 → 52)
 * </pre>
 *
 * Seed : orders of 2025, i.e. order 10 only.
 */
class FkTraversalTest {

    private static final ForeignKey ORDER_USER = new ForeignKey("orders", "user_id", "users", "id");
    private static final ForeignKey ITEM_ORDER = new ForeignKey("order_items", "order_id", "orders", "id");
    private static final ForeignKey ITEM_PRODUCT = new ForeignKey("order_items", "product_id", "products", "id");
    private static final ForeignKey USER_MANAGER = new ForeignKey("users", "manager_id", "users", "id");

    private static final DatabaseSchema SHOP = schema(
            table("users", "id", USER_MANAGER),
            table("orders", "id", ORDER_USER),
            table("order_items", "id", ITEM_ORDER, ITEM_PRODUCT),
            table("products", "id"));

    private static final List<TableExtractionConfig> ORDERS_2025 =
            List.of(new TableExtractionConfig("orders", "year >= 2025"));

    private final FakeSource shop = new FakeSource()
            .row("users", "id", 1L, "manager_id", 2L)
            .row("users", "id", 2L, "manager_id", 3L)
            .row("users", "id", 3L, "manager_id", null)
            .row("users", "id", 9L, "manager_id", null)
            .row("orders", "id", 10L, "user_id", 1L, "year", 2025)
            .row("orders", "id", 11L, "user_id", 1L, "year", 2024)
            .row("orders", "id", 12L, "user_id", 9L, "year", 2024)
            .row("order_items", "id", 100L, "order_id", 10L, "product_id", 50L)
            .row("order_items", "id", 101L, "order_id", 10L, "product_id", 51L)
            .row("order_items", "id", 102L, "order_id", 11L, "product_id", 50L)
            .row("order_items", "id", 103L, "order_id", 12L, "product_id", 52L)
            .row("products", "id", 50L)
            .row("products", "id", 51L)
            .row("products", "id", 52L)
            .filter("year >= 2025", row -> (int) row.get("year") >= 2025);

    @Test
    @DisplayName("#71a regression — children reached by descending get their other parents (products), "
            + "even beyond fk_depth, under KEEP_PARENTS")
    void childrenKeepTheirOtherParents() {
        Walk walk = walk(SHOP, ORDERS_2025, 1, KEEP_PARENTS, shop);

        assertThat(walk.ids("order_items")).containsExactly(100L, 101L);
        assertThat(walk.ids("products")).as("parents of the FK children").containsExactly(50L, 51L);
        assertThat(walk.ids("users")).as("user 1 and its whole manager chain").containsExactly(1L, 2L, 3L);
        assertThat(walk.ids("orders")).as("2024 orders are 2 hops away").containsExactly(10L);
        assertThat(walk.orphans).isEmpty();
    }

    @Test
    @DisplayName("ALLOW_ORPHANS keeps the radius strict and counts the references leaving it, per FK")
    void allowOrphansCountsDanglingReferences() {
        Walk walk = walk(SHOP, ORDERS_2025, 1, ALLOW_ORPHANS, shop);

        assertThat(walk.ids("users")).containsExactly(1L);
        assertThat(walk.ids("order_items")).containsExactly(100L, 101L);
        assertThat(walk.ids("products")).isEmpty();
        assertThat(walk.orphans).containsExactly(
                new OrphanReference("order_items", List.of("product_id"), "products", 2, 2),
                new OrphanReference("users", List.of("manager_id"), "users", 1, 1));
    }

    @Test
    @DisplayName("fk_depth is a radius in both directions : climb to user 1 then descend to its 2024 order (2 hops)")
    void radiusMixesUpAndDownMoves() {
        Walk depth2 = walk(SHOP, ORDERS_2025, 2, KEEP_PARENTS, shop);
        assertThat(depth2.ids("orders")).containsExactly(10L, 11L);
        assertThat(depth2.origin("orders", 11L)).isEqualTo(FkTraversal.Origin.FK_CHILD);
        assertThat(depth2.ids("order_items")).as("item 102 is 3 hops away").containsExactly(100L, 101L);

        Walk depth3 = walk(SHOP, ORDERS_2025, 3, KEEP_PARENTS, shop);
        assertThat(depth3.ids("order_items")).containsExactly(100L, 101L, 102L);
        assertThat(depth3.ids("users")).as("user 9 is never reached").doesNotContain(9L);
    }

    @Test
    @DisplayName("each row is accepted once, with the origin of its shortest path")
    void rowsAreAcceptedOnce() {
        Walk walk = walk(SHOP, ORDERS_2025, 3, KEEP_PARENTS, shop);

        assertThat(walk.accepted).doesNotHaveDuplicates();
        assertThat(walk.origin("orders", 10L)).isEqualTo(FkTraversal.Origin.DIRECT);
        assertThat(walk.origin("users", 2L)).isEqualTo(FkTraversal.Origin.FK_PARENT);
        assertThat(walk.origin("order_items", 100L)).isEqualTo(FkTraversal.Origin.FK_CHILD);
    }

    @Test
    @DisplayName("a manager cycle (1 ↔ 2) stops the unbounded parent climb")
    void parentCycleTerminates() {
        FakeSource cyclic = new FakeSource()
                .row("users", "id", 1L, "manager_id", 2L)
                .row("users", "id", 2L, "manager_id", 1L)
                .row("orders", "id", 10L, "user_id", 1L, "year", 2025)
                .filter("year >= 2025", row -> true);

        Walk walk = walk(SHOP, ORDERS_2025, 1, KEEP_PARENTS, cyclic);

        assertThat(walk.ids("users")).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("a parent missing from the source is requested once, even when referenced again 4 levels later")
    void parentMissingFromSourceIsRequestedOnce() {
        FakeSource broken = new FakeSource()
                .row("users", "id", 1L, "manager_id", 7L)
                .row("users", "id", 2L, "manager_id", 7L)
                .row("orders", "id", 10L, "user_id", 1L, "year", 2025)
                .row("orders", "id", 11L, "user_id", 2L, "year", 2024)
                .row("order_items", "id", 100L, "order_id", 10L, "product_id", 50L)
                .row("order_items", "id", 101L, "order_id", 11L, "product_id", 50L)
                .row("products", "id", 50L)
                .filter("year >= 2025", row -> (int) row.get("year") >= 2025);

        Walk walk = walk(SHOP, ORDERS_2025, 10, KEEP_PARENTS, broken);

        assertThat(walk.ids("users")).containsExactly(1L, 2L);
        assertThat(broken.reads).as("users by id : 1, then 7 (missing), then 2 — never 7 again")
                .filteredOn("users[id]"::equals).hasSize(3);
    }

    @Test
    @DisplayName("an int4 FK value matches an int8 primary key — no false orphan at the edge of the radius")
    void integerAndLongKeysMatch() {
        FakeSource mixed = new FakeSource()
                .row("users", "id", 1L, "manager_id", null)
                .row("orders", "id", 10L, "user_id", 1, "year", 2025);

        Walk walk = walk(SHOP, List.of(new TableExtractionConfig("users", null)), 1, ALLOW_ORPHANS, mixed);

        assertThat(walk.ids("orders")).containsExactly(10L);
        assertThat(walk.orphans).as("order 10 points to user 1, which is copied").isEmpty();
    }

    @Test
    @DisplayName("composite FK : the parent is matched on the full (order_id, line_no) tuple")
    void compositeForeignKey() {
        ForeignKey shipmentLine = new ForeignKey("shipments", List.of("order_id", "line_no"),
                "lines", List.of("order_id", "line_no"));
        DatabaseSchema schema = schema(
                new TableMetadata("lines", List.of(), List.of(), List.of("order_id", "line_no")),
                table("shipments", "id", shipmentLine));
        FakeSource source = new FakeSource()
                .row("lines", "order_id", 10L, "line_no", 1L)
                .row("lines", "order_id", 10L, "line_no", 2L)
                .row("shipments", "id", 1L, "order_id", 10L, "line_no", 2L)
                .filter("all", row -> true);

        Walk walk = walk(schema, List.of(new TableExtractionConfig("shipments", "all")), 1, KEEP_PARENTS, source);

        assertThat(walk.rows("lines")).extracting(r -> r.get("line_no")).containsExactly(2L);
    }

    @Test
    @DisplayName("a failed parent read aborts the walk — a partial parent set would leave dangling FKs (ADR-0018)")
    void failedParentReadAbortsTheWalk() {
        FakeSource failing = new FakeSource()
                .row("orders", "id", 10L, "user_id", 1L, "year", 2025)
                .filter("year >= 2025", row -> true)
                .failOn("users");

        assertThatThrownBy(() -> walk(SHOP, ORDERS_2025, 1, KEEP_PARENTS, failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("users")
                .hasRootCauseMessage("connection reset");
    }

    @Test
    @DisplayName("levelCompleted : a table the walk can no longer reach is closed (drives --resume checkpoints)")
    void tablesOutOfReachAreClosed() {
        DatabaseSchema schema = schema(table("audit_logs", "id"), table("orders", "id", ORDER_USER),
                table("users", "id"));
        FakeSource source = new FakeSource()
                .row("audit_logs", "id", 1L)
                .row("orders", "id", 10L, "user_id", 1L, "year", 2025)
                .row("users", "id", 1L)
                .filter("year >= 2025", row -> true);
        List<Set<String>> openPerLevel = new ArrayList<>();

        new FkTraversal(schema, 1, KEEP_PARENTS, () -> { }).run(
                List.of(new TableExtractionConfig("audit_logs", null), new TableExtractionConfig("orders", "year >= 2025")),
                source, new FkTraversal.RowSink() {
                    @Override
                    public void accept(ExtractedRow row, FkTraversal.Origin origin) {
                    }

                    @Override
                    public void levelCompleted(int level, Set<String> openTables) {
                        openPerLevel.add(openTables);
                    }
                });

        assertThat(openPerLevel.getFirst()).as("after the seeds, only users can still receive rows")
                .containsExactly("users");
        assertThat(openPerLevel.getLast()).isEmpty();
    }

    // ------------------------------------------------------------------------------------

    private static Walk walk(DatabaseSchema schema, List<TableExtractionConfig> seeds, int radius,
                             OrphanPolicy policy, FakeSource source) {
        Walk walk = new Walk();
        walk.orphans = new FkTraversal(schema, radius, policy, () -> { }).run(seeds, source, (row, origin) -> {
            walk.accepted.add(row.table() + ":" + row.data());
            walk.byTable.computeIfAbsent(row.table(), _ -> new ArrayList<>()).add(row.data());
            walk.origins.put(row.table() + ":" + row.data().get("id"), origin);
        });
        return walk;
    }

    private static final class Walk {
        final List<String> accepted = new ArrayList<>();
        final Map<String, List<Map<String, Object>>> byTable = new HashMap<>();
        final Map<String, FkTraversal.Origin> origins = new HashMap<>();
        List<OrphanReference> orphans;

        List<Map<String, Object>> rows(String table) {
            return byTable.getOrDefault(table, List.of());
        }

        Set<Object> ids(String table) {
            Set<Object> ids = new TreeSet<>();
            rows(table).forEach(r -> ids.add(r.get("id")));
            return ids;
        }

        FkTraversal.Origin origin(String table, Object id) {
            return origins.get(table + ":" + id);
        }
    }

    /** In-memory source : filters are named predicates, key reads match like {@code (cols) IN (keys)}. */
    private static final class FakeSource implements FkTraversal.RowReader {
        private final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        private final Map<String, Predicate<Map<String, Object>>> filters = new HashMap<>();
        private String failingTable;
        final List<String> reads = new ArrayList<>();

        FakeSource failOn(String table) {
            failingTable = table;
            return this;
        }

        FakeSource row(String table, Object... keyValues) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) row.put((String) keyValues[i], keyValues[i + 1]);
            tables.computeIfAbsent(table, _ -> new ArrayList<>()).add(row);
            return this;
        }

        FakeSource filter(String sql, Predicate<Map<String, Object>> predicate) {
            filters.put(sql, predicate);
            return this;
        }

        @Override
        public void readSeeds(String table, String filter, Consumer<ExtractedRow> consumer) {
            reads.add(table);
            Predicate<Map<String, Object>> keep = filter == null ? _ -> true : filters.get(filter);
            tables.getOrDefault(table, List.of()).stream().filter(keep)
                    .forEach(r -> consumer.accept(new ExtractedRow(table, r)));
        }

        @Override
        public synchronized void readWhereIn(String table, List<String> keyColumns, Collection<Object> keys,
                                             Consumer<ExtractedRow> consumer) {
            reads.add(table + keyColumns);
            if (table.equals(failingTable)) throw new IllegalStateException("connection reset");
            Set<Object> wanted = new LinkedHashSet<>(keys);
            for (Map<String, Object> r : tables.getOrDefault(table, List.of())) {
                ExtractedRow row = new ExtractedRow(table, r);
                if (wanted.contains(FkTraversal.key(row, keyColumns))) consumer.accept(row);
            }
        }
    }

    private static TableMetadata table(String name, String pk, ForeignKey... fks) {
        return new TableMetadata(name, List.of(), List.of(fks), pk);
    }

    private static DatabaseSchema schema(TableMetadata... tables) {
        Map<String, TableMetadata> byName = new LinkedHashMap<>();
        for (TableMetadata t : tables) byName.put(t.name(), t);
        return new DatabaseSchema(byName);
    }
}
