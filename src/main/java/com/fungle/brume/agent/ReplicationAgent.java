package com.fungle.brume.agent;

import com.fungle.brume.anonymization.AnonymizationEngine;
import com.fungle.brume.anonymization.FkStrategyPropagator;
import com.fungle.brume.audit.QuasiIdDetector;
import com.fungle.brume.checkpoint.CheckpointService;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.ConfigValidator;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.SchemaConfigValidator;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.extraction.ChunkedTableProcessor;
import com.fungle.brume.extraction.ExtractionEngine;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.monitoring.HeapMonitor;
import com.fungle.brume.output.BrumeJsonOutput;
import com.fungle.brume.output.OutputMode;
import com.fungle.brume.plan.PiiDetector;
import com.fungle.brume.plan.PlanEstimator;
import com.fungle.brume.preflight.PreflightCheckRunner;
import com.fungle.brume.shutdown.CancellationToken;
import com.fungle.brume.state.ExecutionStateWriter;
import com.fungle.brume.report.ComparisonRow;
import com.fungle.brume.report.ComparisonSummary;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.Insight;
import com.fungle.brume.report.InsightGenerator;
import com.fungle.brume.report.BrumeRuntimeContext;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.report.PiiWarning;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.report.QuasiIdWarning;
import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.report.ReportRenderer;
import com.fungle.brume.report.TableStats;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.schema.SchemaAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.writer.HybridWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.fungle.brume.command.CommandEnum.PLAN;

/**
 * Orchestrates the full Brume replication pipeline.
 *
 * <p>Execution order (fail-fast before any DB connection is opened):
 * <ol>
 *   <li>Load and validate the anonymization config ({@code config.yaml}) — fail-fast</li>
 *   <li>Replicate the schema on the target via {@link SchemaReplicator} (pg_dump --schema-only)</li>
 *   <li>Analyze the source schema via {@link SchemaAnalyzer}</li>
 *   <li>Run pre-flight plan: {@link PlanEstimator} + {@link PiiDetector} (read-only)</li>
 *   <li>If {@code --plan} flag is set, render the plan and exit without DB writes</li>
 *   <li>Extract a coherent, FK-complete dataset via {@link ExtractionEngine}</li>
 *   <li>Anonymize the extracted rows via {@link AnonymizationEngine}</li>
 *   <li>Write the anonymized rows to the target via {@link HybridWriter}</li>
 *   <li>Log the total elapsed time</li>
 * </ol>
 *
 * <p>Entry point called by {@link com.fungle.brume.BrumeApplication} on startup.
 */
@Component
public class ReplicationAgent {

    private static final Logger log = LoggerFactory.getLogger(ReplicationAgent.class);

    private final SchemaReplicator schemaReplicator;
    private final ConfigLoader configLoader;
    private final ConfigValidator configValidator;
    private final SchemaAnalyzer schemaAnalyzer;
    private final ExtractionEngine extractionEngine;
    private final ChunkedTableProcessor chunkedTableProcessor;
    private final AnonymizationEngine anonymizationEngine;
    private final FkStrategyPropagator fkStrategyPropagator;
    private final SchemaConfigValidator schemaConfigValidator;
    private final HybridWriter hybridWriter;
    private final SubstitutionDictionary substitutionDictionary;
    private final ReplicationProperties replicationProperties;
    private final BrumeProperties brumeProperties;
    private final Optional<DataSource> targetDataSource;
    private final ReportRenderer reportRenderer;
    private final PlanEstimator planEstimator;
    private final PiiDetector piiDetector;
    private final QuasiIdDetector quasiIdDetector;
    private final HeapMonitor heapMonitor;
    private final ObjectMapper objectMapper;
    private final PreflightCheckRunner preflightCheckRunner;
    private final CancellationToken cancellationToken;
    private final CheckpointService checkpointService;
    private final ExecutionStateWriter executionStateWriter;

