package com.fungle.brume.plan;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.OrphanPolicy;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.CursorReader;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.timeout.BoundedQueryExecutor;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.SinkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlanEstimator}.
 *
 * <p>No Spring context — the {@link CursorReader} (EXACT) and the {@link JdbcTemplate}
 * (ESTIMATE) are mocked. EXACT runs the {@code execute} FK walk on key columns (ADR-0050) ;
 * ESTIMATE reads {@code pg_class.reltuples} (B3 / ADR-0003).
 */
@ExtendWith(MockitoExtension.class)
class PlanEstimatorTest {

    private static final ForeignKey ITEM_ORDER = new ForeignKey("order_items", "order_id", "orders", "id");
    private static final ForeignKey ITEM_PRODUCT = new ForeignKey("order_items", "product_id", "products", "id");

    @Mock
    private CursorReader cursorReader;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private static BrumeProperties props(PlanMode mode) {
        return new BrumeProperties(
                "config.yaml", "secret", "fpekey1234567890",
                "HmacSHA256", "fr",
                0.0, 0L, 85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""),
                new BrumeProperties.SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new BrumeProperties.JdbcSinkProperties(CopyMode.NEVER)),
                new BrumeProperties.PlanProperties(mode)
        );
    }

    private PlanEstimator estimator(PlanMode mode) {
        return new PlanEstimator(cursorReader, BoundedQueryExecutor.passthrough(jdbcTemplate), props(mode));
    }

    private static AnonymizerConfig config(int fkDepth, OrphanPolicy policy, String... tables) {
        List<TableExtractionConfig> seeds = java.util.Arrays.stream(tables)
                .map(t -> new TableExtractionConfig(t, null)).toList();
        return new AnonymizerConfig(new ExtractionConfig(fkDepth, policy, 1000, 1000, 10_000, seeds),
                new AnonymizationConfig(List.of(), List.of()));
    }

    private static DatabaseSchema shop() {
        Map<String, TableMetadata> tables = new LinkedHashMap<>();
        tables.put("orders", new TableMetadata("orders", List.of(), List.of(), "id"));
        tables.put("order_items", new TableMetadata("order_items", List.of(), List.of(ITEM_ORDER, ITEM_PRODUCT), "id"));
        tables.put("products", new TableMetadata("products", List.of(), List.of(), "id"));
        return new DatabaseSchema(tables);
    }

    /** Seeds {@code count} rows of {@code table} (keys only) through the projected seed read. */
    private void seedRows(String table, int count) {
        doAnswer(inv -> {
            Consumer<ExtractedRow> consumer = inv.getArgument(6);
            for (long id = 1; id <= count; id++) consumer.accept(new ExtractedRow(table, Map.of("id", id)));
            return null;
        }).when(cursorReader).streamRows(eq("test_schema"), eq(table), isNull(), anyList(), anyList(), anyInt(), any());
    }

    @Test
    void exactMode_shouldReturnNegativeOneWhenTheWalkFails() {
        doThrow(new DataRetrievalFailureException("Table not found")).when(cursorReader)
                .streamRows(eq("test_schema"), eq("orders"), isNull(), anyList(), anyList(), anyInt(), any());

        PlanSummary summary = estimator(PlanMode.EXACT).estimate(config(3, null, "orders"), shop(), "test_schema", 3);

        PlanTableStats stats = summary.tableStats().getFirst();
        assertThat(stats.table()).isEqualTo("orders");
        assertThat(stats.plannedDirect()).isEqualTo(-1L);
        assertThat(stats.plannedTotal()).isEqualTo(-1L);
    }

    @Test
    void exactMode_shouldCountSeedRowsReadingKeyColumnsOnly() {
        seedRows("products", 42);

        PlanSummary summary = estimator(PlanMode.EXACT).estimate(config(3, null, "products"), shop(), "test_schema", 3);

        assertThat(summary.tableStats()).hasSize(1);
        assertThat(summary.tableStats().getFirst().plannedDirect()).isEqualTo(42L);
        assertThat(summary.totalPlanned()).isEqualTo(42L);
        verify(cursorReader).streamRows(eq("test_schema"), eq("products"), isNull(), eq(List.of("id")),
                eq(List.of()), anyInt(), any());
    }

    /**
     * FK children of a seed appear as "fk-child", and their other parents as "fk-parent" —
     * the same walk as execute (#71a, ADR-0050).
     */
    @Test
    void exactMode_shouldCountFkChildrenAndTheirParents() {
        seedRows("orders", 2);
        doAnswer(inv -> {
            Consumer<ExtractedRow> consumer = inv.getArgument(7);
            consumer.accept(new ExtractedRow("order_items", Map.of("id", 10L, "order_id", 1L, "product_id", 7L)));
            consumer.accept(new ExtractedRow("order_items", Map.of("id", 11L, "order_id", 2L, "product_id", 7L)));
            return null;
        }).when(cursorReader).streamWhereIn(eq("test_schema"), eq("order_items"), eq(List.of("order_id")),
                any(), anyList(), anyList(), anyInt(), any());
        doAnswer(inv -> {
            Consumer<ExtractedRow> consumer = inv.getArgument(7);
            consumer.accept(new ExtractedRow("products", Map.of("id", 7L)));
            return null;
        }).when(cursorReader).streamWhereIn(eq("test_schema"), eq("products"), eq(List.of("id")),
                any(), anyList(), anyList(), anyInt(), any());

        PlanSummary summary = estimator(PlanMode.EXACT).estimate(config(1, null, "orders"), shop(), "test_schema", 1);

        Map<String, PlanTableStats> byTable = new LinkedHashMap<>();
        summary.tableStats().forEach(s -> byTable.put(s.table(), s));
        assertThat(byTable.get("orders").origin()).isEqualTo("direct");
        assertThat(byTable.get("orders").plannedDirect()).isEqualTo(2L);
        assertThat(byTable.get("order_items").origin()).isEqualTo("fk-child");
        assertThat(byTable.get("order_items").plannedFkChildren()).isEqualTo(2L);
        assertThat(byTable.get("products").origin()).isEqualTo("fk-parent");
        assertThat(byTable.get("products").plannedFkParents()).isEqualTo(1L);
        assertThat(summary.totalPlanned()).isEqualTo(5L);
    }

    /**
     * ESTIMATE mode — queries {@code pg_class.reltuples} via the parameterized varargs overload
     * and never reads rows.
     */
    @Test
    void estimateMode_shouldReadReltuplesAndNeverReadRows() {
        when(jdbcTemplate.queryForObject(contains("pg_class"), eq(Long.class), any(Object[].class)))
                .thenReturn(50_000L);

        PlanSummary summary = estimator(PlanMode.ESTIMATE).estimate(config(3, null, "products"), shop(), "test_schema", 3);

        PlanTableStats products = summary.tableStats().getFirst();
        assertThat(products.table()).isEqualTo("products");
        assertThat(products.plannedDirect()).isEqualTo(50_000L);
        verifyNoInteractions(cursorReader);
    }

    @Test
    void estimateMode_shouldApplyTheRadiusAndOrphanPolicy() {
        when(jdbcTemplate.queryForObject(contains("pg_class"), eq(Long.class), any(Object[].class)))
                .thenReturn(10L);

        PlanSummary keep = estimator(PlanMode.ESTIMATE)
                .estimate(config(1, OrphanPolicy.KEEP_PARENTS, "orders"), shop(), "test_schema", 1);
        PlanSummary strict = estimator(PlanMode.ESTIMATE)
                .estimate(config(1, OrphanPolicy.ALLOW_ORPHANS, "orders"), shop(), "test_schema", 1);

        assertThat(keep.tableStats()).extracting(PlanTableStats::table)
                .as("products : parent of the FK child, beyond fk_depth").containsExactly("orders", "order_items", "products");
        assertThat(strict.tableStats()).extracting(PlanTableStats::table).containsExactly("orders", "order_items");
    }

    /**
     * Regression guard for #26b (audit § A3 + ticket T02) — schemaName must be validated
     * upfront so an invalid identifier surfaces with a clear message, not 5 stack frames
     * deep inside a SQL builder. Validation goes through {@code SqlIdentifiers.validate}.
     */
    @Test
    void estimate_shouldRejectInvalidSchemaName_withClearMessage() {
        assertThatThrownBy(() -> estimator(PlanMode.EXACT)
                .estimate(config(3, null, "orders"), shop(), "bad\0schema", 3))
                .isInstanceOf(IllegalArgumentException.class);
        // No read should have been issued — fail-fast happened before any SQL was built
        verifyNoInteractions(cursorReader);
    }
}
