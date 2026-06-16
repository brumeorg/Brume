package com.fungle.brume.report;

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of a completed (or failed) Brume execution.
 *
 * <p>Produced by {@link ExecutionReport#toSummary(PhaseTimings)} at the end of the pipeline.
 * All fields are set once and never mutated.
 *
 * @param sourceSchema     name of the source PostgreSQL schema that was replicated
 * @param targetSchema     name of the target PostgreSQL schema that received the data
 * @param success          {@code true} if the pipeline completed without a fatal error
 * @param failureCause     human-readable description of the failure, or {@code null} on success
 * @param timings          per-phase and total durations in milliseconds
 * @param tableStats       one {@link TableStats} entry per table touched during the run
 * @param strategyUsages   one {@link StrategyUsage} entry per (table, column, strategy) tuple
 * @param substitutionDict snapshot of substitution dictionary usage and top contributors
 * @param heap             JVM heap usage summary captured during the run
 * @param ddlExecution     replay result of the pg_dump DDL on the target
 * @param runtimeContext   meta-info (Brume version, config path, faker locale, DDL mode)
 * @param startedAt        wall-clock instant at which the {@link ExecutionReport} was created
 */
public record ExecutionSummary(
        String sourceSchema,
        String targetSchema,
        boolean success,
        String failureCause,
        PhaseTimings timings,
        List<TableStats> tableStats,
        List<StrategyUsage> strategyUsages,
        SubstitutionDictStats substitutionDict,
        HeapStats heap,
        DdlExecutionResult ddlExecution,
        BrumeRuntimeContext runtimeContext,
        Instant startedAt
) {

    public ExecutionSummary {
        substitutionDict = substitutionDict == null ? SubstitutionDictStats.empty() : substitutionDict;
        heap = heap == null ? HeapStats.empty() : heap;
        ddlExecution = ddlExecution == null ? DdlExecutionResult.empty() : ddlExecution;
        runtimeContext = runtimeContext == null ? BrumeRuntimeContext.empty() : runtimeContext;
    }

    public ExecutionSummary(
            String sourceSchema,
            String targetSchema,
            boolean success,
            String failureCause,
            PhaseTimings timings,
            List<TableStats> tableStats,
            List<StrategyUsage> strategyUsages,
            SubstitutionDictStats substitutionDict,
            HeapStats heap,
            Instant startedAt
    ) {
        this(sourceSchema, targetSchema, success, failureCause, timings,
                tableStats, strategyUsages, substitutionDict, heap,
                DdlExecutionResult.empty(), BrumeRuntimeContext.empty(), startedAt);
    }

    public ExecutionSummary(
            String sourceSchema,
            String targetSchema,
            boolean success,
            String failureCause,
            PhaseTimings timings,
            List<TableStats> tableStats,
            List<StrategyUsage> strategyUsages,
            SubstitutionDictStats substitutionDict,
            Instant startedAt
    ) {
        this(sourceSchema, targetSchema, success, failureCause, timings,
                tableStats, strategyUsages, substitutionDict, HeapStats.empty(),
                DdlExecutionResult.empty(), BrumeRuntimeContext.empty(), startedAt);
    }

    public ExecutionSummary(
            String sourceSchema,
            String targetSchema,
            boolean success,
            String failureCause,
            PhaseTimings timings,
            List<TableStats> tableStats,
            List<StrategyUsage> strategyUsages,
            Instant startedAt
    ) {
        this(sourceSchema, targetSchema, success, failureCause, timings,
                tableStats, strategyUsages, SubstitutionDictStats.empty(),
                HeapStats.empty(), DdlExecutionResult.empty(),
                BrumeRuntimeContext.empty(), startedAt);
    }

    /** Convenience accessor — never null thanks to the compact constructor. */
    public List<DdlFailure> ddlFailures() {
        return ddlExecution.failures();
    }

    /**
     * Returns the total number of rows extracted from the source, including rows added
     * by {@code FkParentResolver} and {@code FkChildResolver} to satisfy foreign-key
     * constraints.
     *
     * @return sum of {@code extracted + fkParents + fkChildren} across all tables
     */
    public long totalExtracted() {
        return tableStats.stream()
                .mapToLong(t -> t.extracted() + t.fkParents() + t.fkChildren())
                .sum();
    }

    /**
     * Returns the total number of rows successfully inserted in the target database.
     *
     * @return sum of {@code inserted} across all tables
     */
    public long totalInserted() {
        return tableStats.stream()
                .mapToLong(TableStats::inserted)
                .sum();
    }

    /**
     * Returns the total number of rows silently ignored by {@code ON CONFLICT DO NOTHING}.
     *
     * @return sum of {@code conflicts} across all tables
     */
    public long totalConflicts() {
        return tableStats.stream()
                .mapToLong(TableStats::conflicts)
                .sum();
    }

    /**
     * Returns the total number of JDBC batches that failed and were skipped.
     *
     * @return sum of {@code batchErrors} across all tables
     */
    public long totalBatchErrors() {
        return tableStats.stream()
                .mapToLong(TableStats::batchErrors)
                .sum();
    }

    /**
     * Returns {@code true} if there are any rows that were not written to the target,
     * either due to conflicts or batch errors.
     *
     * @return {@code true} if {@link #totalConflicts()} {@code > 0} or
     *         {@link #totalBatchErrors()} {@code > 0}
     */
    public boolean hasWarnings() {
        return totalConflicts() > 0 || totalBatchErrors() > 0;
    }
}

