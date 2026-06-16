package com.fungle.brume.extraction;

import com.fungle.brume.schema.GraphAnalyzer;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PkReleaseTracker} (#11b / B4b).
 *
 * <p>All scenarios use synthetic {@link DatabaseSchema} instances and never touch
 * a real database. The {@code processedPrimaryKeys} map is pre-populated with one
 * fake PK per table so that each {@code remove()} is observable.
 */
class PkReleaseTrackerTest {

    private GraphAnalyzer graphAnalyzer;

    @BeforeEach
    void setUp() {
        graphAnalyzer = new GraphAnalyzer();
    }

    // ---------------------------------------------------------------------------
    // Happy path : linear FK chain — evict only after the last descendant is done
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("linear chain A→B→C→D : all 4 entries are released only after D completes")
    void linearChainReleasesAtTailCompletion() {
        // FK direction : D → C → B → A (D references C, C references B, B references A)
        // Insertion order : A, B, C, D (parents first)
        DatabaseSchema schema = schemaOf(
                tableNoFk("A"),
                tableWithFk("B", "a_id", "A"),
                tableWithFk("C", "b_id", "B"),
                tableWithFk("D", "c_id", "C"));
        List<String> configured = List.of("A", "B", "C", "D");
        PkReleaseTracker tracker = new PkReleaseTracker(configured, schema, 5, graphAnalyzer);

        // ref-count expected :
        //   A : A itself + B, C, D have A as ancestor = 4
        //   B : B itself + C, D have B as ancestor = 3
        //   C : C itself + D has C as ancestor = 2
        //   D : D itself = 1
        assertThat(tracker.refCountFor("A")).isEqualTo(4);
        assertThat(tracker.refCountFor("B")).isEqualTo(3);
        assertThat(tracker.refCountFor("C")).isEqualTo(2);
        assertThat(tracker.refCountFor("D")).isEqualTo(1);

        Map<String, Set<Object>> pkIndex = newPkIndex("A", "B", "C", "D");

        tracker.onTableCompleted("A", pkIndex);
        assertThat(pkIndex.keySet()).containsExactlyInAnyOrder("A", "B", "C", "D");
        assertThat(tracker.refCountFor("A")).isEqualTo(3);

        tracker.onTableCompleted("B", pkIndex);
        assertThat(pkIndex.keySet()).containsExactlyInAnyOrder("A", "B", "C", "D");

        tracker.onTableCompleted("C", pkIndex);
        assertThat(pkIndex.keySet()).containsExactlyInAnyOrder("A", "B", "C", "D");

        tracker.onTableCompleted("D", pkIndex);
        // All ref-counts reached zero in cascade — full eviction in one step
        assertThat(pkIndex).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Branching : two siblings reference the same parent ⇒ parent kept until both done
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("siblings B and C both reference A : A kept until both leaves complete; B and C evicted individually")
    void branchingReleasesEachLeafIndependently() {
        DatabaseSchema schema = schemaOf(
                tableNoFk("A"),
                tableWithFk("B", "a_id", "A"),
                tableWithFk("C", "a_id", "A"));
        List<String> configured = List.of("A", "B", "C");
        PkReleaseTracker tracker = new PkReleaseTracker(configured, schema, 5, graphAnalyzer);

        // ref-count : A = 1 (self) + 2 (B,C ancestors) = 3; B = 1; C = 1
        assertThat(tracker.refCountFor("A")).isEqualTo(3);
        assertThat(tracker.refCountFor("B")).isEqualTo(1);
        assertThat(tracker.refCountFor("C")).isEqualTo(1);

        Map<String, Set<Object>> pkIndex = newPkIndex("A", "B", "C");

        tracker.onTableCompleted("A", pkIndex);
        assertThat(pkIndex.keySet()).containsExactlyInAnyOrder("A", "B", "C");

        tracker.onTableCompleted("B", pkIndex);
        // B is fully done — evicted. A still referenced by C (count = 1).
        assertThat(pkIndex.keySet()).containsExactlyInAnyOrder("A", "C");

        tracker.onTableCompleted("C", pkIndex);
        // C done, A's last reference removed.
        assertThat(pkIndex).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // FK cycle : tables involved in a cycle are never evicted (conservative)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("FK cycle A↔B : entries kept in pkIndex even when ref-count reaches zero")
    void cyclicTablesAreNeverEvicted() {
        // A.b_id -> B.id   AND   B.a_id -> A.id  (mutual cycle)
        DatabaseSchema schema = schemaOf(
                tableWithFk("A", "b_id", "B"),
                tableWithFk("B", "a_id", "A"));
        List<String> configured = List.of("A", "B");
        PkReleaseTracker tracker = new PkReleaseTracker(configured, schema, 5, graphAnalyzer);

        assertThat(tracker.isCyclic("A")).isTrue();
        assertThat(tracker.isCyclic("B")).isTrue();

        Map<String, Set<Object>> pkIndex = newPkIndex("A", "B");

        tracker.onTableCompleted("A", pkIndex);
        tracker.onTableCompleted("B", pkIndex);

        // Both ref-counts went to zero but eviction was skipped for both.
        assertThat(pkIndex.keySet()).containsExactlyInAnyOrder("A", "B");
    }

    // ---------------------------------------------------------------------------
    // Non-configured ancestor : reached via FK depth, must be tracked and evicted
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("non-configured ancestor X reached via FK depth : refCount built and X evicted when done")
    void nonConfiguredAncestorIsTracked() {
        // A is configured; A references X (not configured); X references Y (not configured).
        // FkParentResolver remonte A → X → Y up to fkDepth = 2.
        DatabaseSchema schema = schemaOf(
                tableNoFk("Y"),
                tableWithFk("X", "y_id", "Y"),
                tableWithFk("A", "x_id", "X"));
        List<String> configured = List.of("A"); // only A is configured
        PkReleaseTracker tracker = new PkReleaseTracker(configured, schema, 2, graphAnalyzer);

        // ref-count :
        //   A : 1 (self)
        //   X : 0 + 1 (A has X as ancestor) = 1
        //   Y : 0 + 1 (A has Y as ancestor at depth 2) = 1
        assertThat(tracker.refCountFor("A")).isEqualTo(1);
        assertThat(tracker.refCountFor("X")).isEqualTo(1);
        assertThat(tracker.refCountFor("Y")).isEqualTo(1);

        Map<String, Set<Object>> pkIndex = newPkIndex("A", "X", "Y");

        tracker.onTableCompleted("A", pkIndex);

        // All three reach zero in a single step
        assertThat(pkIndex).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Resume : skipped table still calls onTableCompleted so accounting stays consistent
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("resume scenario : skipped table B still triggers ref-count decrement; pkIndex (empty for skipped) stays empty without exception")
    void resumeSkippedTableDecrementsCorrectly() {
        DatabaseSchema schema = schemaOf(
                tableNoFk("A"),
                tableWithFk("B", "a_id", "A"),
                tableWithFk("C", "b_id", "B"));
        List<String> configured = List.of("A", "B", "C");
        PkReleaseTracker tracker = new PkReleaseTracker(configured, schema, 5, graphAnalyzer);

        // ref-count initial : A=3, B=2, C=1
        assertThat(tracker.refCountFor("A")).isEqualTo(3);
        assertThat(tracker.refCountFor("B")).isEqualTo(2);
        assertThat(tracker.refCountFor("C")).isEqualTo(1);

        // Simulate a resume where A and B were already completed in a previous run.
        // ChunkedTableProcessor.processAll calls onTableCompleted for skipped tables too —
        // their pkIndex entry is empty (nothing populated it this run), so the remove()
        // is a no-op but the ref-count decrement still matters for C below.
        Map<String, Set<Object>> pkIndex = new HashMap<>();
        // A and B intentionally absent — they were processed in a previous run

        tracker.onTableCompleted("A", pkIndex);
        tracker.onTableCompleted("B", pkIndex);

        // After skips : A's count went 3→2 (A and B both subtract from A); B's count 2→1.
        // Should not have thrown despite missing entries in pkIndex.
        assertThat(tracker.refCountFor("A")).isEqualTo(1);
        assertThat(tracker.refCountFor("B")).isEqualTo(1);

        // Now process C normally — it populates its own pkIndex entry first.
        pkIndex.put("C", oneFakeRow());
        tracker.onTableCompleted("C", pkIndex);

        // C's decrement cascades to B and A, all reach zero, all evicted.
        // (B and A had no pkIndex entries to begin with, so remove() is silent.)
        assertThat(pkIndex).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Helpers : synthetic schemas without DB
    // ---------------------------------------------------------------------------

    private static TableMetadata tableNoFk(String name) {
        return new TableMetadata(name, List.of(col("id")), List.of(), "id");
    }

    private static TableMetadata tableWithFk(String name, String fkCol, String parentTable) {
        return new TableMetadata(name,
                List.of(col("id"), col(fkCol)),
                List.of(new ForeignKey(name, fkCol, parentTable, "id")),
                "id");
    }

    private static ColumnMetadata col(String name) {
        return new ColumnMetadata(name, "bigint", false);
    }

    private static DatabaseSchema schemaOf(TableMetadata... tables) {
        Map<String, TableMetadata> map = new LinkedHashMap<>();
        for (TableMetadata t : tables) {
            map.put(t.name(), t);
        }
        return new DatabaseSchema(Map.copyOf(map));
    }

    private static Map<String, Set<Object>> newPkIndex(String... tables) {
        Map<String, Set<Object>> map = new HashMap<>();
        for (String t : tables) {
            map.put(t, oneFakeRow());
        }
        return map;
    }

    private static Set<Object> oneFakeRow() {
        Set<Object> set = new HashSet<>();
        set.add(1L);
        return set;
    }
}