    /**
     * Constructs a {@code ReplicationAgent} with all required pipeline components.
     *
     * @param schemaReplicator      replicates the source schema to the target via pg_dump
     * @param configLoader          loads the anonymization config from {@code config.yaml}
     * @param configValidator       validates the config before any DB connection is opened
     * @param schemaAnalyzer        analyzes the source database schema
     * @param extractionEngine      extracts a coherent, FK-complete dataset from the source
     * @param anonymizationEngine   anonymizes extracted rows according to the config
     * @param hybridWriter          writes the anonymized rows to the target
     * @param replicationProperties replication configuration (schema name, source credentials)
     * @param targetDataSource      target database data source — {@link Optional#empty()} when
     *                              {@code brume.sink.type} is {@code DUMP} or {@code NULL}
     *                              (the bean is conditional on JDBC, ADR-0028).
     *                              Used only to pass through to {@link SchemaReplicator#replicate}
     *                              in the JDBC branch.
     * @param reportRenderer        renders the execution report to console and optionally JSON
     * @param planEstimator         computes exact planned row counts before extraction
     * @param piiDetector           detects PII columns not covered by anonymization rules
     */
    public ReplicationAgent(
            SchemaReplicator schemaReplicator,
            ConfigLoader configLoader,
            ConfigValidator configValidator,
            SchemaAnalyzer schemaAnalyzer,
            ExtractionEngine extractionEngine,
            ChunkedTableProcessor chunkedTableProcessor,
            AnonymizationEngine anonymizationEngine,
            FkStrategyPropagator fkStrategyPropagator,
            SchemaConfigValidator schemaConfigValidator,
            HybridWriter hybridWriter,
            SubstitutionDictionary substitutionDictionary,
            ReplicationProperties replicationProperties,
            BrumeProperties brumeProperties,
            @Qualifier("targetDataSource") Optional<DataSource> targetDataSource,
            ReportRenderer reportRenderer,
            PlanEstimator planEstimator,
            PiiDetector piiDetector,
            QuasiIdDetector quasiIdDetector,
            HeapMonitor heapMonitor,
            ObjectMapper objectMapper,
            PreflightCheckRunner preflightCheckRunner,
            CancellationToken cancellationToken,
            CheckpointService checkpointService,
            ExecutionStateWriter executionStateWriter) {
        this.schemaReplicator = schemaReplicator;
        this.configLoader = configLoader;
        this.configValidator = configValidator;
        this.schemaAnalyzer = schemaAnalyzer;
        this.extractionEngine = extractionEngine;
        this.chunkedTableProcessor = chunkedTableProcessor;
        this.anonymizationEngine = anonymizationEngine;
        this.fkStrategyPropagator = fkStrategyPropagator;
        this.schemaConfigValidator = schemaConfigValidator;
        this.hybridWriter = hybridWriter;
        this.substitutionDictionary = substitutionDictionary;
        this.replicationProperties = replicationProperties;
        this.brumeProperties = brumeProperties;
        this.targetDataSource = targetDataSource;
        this.reportRenderer = reportRenderer;
        this.planEstimator = planEstimator;
        this.piiDetector = piiDetector;
        this.quasiIdDetector = quasiIdDetector;
        this.heapMonitor = heapMonitor;
        this.objectMapper = objectMapper;
        this.preflightCheckRunner = preflightCheckRunner;
        this.cancellationToken = cancellationToken;
        this.checkpointService = checkpointService;
        this.executionStateWriter = executionStateWriter;
    }

