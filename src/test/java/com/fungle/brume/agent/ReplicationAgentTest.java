package com.fungle.brume.agent;

import com.fungle.brume.anonymization.AnonymizationEngine;
import com.fungle.brume.anonymization.FkStrategyPropagator;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.SchemaConfigValidator;
import com.fungle.brume.anonymization.SubstitutionDictionaryOverflowException;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.ConfigValidator;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.extraction.ChunkedTableProcessor;
import com.fungle.brume.extraction.ExtractionEngine;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.monitoring.HeapMonitor;
import com.fungle.brume.plan.PiiDetector;
import com.fungle.brume.plan.PlanEstimator;
import com.fungle.brume.preflight.PreflightCheckRunner;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.ReportRenderer;
import com.fungle.brume.report.SubstitutionDictStats;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.schema.SchemaAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.state.ExecutionStateWriter;
import com.fungle.brume.writer.HybridWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicationAgentTest {

    @Test
    @DisplayName("run utilise le processeur streaming quand brume.pipeline-mode=STREAMING")
    void shouldUseStreamingPipelineWhenConfigured() throws Exception {
        ReplicationAgent agent = newAgent(PipelineMode.STREAMING);

        agent.run(CommandEnum.EXECUTE);

        verify(agentDeps.chunkedTableProcessor).processAll(any(), any(), any());
        verify(agentDeps.extractionEngine, never()).extract(any(), any(), any());
        verify(agentDeps.anonymizationEngine, never()).anonymize(any(), any(), any());
        verify(agentDeps.hybridWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("run conserve le pipeline batch historique quand brume.pipeline-mode=BATCH")
    void shouldUseBatchPipelineWhenConfigured() throws Exception {
        ReplicationAgent agent = newAgent(PipelineMode.BATCH);

        agent.run(CommandEnum.EXECUTE);

        verify(agentDeps.chunkedTableProcessor, never()).processAll(any(), any(), any());
        verify(agentDeps.extractionEngine).extract(any(), any(), any());
        verify(agentDeps.anonymizationEngine).anonymize(any(), any(), any());
        verify(agentDeps.hybridWriter).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("run rend le rapport avec les stats dictionnaire avant de relancer l'overflow")
    void shouldRenderReportBeforeRethrowingDictionaryOverflow() throws Exception {
        ReplicationAgent agent = newAgent(PipelineMode.STREAMING);
        SubstitutionDictionaryOverflowException overflow = new SubstitutionDictionaryOverflowException(
                11,
                10,
                List.of(new SubstitutionDictStats.TopContributor("users.email", 10))
        );
        doThrow(overflow).when(agentDeps.chunkedTableProcessor).processAll(any(), any(), any());

        assertThatThrownBy(() -> agent.run(CommandEnum.EXECUTE))
                .isSameAs(overflow);

        ArgumentCaptor<com.fungle.brume.report.ExecutionSummary> summaryCaptor =
                ArgumentCaptor.forClass(com.fungle.brume.report.ExecutionSummary.class);
        verify(agentDeps.reportRenderer).render(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().success()).isFalse();
        assertThat(summaryCaptor.getValue().substitutionDict().entries()).isEqualTo(7L);
        assertThat(summaryCaptor.getValue().substitutionDict().topContributors())
                .containsExactly(new SubstitutionDictStats.TopContributor("users.email", 7));
    }

    @Test
    @DisplayName("run échoue avant extraction quand le plan dépasse brume.max-target-rows")
    void shouldFailFastWhenPlannedRowsExceedMaxTargetRows() {
        ReplicationAgent agent = newAgent(PipelineMode.STREAMING, 10);
        PlanSummary tooLargePlan = new PlanSummary(
                "test_brume",
                List.of(new PlanTableStats("orders", 11, 0, "direct")),
                List.of(),
                Instant.parse("2026-05-03T00:00:00Z")
        );
        when(agentDeps.planEstimator.estimate(any(), any(), eq("test_brume"), eq(3))).thenReturn(tooLargePlan);

        assertThatThrownBy(() -> agent.run(CommandEnum.EXECUTE))
                .isInstanceOf(com.fungle.brume.config.ConfigurationException.class)
                .hasMessageContaining("brume.max-target-rows=10");

        verify(agentDeps.chunkedTableProcessor, never()).processAll(any(), any(), any());
    }

    private AgentDeps agentDeps;

    private ReplicationAgent newAgent(PipelineMode pipelineMode) {
        return newAgent(pipelineMode, 0L);
    }

    private ReplicationAgent newAgent(PipelineMode pipelineMode, long maxTargetRows) {
        agentDeps = new AgentDeps();

        agentDeps.schemaReplicator = mock(SchemaReplicator.class);
        agentDeps.configLoader = mock(ConfigLoader.class);
        agentDeps.configValidator = mock(ConfigValidator.class);
        agentDeps.schemaAnalyzer = mock(SchemaAnalyzer.class);
        agentDeps.extractionEngine = mock(ExtractionEngine.class);
        agentDeps.chunkedTableProcessor = mock(ChunkedTableProcessor.class);
        agentDeps.anonymizationEngine = mock(AnonymizationEngine.class);
        agentDeps.fkStrategyPropagator = new FkStrategyPropagator();
        agentDeps.schemaConfigValidator = new SchemaConfigValidator();
        agentDeps.hybridWriter = mock(HybridWriter.class);
        agentDeps.substitutionDictionary = mock(SubstitutionDictionary.class);
        agentDeps.reportRenderer = mock(ReportRenderer.class);
        agentDeps.planEstimator = mock(PlanEstimator.class);
        agentDeps.piiDetector = mock(PiiDetector.class);
        agentDeps.quasiIdDetector = mock(com.fungle.brume.audit.QuasiIdDetector.class);
        agentDeps.heapMonitor = mock(HeapMonitor.class);
        agentDeps.preflightCheckRunner = mock(PreflightCheckRunner.class);
        agentDeps.checkpointService = mock(com.fungle.brume.checkpoint.CheckpointService.class);

        ReplicationProperties replicationProperties = new ReplicationProperties(
                "test_brume",
                "pg_dump",
                300,
                ReplicationProperties.DdlErrorMode.STRICT,
                20,
                3,
                new ReplicationProperties.Source("jdbc:postgresql://source/postgres", "postgres", "postgres"),
                new ReplicationProperties.Target("jdbc:postgresql://target/postgres", "postgres", "postgres")
        );
        BrumeProperties brumeProperties = new BrumeProperties(
                "config.yaml",
                "test-secret-1234",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                maxTargetRows,
                85,
                pipelineMode,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", "")
        );

        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000, List.of()),
                new AnonymizationConfig(List.of(), List.of())
        );
        DatabaseSchema schema = new DatabaseSchema(Map.of());
        OrderedExtractionResult extracted = new OrderedExtractionResult();
        OrderedExtractionResult anonymized = new OrderedExtractionResult();
        PlanSummary planSummary = new PlanSummary("test_brume", List.of(), List.of(), Instant.parse("2026-05-03T00:00:00Z"));

        try {
            org.mockito.Mockito.doReturn(com.fungle.brume.report.DdlExecutionResult.empty())
                    .when(agentDeps.schemaReplicator)
                    .replicate(ArgumentMatchers.anyString(),
                            ArgumentMatchers.anyString(),
                            ArgumentMatchers.anyString(),
                            ArgumentMatchers.anyString(),
                            ArgumentMatchers.any());
        } catch (Exception unreachable) {
            throw new IllegalStateException(unreachable);
        }
        when(agentDeps.configLoader.load()).thenReturn(config);
        when(agentDeps.schemaAnalyzer.analyze("test_brume")).thenReturn(schema);
        when(agentDeps.planEstimator.estimate(config, schema, "test_brume", 3)).thenReturn(planSummary);
        when(agentDeps.piiDetector.detect(schema, config, "test_brume")).thenReturn(List.of());
        when(agentDeps.quasiIdDetector.detect(schema, config, "test_brume")).thenReturn(List.of());
        when(agentDeps.extractionEngine.extract(eq(config), eq(schema), any())).thenReturn(extracted);
        when(agentDeps.anonymizationEngine.anonymize(eq(extracted), eq(config.anonymization()), any())).thenReturn(anonymized);
        when(agentDeps.substitutionDictionary.snapshot(10)).thenReturn(new SubstitutionDictStats(
                7,
                10,
                List.of(new SubstitutionDictStats.TopContributor("users.email", 7))
        ));

        return new ReplicationAgent(
                agentDeps.schemaReplicator,
                agentDeps.configLoader,
                agentDeps.configValidator,
                agentDeps.schemaAnalyzer,
                agentDeps.extractionEngine,
                agentDeps.chunkedTableProcessor,
                agentDeps.anonymizationEngine,
                agentDeps.fkStrategyPropagator,
                agentDeps.schemaConfigValidator,
                agentDeps.hybridWriter,
                agentDeps.substitutionDictionary,
                replicationProperties,
                brumeProperties,
                Optional.of(mock(DataSource.class)),
                agentDeps.reportRenderer,
                agentDeps.planEstimator,
                agentDeps.piiDetector,
                agentDeps.quasiIdDetector,
                agentDeps.heapMonitor,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                agentDeps.preflightCheckRunner,
                new com.fungle.brume.shutdown.CancellationToken(),
                agentDeps.checkpointService,
                mock(ExecutionStateWriter.class)
        );
    }

    private static final class AgentDeps {
        private SchemaReplicator schemaReplicator;
        private ConfigLoader configLoader;
        private ConfigValidator configValidator;
        private SchemaAnalyzer schemaAnalyzer;
        private ExtractionEngine extractionEngine;
        private ChunkedTableProcessor chunkedTableProcessor;
        private AnonymizationEngine anonymizationEngine;
        private FkStrategyPropagator fkStrategyPropagator;
        private SchemaConfigValidator schemaConfigValidator;
        private HybridWriter hybridWriter;
        private SubstitutionDictionary substitutionDictionary;
        private ReportRenderer reportRenderer;
        private PlanEstimator planEstimator;
        private PiiDetector piiDetector;
        private com.fungle.brume.audit.QuasiIdDetector quasiIdDetector;
        private HeapMonitor heapMonitor;
        private PreflightCheckRunner preflightCheckRunner;
        private com.fungle.brume.checkpoint.CheckpointService checkpointService;
    }
}




