package com.fungle.brume.extraction;

import com.fungle.brume.schema.GraphAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks when a per-table PK set in {@code processedPrimaryKeys} can be evicted
 * because no future table in the run will consult it via FK-depth resolution.
 *
 * <p>Reference-counting algorithm — pays the full 100 % memory gain promised by
 * ticket {@code B4} (cf. {@code #11b / B4b}, closes the 50 % gap left by {@code #11}) :
 * <ol>
 *   <li>For each configured table {@code C}, compute its ancestor set via
 *       {@link GraphAnalyzer#resolveAncestors} up to the runtime {@code fkDepth} — the
 *       same depth bound used by {@link FkParentResolver}, so the tracker can never
 *       free an entry that the resolver might still consult.</li>
 *   <li>For each table {@code T} (configured or non-configured but reachable via FK
 *       depth from some configured table), initialise
 *       {@code refCount[T] = |{C ∈ configured : T ∈ ancestors[C] ∪ {C}}|}.</li>
 *   <li>When a configured table {@code T} has been fully processed (or skipped via
 *       {@code CheckpointService.shouldSkip} during a {@code --resume}), decrement
 *       {@code refCount[A]} for each {@code A ∈ ancestors[T] ∪ {T}}. When
 *       {@code refCount[A]} reaches zero, evict {@code processedPrimaryKeys.remove(A)}.</li>
 * </ol>
 *
 * <h2>Non-configured ancestors</h2>
 * If {@code C} is configured and references a parent {@code P} not declared in the
 * extraction config, {@code P} is still loaded transitively by
 * {@link FkParentResolver#resolveParentsForTable} and its PKs end up in
 * {@code processedPrimaryKeys[P]}. {@link GraphAnalyzer#resolveAncestors} returns
 * {@code P} (and its own ancestors up to {@code fkDepth}), so the ref-count covers
 * non-configured ancestors automatically — they get the same release treatment as
 * configured ones.
 *
 * <h2>Resume after checkpoint (#25)</h2>
 * Tables marked completed in a previous run are skipped via
 * {@link com.fungle.brume.checkpoint.CheckpointService#shouldSkip} ; the caller still
 * invokes {@link #onTableCompleted} for them so the ref-count stays self-consistent.
 * Their {@code processedPrimaryKeys} entry is empty at resume time (nothing populated
 * it), so the {@code remove()} is a no-op — but the decrement matters for tables
 * processed later in the same run.
 *
 * <h2>FK cycles</h2>
 * Tables flagged as cyclic by {@link GraphAnalyzer#detectCycles} are kept in the
 * ref-count book-keeping but their entries are never evicted from {@code
 * processedPrimaryKeys}, even when {@code refCount} reaches zero. A finer per-cycle
 * release would require coordinating eviction across all cycle participants ; the
 * gain is marginal (cycles are rare) and the safety risk of premature eviction is
 * non-trivial. Conservative default chosen.
 */
final class PkReleaseTracker {

    private static final Logger log = LoggerFactory.getLogger(PkReleaseTracker.class);

    private final Map<String, Integer> refCount = new HashMap<>();
    private final Map<String, Set<String>> ancestorsByTable = new HashMap<>();
    private final Set<String> cyclicTables;

    /**
     * Builds the ref-count map from the configured tables and the FK graph.
     *
     * @param configuredTables tables declared in the extraction config, in topological order
     *                         (the order doesn't matter for ref-count correctness but a
     *                         topological order makes the eviction trace easier to read)
     * @param schema           the analyzed source schema
     * @param fkDepth          maximum FK depth resolved by {@link FkParentResolver} at runtime
     * @param graphAnalyzer    shared component providing {@code resolveAncestors} and
     *                         {@code detectCycles}
     */
    PkReleaseTracker(List<String> configuredTables, DatabaseSchema schema, int fkDepth,
                     GraphAnalyzer graphAnalyzer) {
        this.cyclicTables = graphAnalyzer.detectCycles(schema);
        for (String c : configuredTables) {
            Set<String> ancestors = graphAnalyzer.resolveAncestors(c, schema, fkDepth);
            ancestorsByTable.put(c, ancestors);
        }
        for (String c : configuredTables) {
            // C itself contributes one count — processTable(C) consults processedPrimaryKeys[C]
            // both directly (its own rows) and via writeParentRows of subsequent descendants.
            refCount.merge(c, 1, Integer::sum);
            for (String ancestor : ancestorsByTable.get(c)) {
                refCount.merge(ancestor, 1, Integer::sum);
            }
        }
    }

    /**
     * Decrements the ref-count for {@code table} and each of its ancestors, evicting from
     * {@code processedPrimaryKeys} every entry whose count drops to zero (except those
     * involved in an FK cycle — conservative).
     *
     * <p>Called by {@link ChunkedTableProcessor#processAll} after each table is either
     * fully processed (and {@code CheckpointService.markCompleted} fired) or skipped via
     * {@code CheckpointService.shouldSkip} during a {@code --resume}.
     */
    void onTableCompleted(String table, Map<String, Set<Object>> processedPrimaryKeys) {
        Set<String> ancestors = ancestorsByTable.getOrDefault(table, Set.of());
        decrementAndRelease(table, processedPrimaryKeys);
        for (String ancestor : ancestors) {
            decrementAndRelease(ancestor, processedPrimaryKeys);
        }
    }

    private void decrementAndRelease(String t, Map<String, Set<Object>> processedPrimaryKeys) {
        Integer current = refCount.get(t);
        if (current == null) {
            // Untracked table — defensive : decrement on something never added means the
            // caller passed an unexpected table name (e.g. typo). Silently no-op rather
            // than throwing, the worst case is we miss one release opportunity.
            return;
        }
        int next = current - 1;
        if (next > 0) {
            refCount.put(t, next);
            return;
        }
        refCount.remove(t);
        if (cyclicTables.contains(t)) {
            log.debug("PkReleaseTracker: '{}' ref-count reached 0 but is cyclic — kept in pkIndex", t);
            return;
        }
        Set<Object> evicted = processedPrimaryKeys.remove(t);
        int size = evicted == null ? 0 : evicted.size();
        log.debug("PkReleaseTracker: released pkIndex for '{}' ({} PKs reclaimed)", t, size);
    }

    // ---- Test affordances (package-private) -------------------------------------------

    int refCountFor(String table) {
        return refCount.getOrDefault(table, 0);
    }

    Set<String> trackedTables() {
        return Collections.unmodifiableSet(refCount.keySet());
    }

    boolean isCyclic(String table) {
        return cyclicTables.contains(table);
    }
}