    /**
     * Runs the full replication + anonymization pipeline.
     *
     * <p>Config validation is performed first, before any database connection is opened,
     * to provide a fast and clear failure mode when the config is invalid.
     *
     * <p>An {@link ExecutionReport} is created at the start and passed through every pipeline
     * phase. At the end, {@link ReportRenderer} produces a structured summary on the console
     * and optionally in a JSON file. If any step throws, the report is marked as failed and
     * rendered before the exception is re-thrown.
     *
     * <p>If the {@code --plan} CLI argument is present, the pipeline renders the pre-execution
     * plan and exits before any database writes.
     * @throws IOException if the pg_dump subprocess cannot be started or its output cannot be read
     * @throws SQLException if a database operation fails during schema replication
     * @throws InterruptedException if the current thread is interrupted during schema replication
     */
    public void run(CommandEnum command) throws IOException, SQLException, InterruptedException {
        long start = System.currentTimeMillis();

        if (command == CommandEnum.DRY_RUN) {
            log.info("Mode: dry-run — pipeline runs end-to-end but no rows will be persisted to the target");
        }

        // Create the execution report — collects stats throughout the pipeline
        // Both source and target use the same schema name; the distinction is on the DB connection URL.
        ExecutionReport report = new ExecutionReport(
                replicationProperties.schema(),
                replicationProperties.schema());
        BrumeRuntimeContext runtimeContext = BrumeRuntimeContext.of(brumeProperties, replicationProperties, command);
        report.captureRuntimeContext(runtimeContext);

        try {
            heapMonitor.sample(report, "startup");
            AnonymizerConfig config = configLoader.load();
            configValidator.validate(config);
            log.info("Config loaded and validated successfully");

            preflightCheckRunner.run();
            cancellationToken.checkpoint();

            if (brumeProperties.sink().type() == com.fungle.brume.writer.SinkType.JDBC) {
                ReplicationProperties.Source source = replicationProperties.source();
                DataSource targetDs = targetDataSource.orElseThrow(() -> new IllegalStateException(
                        "targetDataSource bean is required for sink.type=JDBC but was not wired. "
                                + "This indicates a misconfiguration of DataSourceConfig (see ADR-0028)."));
                com.fungle.brume.report.DdlExecutionResult ddlResult = schemaReplicator.replicate(
                        source.url(),
                        source.username(),
                        source.password(),
                        replicationProperties.schema(),
                        targetDs
                );
                report.captureDdlExecution(ddlResult);
                if (ddlResult.ignored() > 0) {
                    log.warn("DDL phase ignored {} statement(s) under LENIENT mode — see rapport for details (#28/A17)",
                            ddlResult.ignored());
                }
            } else {
                log.info("Sink mode is {} — skipping target schema replication; DDL will be written into the dump file",
                        brumeProperties.sink().type());
            }

            cancellationToken.checkpoint();

            DatabaseSchema schema = schemaAnalyzer.analyze(replicationProperties.schema());

            config = fkStrategyPropagator.propagate(config, schema);

            schemaConfigValidator.validate(config, schema, brumeProperties.strictConfig());

            PlanSummary planSummary = planEstimator.estimate(
                    config, schema, replicationProperties.schema(), replicationProperties.fkDepth());
            List<PiiWarning> piiWarnings = piiDetector.detect(
                    schema, config, replicationProperties.schema());
            // Log PII warnings immediately
            piiWarnings.forEach(w -> log.warn("[PII] {}.{} — no anonymization rule (matched: {})",
                    w.table(), w.column(), w.matchedPattern()));

            List<QuasiIdWarning> quasiIdWarnings = quasiIdDetector.detect(
                    schema, config, replicationProperties.schema());
            quasiIdWarnings.forEach(w -> log.warn(
                    "[QUASI-ID] {}.{} ({}, strategy={}) — name matches re-identification "
                            + "pattern '{}' and strategy preserves correlation",
                    w.table(), w.column(), w.dataType(),
                    w.effectiveStrategy() == null ? "KEEP (implicit)" : w.effectiveStrategy(),
                    w.matchedPattern()));

            PlanSummary planForOutput = new PlanSummary(
                    planSummary.sourceSchema(),
                    planSummary.tableStats(),
                    piiWarnings,
                    quasiIdWarnings,
                    runtimeContext,
                    planSummary.estimatedAt());

            OutputMode outputMode = brumeProperties.output().mode();

            if (PLAN.equals(command)) {
                try {
                    reportRenderer.renderPlan(planForOutput);
                } catch (Exception e) {
                    log.warn("renderPlan failed: {}", e.getMessage());
                }
                if (outputMode == OutputMode.JSON) {
                    emitJsonOutput(BrumeJsonOutput.planOnly(planForOutput));
                }
                return;
            }

            try {
                reportRenderer.renderPlan(planForOutput);
            } catch (Exception e) {
                log.warn("renderPlan (pre-flight HTML) failed: {}", e.getMessage());
            }

            log.info("Plan: {} total rows planned across {} tables",
                    planSummary.totalPlanned(), planSummary.tableStats().size());

            enforceMaxTargetRows(planSummary);
            heapMonitor.sample(report, "plan-ready");

            long extractionMs;
            long anonymizationMs;
            long writeMs;

            checkpointService.boot(schema);

            if (brumeProperties.sink().stripTimestamps()) {
                warnTablesWithoutSinglePk(config, schema);
            }

            if (brumeProperties.pipelineMode() == PipelineMode.STREAMING) {
                long streamingStart = System.currentTimeMillis();
                log.info("Pipeline mode: STREAMING");
                chunkedTableProcessor.processAll(config, schema, report);
                extractionMs = System.currentTimeMillis() - streamingStart;
                anonymizationMs = 0L;
                writeMs = 0L;
                heapMonitor.sample(report, "streaming-finished");
            } else {
                if (brumeProperties.checkpoint().enabled()) {
                    log.warn("Pipeline mode BATCH does not support --resume in V1 (tracked in "
                            + "#25h). The checkpoint will not be read or written — running a full "
                            + "extract/anonymize/write cycle.");
                }
                log.info("Pipeline mode: BATCH");
                long extractStart = System.currentTimeMillis();
                OrderedExtractionResult extracted = extractionEngine.extract(config, schema, report);
                extractionMs = System.currentTimeMillis() - extractStart;
                heapMonitor.sample(report, "batch-extraction");

                long anonStart = System.currentTimeMillis();
                OrderedExtractionResult anonymized = anonymizationEngine.anonymize(extracted, config.anonymization(), report);
                anonymizationMs = System.currentTimeMillis() - anonStart;
                heapMonitor.sample(report, "batch-anonymization");

                long writeStart = System.currentTimeMillis();
                hybridWriter.write(anonymized, config, schema, report);
                writeMs = System.currentTimeMillis() - writeStart;
                heapMonitor.sample(report, "batch-write");
            }

            long elapsed = System.currentTimeMillis() - start;
            PhaseTimings timings = new PhaseTimings(extractionMs, anonymizationMs, writeMs, elapsed);
            heapMonitor.sample(report, "final-summary");
            captureSubstitutionDictionaryStats(report);
            ExecutionSummary summary = report.toSummary(timings);
            try {
                reportRenderer.render(summary);
            } catch (Exception e) {
                log.warn("ReportRenderer failed (pipeline result unaffected): {}", e.getMessage());
            }

            ComparisonSummary comparison = null;
            try {
                comparison = buildComparison(planSummary, summary, piiWarnings, schema);
                reportRenderer.renderComparison(comparison);
            } catch (Exception e) {
                log.warn("renderComparison failed (pipeline result unaffected): {}", e.getMessage());
            }

            if (outputMode == OutputMode.JSON) {
                emitJsonOutput(BrumeJsonOutput.full(planForOutput, summary, comparison));
            }

            if (command == CommandEnum.EXECUTE) {
                executionStateWriter.write(config, replicationProperties.schema());
            }

            log.info("Brume pipeline completed in {} ms", elapsed);

        } catch (Exception e) {
            report.markFailed(e.getMessage());
            heapMonitor.sample(report, "failure");
            captureSubstitutionDictionaryStats(report);
            PhaseTimings timings = new PhaseTimings(0, 0, 0, System.currentTimeMillis() - start);
            try {
                reportRenderer.render(report.toSummary(timings));
            } catch (Exception renderEx) {
                log.warn("ReportRenderer failed during error handling: {}", renderEx.getMessage());
            }
            throw e;
        }
    }

