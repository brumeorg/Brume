package com.fungle.brume;

import com.fungle.brume.audit.anonymity.AnonymityReport;
import com.fungle.brume.audit.anonymity.EquivalenceClassDistribution;
import com.fungle.brume.audit.anonymity.Recommendation;
import com.fungle.brume.audit.anonymity.SingletonRow;
import com.fungle.brume.audit.anonymity.TableAuditResult;
import com.fungle.brume.checkpoint.CheckpointState;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ColumnReference;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.JsonPathConfig;
import com.fungle.brume.config.model.LinkedColumnsConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.output.BrumeJsonOutput;
import com.fungle.brume.report.ComparisonRow;
import com.fungle.brume.report.ComparisonSummary;
import com.fungle.brume.report.DdlExecutionResult;
import com.fungle.brume.report.DdlFailure;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.HeapStats;
import com.fungle.brume.report.Insight;
import com.fungle.brume.report.BrumeRuntimeContext;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.report.PiiWarning;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.report.QuasiIdWarning;
import com.fungle.brume.report.StrategyUsage;
import com.fungle.brume.report.SubstitutionDictStats;
import com.fungle.brume.report.TableStats;
import com.fungle.brume.state.ColumnState;
import com.fungle.brume.state.ExecutionState;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.stream.Stream;

/**
 * GraalVM native-image reflection hints for classes not reachable by Spring AOT.
 *
 * <p>Spring AOT handles {@code @Component}, {@code @ConfigurationProperties}, and Spring
 * beans automatically. This registrar covers the gaps:
 * <ul>
 *   <li>Jackson YAML deserialization targets ({@code config.model.*} — not Spring beans)</li>
 *   <li>Jackson JSON serialization targets ({@code report.*}, {@code output.*}) emitted
 *       on stdout or written to files</li>
 *   <li>JSON state and checkpoint files ({@code state.*}, {@code checkpoint.CheckpointState})</li>
 *   <li>k-anonymity audit report classes ({@code audit.anonymity.*})</li>
 *   <li>Enums used as Jackson fields ({@code Strategy}, {@code SemanticType},
 *       {@code CommandEnum}, inner enums of report records)</li>
 * </ul>
 *
 * <p>Activated via {@code @ImportRuntimeHints(BrumeRuntimeHints.class)} on
 * {@link BrumeApplication} and processed by {@code spring-boot:process-aot} during
 * the native build ({@code -Pnative}).
 *
 * <p>Replaces the hand-maintained {@code reflect-config.json} which had incorrect
 * {@code com.fungle.brume.report.*} package names for classes actually in
 * {@code com.fungle.brume.config.model.*}.
 */
public class BrumeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        Stream.of(
                // --- config.model : Jackson YAML deserialization of config.yaml ---
                AnonymizerConfig.class,
                ExtractionConfig.class,
                AnonymizationConfig.class,
                ColumnConfig.class,
                ColumnReference.class,
                JsonPathConfig.class,
                LinkedColumnsConfig.class,
                SemanticType.class,
                Strategy.class,
                TableAnonymizationConfig.class,
                TableExtractionConfig.class,

                // --- state : Jackson JSON for brume-state.json (written by execute, read by audit) ---
                ExecutionState.class,
                ColumnState.class,

                // --- checkpoint : Jackson JSON for brume-checkpoint.json ---
                CheckpointState.class,

                // --- output : top-level JSON wrapper emitted on stdout (--json flag) ---
                BrumeJsonOutput.class,

                // --- report : records included in BrumeJsonOutput and the HTML/plan reports ---
                ComparisonRow.class,
                ComparisonSummary.class,
                DdlExecutionResult.class,
                DdlFailure.class,
                ExecutionSummary.class,
                HeapStats.class,
                Insight.class,
                Insight.Level.class,             // inner enum — Jackson serialises by name
                BrumeRuntimeContext.class,
                PhaseTimings.class,
                PiiWarning.class,
                PlanSummary.class,
                PlanTableStats.class,
                QuasiIdWarning.class,
                StrategyUsage.class,
                SubstitutionDictStats.class,
                SubstitutionDictStats.TopContributor.class,  // inner record
                TableStats.class,

                // --- audit.anonymity : k-anonymity report serialisation ---
                AnonymityReport.class,
                TableAuditResult.class,
                EquivalenceClassDistribution.class,
                Recommendation.class,
                Recommendation.Severity.class,  // inner enum
                SingletonRow.class,

                // --- enums used as Jackson fields in serialised records ---
                CommandEnum.class               // referenced in BrumeRuntimeContext JSON field
        ).forEach(type -> hints.reflection().registerType(type, MemberCategory.values()));
    }
}
