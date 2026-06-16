package com.fungle.brume.anonymization;

import com.fungle.brume.anonymization.strategies.AnonymizationStrategy;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.anonymization.strategies.NullifyStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymizationEngineTest {

    @Test
    @DisplayName("anonymizeRow anonymise uniquement les colonnes configurées et alimente le report")
    void shouldAnonymizeSingleRow() {
        StrategyResolver strategyResolver = mock(StrategyResolver.class);
        SemanticKeyResolver semanticKeyResolver = mock(SemanticKeyResolver.class);
        JsonPathProcessor jsonPathProcessor = mock(JsonPathProcessor.class);
        AnonymizationStrategy strategy = new NullifyStrategy();

        BrumeProperties props = new BrumeProperties(
                "config.yaml",
                "test-secret-1234",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                new BrumeProperties.SubstitutionDictProperties(1_000L),
                new BrumeProperties.ReportProperties("", "", "")
        );

        SubstitutionDictionary substitutionDictionary = new SubstitutionDictionary(props);
        AnonymizationEngine engine = new AnonymizationEngine(
                strategyResolver,
                substitutionDictionary,
                semanticKeyResolver,
                jsonPathProcessor
        );

        AnonymizationConfig config = new AnonymizationConfig(
                List.of(),
                List.of(new TableAnonymizationConfig("users", List.of(
                        new ColumnConfig("email", Strategy.NULLIFY, SemanticType.EMAIL, null)
                )))
        );

        when(strategyResolver.resolve(Strategy.NULLIFY)).thenReturn(strategy);
        when(semanticKeyResolver.resolve("users", "email", config)).thenReturn("user_email");

        ExecutionReport report = new ExecutionReport("test_brume", "test_brume");
        Map<String, Object> sourceData = new LinkedHashMap<>();
        sourceData.put("id", 1L);
        sourceData.put("email", "alice@example.com");
        sourceData.put("first_name", "Alice");
        ExtractedRow source = new ExtractedRow("users", sourceData);

        ExtractedRow anonymized = engine.anonymizeRow(source, config, report);

        assertThat(anonymized.table()).isEqualTo("users");
        assertThat(anonymized.data())
                .containsEntry("id", 1L)
                .containsEntry("first_name", "Alice")
                .containsEntry("email", null);

        verify(strategyResolver).resolve(Strategy.NULLIFY);
        verify(semanticKeyResolver).resolve("users", "email", config);

        assertThat(report.toSummary(new PhaseTimings(0, 0, 0, 0)).strategyUsages())
                .anyMatch(usage -> usage.table().equals("users")
                        && usage.column().equals("email")
                        && usage.strategy().equals("NULLIFY")
                        && usage.count() == 1L);
    }
}