    private void captureSubstitutionDictionaryStats(ExecutionReport report) {
        report.captureSubstitutionDictStats(substitutionDictionary.snapshot(10));
    }

    /**
     * Emits the JSON wrapper on stdout as a single line of JSON. Writes directly through
     * {@link System#out} (bypassing SLF4J) so the result is independent from the {@code -v}/
     * {@code -q} log levels and never mixes with log JSON lines on stderr. ADR-0030.
     *
     * <p>Serialization failures are caught and logged as ERROR — the wrapper is the run's
     * machine-readable contract; if it can't be produced, the caller (script) sees an empty
     * stdout and the error on stderr.
     */
    private void emitJsonOutput(BrumeJsonOutput wrapper) {
        try {
            String json = objectMapper.writeValueAsString(wrapper);
            System.out.println(json);
        } catch (Exception e) {
            log.error("Failed to serialize JSON output: {}", e.getMessage(), e);
        }
    }

    private void enforceMaxTargetRows(PlanSummary planSummary) {
        long maxTargetRows = brumeProperties.maxTargetRows();
        if (maxTargetRows > 0 && planSummary.totalPlanned() > maxTargetRows) {
            throw new com.fungle.brume.error.MaxTargetRowsExceededException(
                    "Planned dataset size (" + planSummary.totalPlanned() + " rows) exceeds "
                            + "brume.max-target-rows=" + maxTargetRows + ".",
                    "Tighten extraction filters or raise the limit explicitly.");
        }
    }

