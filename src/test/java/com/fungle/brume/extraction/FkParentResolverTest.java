package com.fungle.brume.extraction;

import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FkParentResolver}.
 *
 * <p>Uses a mock {@link CursorReader} to test FK resolution logic without a real database.
 *
 * <p>Covers:
 * <ul>
 *   <li>Phase 1 fix — parents already in pkIndex (via addWithPk during initial extraction)
 *       are NOT re-fetched.</li>
 *   <li>Phase 2 fix — when two FK columns point to the same parent table, the parent row
 *       is stored exactly once despite parallel resolution.</li>
 * </ul>
 */
class FkParentResolverTest {

    private CursorReader cursorReader;
    private FkParentResolver resolver;

    @BeforeEach
    void setUp() {
        cursorReader = mock(CursorReader.class);
        resolver = new FkParentResolver(cursorReader);
    }

    /**
     * Phase 1 fix scenario:
     * - Initial extraction loaded user 1 via addWithPk (pkIndex populated).
     * - orders has FK user_id → users.id pointing to user 1.
     * - Expected: CursorReader is NOT called for user 1 (already present in pkIndex).
     */
    @Test
    @DisplayName("Phase 1 — parent already in pkIndex after addWithPk: no DB fetch triggered")
    void parentAlreadyInPkIndexIsNotRefetched() {
        // Arrange — schema: orders.user_id → users.id
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        TableMetadata usersMeta  = new TableMetadata("users",  List.of(), List.of(),        "id");
        TableMetadata ordersMeta = new TableMetadata("orders", List.of(), List.of(fk),       "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("users", usersMeta, "orders", ordersMeta));

        ExtractionResult result = new ExtractionResult();
        // Simulate Phase 1 fix: initial extraction already registered user 1 in pkIndex
        result.addWithPk(new ExtractedRow("users",  Map.of("id", 1L, "name", "Alice")), "id");
        result.addWithPk(new ExtractedRow("orders", Map.of("id", 10L, "user_id", 1L)),  "id");

        // Act
        resolver.resolve(result, schema, "test_brume", 3,
                new com.fungle.brume.report.ExecutionReport("test_brume", "test_brume"));

        // Assert — cursorReader must NOT be called for users (user 1 is already indexed)
        verify(cursorReader, times(0))
                .readByPrimaryKeys(anyString(), eq("users"), anyString(), org.mockito.ArgumentMatchers.any());
        assertThat(result.getRows("users")).hasSize(1);
    }

    /**
     * Phase 2 fix scenario (race condition):
     * - Two FK columns on the same child table point to the same parent table.
     *   (e.g. orders.user_id → users.id AND orders.creator_id → users.id)
     * - Both FK resolution tasks reference parent PK 1.
     * - Expected: CursorReader is called for user 1, but the row is stored exactly once.
     */
    @Test
    @DisplayName("Phase 2 — two FKs pointing to same parent PK: parent row stored exactly once")
    void twoFkColumnsPointingToSameParentProduceNoDuplicate() {
        // Arrange — orders has TWO FK columns both pointing to users.id
        ForeignKey fk1 = new ForeignKey("orders", "user_id",    "users", "id");
        ForeignKey fk2 = new ForeignKey("orders", "creator_id", "users", "id");
        TableMetadata usersMeta  = new TableMetadata("users",  List.of(), List.of(),          "id");
        TableMetadata ordersMeta = new TableMetadata("orders", List.of(), List.of(fk1, fk2),  "id");
        DatabaseSchema schema    = new DatabaseSchema(Map.of("users", usersMeta, "orders", ordersMeta));

        ExtractionResult result = new ExtractionResult();
        // One order referencing user 1 via BOTH user_id and creator_id
        result.addWithPk(new ExtractedRow("orders", Map.of(
                "id", 10L, "user_id", 1L, "creator_id", 1L)), "id");

        // CursorReader returns user 1 for any readByPrimaryKeys call on "users"
        ExtractedRow userRow = new ExtractedRow("users", Map.of("id", 1L, "name", "Alice"));
        when(cursorReader.readByPrimaryKeys(anyString(), eq("users"), eq("id"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(userRow));

        // Act
        resolver.resolve(result, schema, "test_brume", 3, new com.fungle.brume.report.ExecutionReport("test_brume", "test_brume"));

        // Assert — only ONE user row despite two FK resolution paths
        assertThat(result.getRows("users"))
                .as("User 1 must be stored exactly once even though two FKs reference it")
                .hasSize(1);
        assertThat(result.totalRowCount())
                .as("1 order + 1 user = 2 total rows")
                .isEqualTo(2);
        verify(cursorReader, times(1))
                .readByPrimaryKeys(anyString(), eq("users"), eq("id"), org.mockito.ArgumentMatchers.any());
    }

    /**
     * Verifies that a genuinely missing parent is fetched and added correctly.
     */
    @Test
    @DisplayName("Missing parent row is fetched from DB and added to result")
    void missingParentIsFetchedAndAdded() {
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        TableMetadata usersMeta  = new TableMetadata("users",  List.of(), List.of(),     "id");
        TableMetadata ordersMeta = new TableMetadata("orders", List.of(), List.of(fk),   "id");
        DatabaseSchema schema    = new DatabaseSchema(Map.of("users", usersMeta, "orders", ordersMeta));

        ExtractionResult result = new ExtractionResult();
        // User 1 is NOT pre-loaded — it must be fetched
        result.addWithPk(new ExtractedRow("orders", Map.of("id", 10L, "user_id", 1L)), "id");

        ExtractedRow parentUser = new ExtractedRow("users", Map.of("id", 1L, "name", "Alice"));
        when(cursorReader.readByPrimaryKeys(anyString(), eq("users"), eq("id"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(parentUser));

        resolver.resolve(result, schema, "test_brume", 3, new com.fungle.brume.report.ExecutionReport("test_brume", "test_brume"));

        assertThat(result.getRows("users")).hasSize(1);
        assertThat(result.containsPrimaryKey("users", "id", 1L)).isTrue();
    }

    /**
     * STREAMING dedup uses {@code Map<String, Set<Object>>} indexed by table (#11 / B4 / ADR-0001).
     * A parent already listed in the map for its table must not be re-fetched.
     */
    @Test
    @DisplayName("STREAMING — parent in alreadyProcessedPks (per-table Map): no DB fetch")
    void streamingResolveParentsForTableSkipsAlreadyProcessed() {
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        TableMetadata usersMeta = new TableMetadata("users", List.of(), List.of(), "id");
        TableMetadata ordersMeta = new TableMetadata("orders", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("users", usersMeta, "orders", ordersMeta));

        when(cursorReader.readDistinctColumnValues(
                anyString(), eq("orders"), eq("user_id"), any(), anyInt()))
                .thenReturn(Set.of(1L));

        // Per-table indexed dedup map: user 1 is already done
        Map<String, Set<Object>> alreadyProcessed = new HashMap<>();
        alreadyProcessed.put("users", Set.of(1L));

        ExtractionResult result = resolver.resolveParentsForTable(
                "test_brume", "orders", null, schema, 3, alreadyProcessed,
                new com.fungle.brume.report.ExecutionReport("test_brume", "test_brume"));

        verify(cursorReader, times(0))
                .readByPrimaryKeys(anyString(), eq("users"), anyString(), any());
        assertThat(result.totalRowCount())
                .as("user 1 already processed for table users — no parent row fetched")
                .isZero();
    }

    /**
     * Symmetric to the previous test: when the parent is NOT in the per-table map,
     * the row is fetched from the DB.
     */
    @Test
    @DisplayName("STREAMING — parent absent from alreadyProcessedPks: row fetched from DB")
    void streamingResolveParentsForTableFetchesMissingParent() {
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        TableMetadata usersMeta = new TableMetadata("users", List.of(), List.of(), "id");
        TableMetadata ordersMeta = new TableMetadata("orders", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("users", usersMeta, "orders", ordersMeta));

        when(cursorReader.readDistinctColumnValues(
                anyString(), eq("orders"), eq("user_id"), any(), anyInt()))
                .thenReturn(Set.of(1L));
        ExtractedRow user1 = new ExtractedRow("users", Map.of("id", 1L, "name", "Alice"));
        when(cursorReader.readByPrimaryKeys(anyString(), eq("users"), eq("id"), any()))
                .thenReturn(List.of(user1));

        // Empty per-table dedup map: nothing has been processed yet
        Map<String, Set<Object>> alreadyProcessed = new HashMap<>();

        ExtractionResult result = resolver.resolveParentsForTable(
                "test_brume", "orders", null, schema, 3, alreadyProcessed,
                new com.fungle.brume.report.ExecutionReport("test_brume", "test_brume"));

        assertThat(result.getRows("users")).hasSize(1);
    }

    /**
     * Regression guard for #23c (audit § B3, ADR-0018) — a virtual-thread FK fetch that
     * fails (lock timeout, deadlock, transient DB error) must abort the pipeline. Continuing
     * with a partial ExtractionResult would silently produce dangling FK references on the
     * target (masked by session_replication_role=replica at write time).
     */
    @Test
    @DisplayName("Failure inside an FK resolution task aborts the pipeline (audit § B3)")
    void resolveConsolidated_propagatesFailure_whenChildFkFetchFails() {
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        TableMetadata usersMeta  = new TableMetadata("users",  List.of(), List.of(),     "id");
        TableMetadata ordersMeta = new TableMetadata("orders", List.of(), List.of(fk),    "id");
        DatabaseSchema schema    = new DatabaseSchema(Map.of("users", usersMeta, "orders", ordersMeta));

        ExtractionResult result = new ExtractionResult();
        result.addWithPk(new ExtractedRow("orders", Map.of("id", 10L, "user_id", 1L)), "id");

        // Simulate a transient DB error inside the FK fetch — a lock timeout / deadlock equivalent.
        when(cursorReader.readByPrimaryKeys(anyString(), eq("users"), eq("id"), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("simulated lock timeout"));

        assertThatThrownBy(() ->
                resolver.resolve(result, schema, "test_brume", 3,
                        new com.fungle.brume.report.ExecutionReport("test_brume", "test_brume")))
                .as("FK resolution failure must abort the pipeline — no silent partial result")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("FK parent resolution failed")
                .hasMessageContaining("simulated lock timeout");
    }
}

