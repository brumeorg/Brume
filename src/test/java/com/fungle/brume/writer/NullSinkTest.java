package com.fungle.brume.writer;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NullSink} — verifies that the sink counts rows in the
 * {@link ExecutionReport} without performing any IO.
 */
class NullSinkTest {

    private NullSink sink;
    private WriteContext context;
    private ExecutionReport report;

    @BeforeEach
    void setUp() {
        sink = new NullSink();
        report = new ExecutionReport("schema_a", "schema_a");
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 100, Collections.emptyList()),
                new AnonymizationConfig(Collections.emptyList(), Collections.emptyList()));
        context = new WriteContext("schema_a", config, new DatabaseSchema(new HashMap<>()), report);
    }

    private static ExtractedRow row(String table, long id) {
        return new ExtractedRow(table, Map.of("id", id));
    }

    @Test
    @DisplayName("Empty rows: writeChunk is a no-op")
    void emptyChunkIsNoop() {
        sink.open(context);
        sink.writeChunk("foo", List.of());
        sink.close();

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalInserted()).isZero();
    }

    @Test
    @DisplayName("Single chunk: rows counted as inserted, zero conflicts")
    void singleChunkCountedAsInserted() {
        sink.open(context);
        sink.writeChunk("foo", List.of(row("foo", 1L), row("foo", 2L), row("foo", 3L)));
        sink.close();

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalInserted()).isEqualTo(3);
        assertThat(summary.totalConflicts()).isZero();
        assertThat(summary.totalBatchErrors()).isZero();
    }

    @Test
    @DisplayName("Multiple chunks across tables: counts accumulate per table")
    void multipleChunksAcrossTables() {
        sink.open(context);
        sink.writeChunk("foo", List.of(row("foo", 1L), row("foo", 2L)));
        sink.writeChunk("bar", List.of(row("bar", 10L)));
        sink.writeChunk("foo", List.of(row("foo", 3L)));
        sink.close();

        ExecutionSummary summary = report.toSummary(new PhaseTimings(0, 0, 0, 0));
        assertThat(summary.totalInserted()).isEqualTo(4);
    }

    @Test
    @DisplayName("writeChunk before open() throws IllegalStateException")
    void writeBeforeOpenIsRejected() {
        assertThatThrownBy(() -> sink.writeChunk("foo", List.of(row("foo", 1L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before open()");
    }

    @Test
    @DisplayName("close() without open() is safe (idempotent)")
    void closeWithoutOpenIsSafe() {
        sink.close();
        // No exception expected
    }

    @Test
    @DisplayName("close() then writeChunk throws (state is reset)")
    void writeAfterCloseIsRejected() {
        sink.open(context);
        sink.writeChunk("foo", List.of(row("foo", 1L)));
        sink.close();

        assertThatThrownBy(() -> sink.writeChunk("foo", List.of(row("foo", 2L))))
                .isInstanceOf(IllegalStateException.class);
    }
}