    /**
     * Builds a {@link ComparisonSummary} by matching the pre-execution plan against the
     * post-execution summary on a per-table basis, then generating natural-language
     * {@link Insight}s from the comparison metrics and the database schema.
     *
     * <p>Tables present in the plan but absent from execution appear with {@code actual = 0}.
     * Tables present in execution but absent from the plan appear with {@code planned = 0}.
     *
     * @param plan        the pre-execution plan snapshot
     * @param exec        the post-execution summary
     * @param piiWarnings PII warnings detected before the run
     * @param schema      the source database schema (used for FK attribution in insights)
     * @return the comparison summary including natural-language observations
     */
    private ComparisonSummary buildComparison(PlanSummary plan, ExecutionSummary exec,
                                               List<PiiWarning> piiWarnings,
                                               DatabaseSchema schema) {
        // Index plan stats by table name
        Map<String, PlanTableStats> planByTable = plan.tableStats().stream()
                .collect(Collectors.toMap(PlanTableStats::table, Function.identity()));

        // Index execution stats by table name
        Map<String, TableStats> execByTable = exec.tableStats().stream()
                .collect(Collectors.toMap(TableStats::table, Function.identity()));

        // Union of all table names in plan and execution, preserving plan order
        Set<String> allTables = new LinkedHashSet<>();
        plan.tableStats().stream().map(PlanTableStats::table).forEach(allTables::add);
        exec.tableStats().stream().map(TableStats::table).forEach(allTables::add);

        List<ComparisonRow> rows = new ArrayList<>();
        for (String table : allTables) {
            PlanTableStats ps = planByTable.get(table);
            TableStats ts = execByTable.get(table);

            long planned  = (ps != null && ps.plannedTotal() >= 0) ? ps.plannedTotal() : 0L;
            long actual   = ts != null ? ts.extracted() + ts.fkParents() + ts.fkChildren() : 0L;
            long inserted = ts != null ? ts.inserted() : 0L;
            long conflicts = ts != null ? ts.conflicts() : 0L;
            double delta  = planned > 0 ? ((actual - planned) / (double) planned) * 100.0 : 0.0;

            rows.add(new ComparisonRow(table, planned, actual, inserted, conflicts, delta));
        }

        List<Insight> insights = new InsightGenerator(schema).generate(rows, exec, piiWarnings, planByTable);
        return new ComparisonSummary(plan, exec, rows, piiWarnings, insights);
    }

    /**
     * #30b — surfaces the tables configured for extraction that lack a single-column
     * primary key, when {@code brume.sink.strip-timestamps=true}. For these tables the
     * dump's intra-table {@code COPY} block ordering is plan-dependent and two runs may
     * differ. Caller chose strict-determinism mode so the absence of guarantees on a
     * subset of tables is worth a WARN — not a fatal error, the run still completes.
     */
    private void warnTablesWithoutSinglePk(
            com.fungle.brume.config.model.AnonymizerConfig config,
            com.fungle.brume.schema.model.DatabaseSchema schema) {
        if (config.extraction() == null || config.extraction().tables() == null) {
            return;
        }
        java.util.List<String> tablesWithoutPk = new java.util.ArrayList<>();
        for (com.fungle.brume.config.model.TableExtractionConfig t : config.extraction().tables()) {
            com.fungle.brume.schema.model.TableMetadata meta = schema.get(t.table());
            if (meta == null) {
                continue;
            }
            if (meta.primaryKeyColumn() == null) {
                tablesWithoutPk.add(t.table());
            }
        }
        if (!tablesWithoutPk.isEmpty()) {
            log.warn("brume.sink.strip-timestamps is enabled but {} configured table(s) "
                    + "lack a single-column primary key: {} — intra-table ORDER BY can not be "
                    + "applied, two runs may produce non-identical dumps for these tables.",
                    tablesWithoutPk.size(), tablesWithoutPk);
        }
    }
}
