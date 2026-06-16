package com.fungle.brume.plan;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.schema.model.DatabaseSchema;
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

import com.fungle.brume.schema.model.ForeignKey;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlanEstimator}.
 *
 * <p>No Spring context — Mockito is used to mock the JdbcTemplate. Covers both
 * {@link PlanMode#EXACT} (default) and {@link PlanMode#ESTIMATE} (B3 / ADR-0003).
 */
@ExtendWith(MockitoExtension.class)
class PlanEstimatorTest {

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

    /**
     * EXACT mode — when the JdbcTemplate throws a DataAccessException (e.g. table not found),
     * the PlanEstimator must record -1L as plannedDirect and not propagate the exception.
     */
    @Test
    void exactMode_shouldReturnNegativeOneWhenTableNotFound() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new DataRetrievalFailureException("Table not found"));

        PlanEstimator estimator = new PlanEstimator(
                jdbcTemplate, BoundedQueryExecutor.passthrough(jdbcTemplate), props(PlanMode.EXACT));

        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000, List.of(new TableExtractionConfig("orders", null))),
                new AnonymizationConfig(List.of(), List.of())
        );
        DatabaseSchema schema = new DatabaseSchema(
                Map.of("orders", new TableMetadata("orders", List.of(), List.of(), "id"))
        );

        PlanSummary summary = estimator.estimate(config, schema, "test_schema", 3);

        assertThat(summary).isNotNull();
        assertThat(summary.tableStats()).hasSize(1);
        PlanTableStats stats = summary.tableStats().get(0);
        assertThat(stats.table()).isEqualTo("orders");
        assertThat(stats.plannedDirect()).isEqualTo(-1L);
        assertThat(stats.plannedTotal()).isEqualTo(-1L);
    }

    /**
     * EXACT mode — when the query succeeds, the PlanEstimator returns the correct row count.
     */
    @Test
    void exactMode_shouldReturnCorrectCountWhenQuerySucceeds() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(42L);

        PlanEstimator estimator = new PlanEstimator(
                jdbcTemplate, BoundedQueryExecutor.passthrough(jdbcTemplate), props(PlanMode.EXACT));

        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000, List.of(new TableExtractionConfig("users", null))),
                new AnonymizationConfig(List.of(), List.of())
        );
        DatabaseSchema schema = new DatabaseSchema(
                Map.of("users", new TableMetadata("users", List.of(), List.of(), "id"))
        );

        PlanSummary summary = estimator.estimate(config, schema, "test_schema", 3);

        assertThat(summary.tableStats()).hasSize(1);
        assertThat(summary.tableStats().get(0).plannedDirect()).isEqualTo(42L);
        assertThat(summary.tableStats().get(0).plannedTotal()).isEqualTo(42L);
        assertThat(summary.totalPlanned()).isEqualTo(42L);
    }

    /**
     * ESTIMATE mode — queries {@code pg_class.reltuples} via the parameterized
     * varargs overload (schemaName, tableName as bind params), never the parameter-less
     * {@code COUNT(*)} form used by EXACT.
     */
    @Test
    void estimateMode_shouldReadReltuplesAndAvoidCountStar() {
        when(jdbcTemplate.queryForObject(
                contains("pg_class"), eq(Long.class), any(Object[].class)))
                .thenReturn(50_000L);

        PlanEstimator estimator = new PlanEstimator(
                jdbcTemplate, BoundedQueryExecutor.passthrough(jdbcTemplate), props(PlanMode.ESTIMATE));

        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000, List.of(new TableExtractionConfig("users", null))),
                new AnonymizationConfig(List.of(), List.of())
        );
        DatabaseSchema schema = new DatabaseSchema(
                Map.of("users", new TableMetadata("users", List.of(), List.of(), "id"))
        );

        PlanSummary summary = estimator.estimate(config, schema, "test_schema", 3);

        // ESTIMATE produced a value via reltuples
        assertThat(summary.tableStats()).hasSize(1);
        assertThat(summary.tableStats().get(0).plannedDirect()).isEqualTo(50_000L);

        // EXACT path (parameter-less COUNT) must NOT have been used
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class));
    }

    /**
     * FK child tables (tables whose FK column points to a direct seed) must appear in the plan
     * as "fk-child" with a non-zero plannedFkChildren — mirrors FkChildResolver (ADR-0038).
     */
    @Test
    void exactMode_shouldCountFkChildTables() {
        // orders (direct, 10 rows) ← order_items.order_id → orders.id (25 rows)
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(10L, 25L);

        PlanEstimator estimator = new PlanEstimator(
                jdbcTemplate, BoundedQueryExecutor.passthrough(jdbcTemplate), props(PlanMode.EXACT));

        ForeignKey fk = new ForeignKey("order_items", "order_id", "orders", "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "orders",      new TableMetadata("orders",      List.of(), List.of(),     "id"),
                "order_items", new TableMetadata("order_items", List.of(), List.of(fk),   "id")
        ));
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000, List.of(new TableExtractionConfig("orders", null))),
                new AnonymizationConfig(List.of(), List.of())
        );

        PlanSummary summary = estimator.estimate(config, schema, "test_schema", 3);

        assertThat(summary.tableStats()).hasSize(2);

        PlanTableStats ordersStat = summary.tableStats().stream()
                .filter(s -> "orders".equals(s.table())).findFirst().orElseThrow();
        assertThat(ordersStat.origin()).isEqualTo("direct");
        assertThat(ordersStat.plannedDirect()).isEqualTo(10L);
        assertThat(ordersStat.plannedFkChildren()).isEqualTo(0L);

        PlanTableStats itemsStat = summary.tableStats().stream()
                .filter(s -> "order_items".equals(s.table())).findFirst().orElseThrow();
        assertThat(itemsStat.origin()).isEqualTo("fk-child");
        assertThat(itemsStat.plannedFkChildren()).isEqualTo(25L);
        assertThat(itemsStat.plannedDirect()).isEqualTo(0L);

        assertThat(summary.totalPlanned()).isEqualTo(35L);
    }

    /**
     * Regression guard for #26b (audit § A3 + ticket T02) — schemaName must be validated
     * upfront so an invalid identifier surfaces with a clear message, not 5 stack frames
     * deep inside a SQL builder. Validation goes through {@code SqlIdentifiers.validate}.
     */
    @Test
    void estimate_shouldRejectInvalidSchemaName_withClearMessage() {
        PlanEstimator estimator = new PlanEstimator(
                jdbcTemplate, BoundedQueryExecutor.passthrough(jdbcTemplate), props(PlanMode.EXACT));

        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000, List.of(new TableExtractionConfig("orders", null))),
                new AnonymizationConfig(List.of(), List.of())
        );
        DatabaseSchema schema = new DatabaseSchema(
                Map.of("orders", new TableMetadata("orders", List.of(), List.of(), "id"))
        );

        assertThatThrownBy(() -> estimator.estimate(config, schema, "bad-schema; DROP", 3))
                .isInstanceOf(IllegalArgumentException.class);
        // No JDBC call should have been issued — fail-fast happened before any SQL was built
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class));
    }
}
