package com.fungle.brume.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable, thread-safe collector accumulating statistics throughout a Brume pipeline run.
 *
 * <p>All public methods are safe for concurrent use from multiple threads (including
 * virtual threads spawned by {@code FkParentResolver}). Counters are backed by
 * {@link ConcurrentHashMap} and {@link AtomicLong} — no external synchronization required.
 *
 * <p>At the end of the pipeline, call {@link #toSummary(PhaseTimings)} to obtain an
 * immutable {@link ExecutionSummary} snapshot suitable for rendering.
 */
public class ExecutionReport {

    // Per-table counters — key: table name
    private final ConcurrentHashMap<String, AtomicLong> extracted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> fkParents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> fkChildren = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> inserted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> conflicts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> batchErrors = new ConcurrentHashMap<>();

    /**
     * Composite key for the {@code strategyCounts} map — {@code (table, column, strategy)}.
     * #79c — pre-fix used a string-concat key {@code "table|column|strategy"} with a
     * {@code split("\\|", 3)} in {@link #toSummary} ; identifiers containing {@code "|"}
     * (PostgreSQL quoted identifier) collided silently. Records provide value-based
     * equals/hashCode with zero separator ambiguity.
     */
    private record StrategyKey(String table, String column, String strategy) {
    }

    // Strategy counters — key: StrategyKey (#79c, pre-fix was concat "table|column|strategy")
    private final ConcurrentHashMap<StrategyKey, AtomicLong> strategyCounts = new ConcurrentHashMap<>();

    private final Instant startedAt;
    private final String sourceSchema;
    private final String targetSchema;

    private volatile boolean success = true;
    private volatile String failureCause = null;
    private volatile SubstitutionDictStats substitutionDict = SubstitutionDictStats.empty();
    private volatile DdlExecutionResult ddlExecution = DdlExecutionResult.empty();
    private volatile BrumeRuntimeContext runtimeContext = BrumeRuntimeContext.empty();
    private final AtomicLong peakHeapUsedBytes = new AtomicLong(0L);
    private final AtomicLong maxHeapBytes = new AtomicLong(0L);
    private volatile boolean heapWarningTriggered = false;
    private volatile int heapWarningThresholdPercent = 0;

    /**
     * Creates a new {@code ExecutionReport} for a pipeline run.
     *
     * @param sourceSchema name of the source PostgreSQL schema being replicated
     * @param targetSchema name of the target PostgreSQL schema receiving the data
     */
    public ExecutionReport(String sourceSchema, String targetSchema) {
        this.sourceSchema = sourceSchema;
        this.targetSchema = targetSchema;
        this.startedAt = Instant.now();
    }

    /**
     * Records the number of rows read directly from a source table (before FK resolution).
     *
     * @param table name of the table
     * @param count number of rows extracted
     */
    public void recordExtracted(String table, long count) {
        extracted.computeIfAbsent(table, k -> new AtomicLong(0)).addAndGet(count);
    }

    /**
     * Records the number of FK parent rows added for a given parent table by
     * {@code FkParentResolver}.
     *
     * @param table name of the parent table
     * @param count number of parent rows resolved
     */
    public void recordFkParent(String table, long count) {
        fkParents.computeIfAbsent(table, k -> new AtomicLong(0)).addAndGet(count);
    }

    /**
     * Records the number of FK child rows added for a given child table by
     * {@code FkChildResolver}.
     *
     * @param table name of the child table
     * @param count number of child rows resolved
     */
    public void recordFkChild(String table, long count) {
        fkChildren.computeIfAbsent(table, k -> new AtomicLong(0)).addAndGet(count);
    }

    /**
     * Records the result of one batch write for a given table.
     *
     * @param table     name of the table
     * @param insertedCount  number of rows actually inserted in the batch
     * @param conflictCount  number of rows ignored by {@code ON CONFLICT DO NOTHING}
     */
    public void recordInserted(String table, long insertedCount, long conflictCount) {
        inserted.computeIfAbsent(table, k -> new AtomicLong(0)).addAndGet(insertedCount);
        conflicts.computeIfAbsent(table, k -> new AtomicLong(0)).addAndGet(conflictCount);
    }

    /**
     * Records a failed batch for the given table.
     *
     * @param table      name of the table
     * @param batchIndex zero-based index of the batch that failed
     */
    public void recordBatchError(String table, int batchIndex) {
        batchErrors.computeIfAbsent(table, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Records one cell value processed with the given strategy.
     *
     * <p>Uses a {@link ConcurrentHashMap} keyed by a typed {@link StrategyKey} record for
     * O(1) updates without scanning a list. The list view is only materialized in
     * {@link #toSummary(PhaseTimings)}.
     *
     * @param table    name of the table
     * @param column   name of the column
     * @param strategy name of the anonymization strategy
     */
    public void recordStrategy(String table, String column, String strategy) {
        StrategyKey key = new StrategyKey(table, column, strategy);
        strategyCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Marks the pipeline as failed with the given cause.
     *
     * @param cause human-readable description of the failure
     */
    public void markFailed(String cause) {
        this.success = false;
        this.failureCause = cause;
    }

    /**
     * Captures the latest substitution dictionary snapshot so it can be included in the final report.
     *
     * @param substitutionDict immutable dictionary stats snapshot
     */
    public void captureSubstitutionDictStats(SubstitutionDictStats substitutionDict) {
        this.substitutionDict = substitutionDict == null ? SubstitutionDictStats.empty() : substitutionDict;
    }

    /**
     * Captures the outcome of the schema-replication DDL phase so the rapport can surface
     * statements that were silently ignored under LENIENT mode (#28 / A17).
     */
    public void captureDdlExecution(DdlExecutionResult result) {
        this.ddlExecution = result == null ? DdlExecutionResult.empty() : result;
    }

    /**
     * Captures the runtime context (Brume version, config path, faker locale, DDL mode)
     * so the report can surface it in the masthead and meta strip.
     *
     * <p>Call once at pipeline startup from {@code ReplicationAgent}.
     */
    public void captureRuntimeContext(BrumeRuntimeContext context) {
        this.runtimeContext = context == null ? BrumeRuntimeContext.empty() : context;
    }

    /**
     * Records a heap sample and keeps the peak values observed during the run.
     */
    public void recordHeapSample(long usedBytes, long maxBytes, int warningThresholdPercent) {
        peakHeapUsedBytes.accumulateAndGet(Math.max(0L, usedBytes), Math::max);
        this.maxHeapBytes.accumulateAndGet(Math.max(0L, maxBytes), Math::max);
        this.heapWarningThresholdPercent = Math.max(this.heapWarningThresholdPercent, warningThresholdPercent);
    }

    /**
     * Marks the heap warning as emitted once and returns whether this call emitted it first.
     */
    public boolean markHeapWarningEmitted() {
        if (heapWarningTriggered) {
            return false;
        }
        heapWarningTriggered = true;
        return true;
    }

    /**
     * Builds an immutable {@link ExecutionSummary} snapshot from the current state.
     *
     * <p>All counters are read at this point and merged into {@link TableStats} records.
     * The set of known tables is the union of all tables that have been touched by any
     * counter (extracted, fkParents, inserted, conflicts, batchErrors).
     *
     * @param timings per-phase and total durations measured externally
     * @return immutable snapshot suitable for rendering or serialization
     */
    public ExecutionSummary toSummary(PhaseTimings timings) {
        // Collect the union of all table names across all counter maps
        java.util.Set<String> allTables = new java.util.LinkedHashSet<>();
        allTables.addAll(extracted.keySet());
        allTables.addAll(fkParents.keySet());
        allTables.addAll(fkChildren.keySet());
        allTables.addAll(inserted.keySet());
        allTables.addAll(conflicts.keySet());
        allTables.addAll(batchErrors.keySet());

        List<TableStats> stats = new ArrayList<>();
        for (String table : allTables) {
            stats.add(new TableStats(
                    table,
                    getOrZero(extracted, table),
                    getOrZero(fkParents, table),
                    getOrZero(fkChildren, table),
                    getOrZero(inserted, table),
                    getOrZero(conflicts, table),
                    getOrZero(batchErrors, table)
            ));
        }

        // Build StrategyUsage list from the counter map (#79c — typed record key, no split)
        List<StrategyUsage> usages = new ArrayList<>();
        for (Map.Entry<StrategyKey, AtomicLong> entry : strategyCounts.entrySet()) {
            StrategyKey k = entry.getKey();
            usages.add(new StrategyUsage(k.table(), k.column(), k.strategy(), entry.getValue().get()));
        }

        return new ExecutionSummary(
                sourceSchema,
                targetSchema,
                success,
                failureCause,
                timings,
                List.copyOf(stats),
                List.copyOf(usages),
                substitutionDict,
                new HeapStats(
                        peakHeapUsedBytes.get(),
                        maxHeapBytes.get(),
                        heapWarningTriggered,
                        heapWarningThresholdPercent
                ),
                ddlExecution,
                runtimeContext,
                startedAt
        );
    }

    /**
     * Returns the current value of the given counter for a table, or zero if absent.
     *
     * @param map   the counter map to look up
     * @param table the table name key
     * @return current counter value, or {@code 0} if the table has no entry
     */
    private static long getOrZero(ConcurrentHashMap<String, AtomicLong> map, String table) {
        AtomicLong counter = map.get(table);
        return counter == null ? 0L : counter.get();
    }
}

