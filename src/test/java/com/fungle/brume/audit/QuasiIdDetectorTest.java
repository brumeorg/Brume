package com.fungle.brume.audit;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.BrumeProperties.AuditProperties;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.report.QuasiIdWarning;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuasiIdDetector}.
 *
 * <p>No Spring context — the detector is instantiated directly with a {@link BrumeProperties}
 * built via {@link #propertiesWith(AuditProperties)}.
 *
 * <p>Tracked under #21c / ADR-0035.
 */
class QuasiIdDetectorTest {

    // -------------------------------------------------------------------------
    // Core semantic — strategy split (the central decision of #21c)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("KEEP on birth_date date triggers a warning (correlation preserved)")
    void keepImplicitOnNonTextQuasiIdTriggersWarning() {
        DatabaseSchema schema = schemaWith("users",
                new ColumnMetadata("birth_date", "date", true));
        AnonymizerConfig config = configWith("users", List.of());  // no rule = KEEP implicit

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).hasSize(1);
        QuasiIdWarning w = warnings.get(0);
        assertThat(w.column()).isEqualTo("birth_date");
        assertThat(w.dataType()).isEqualTo("date");
        assertThat(w.effectiveStrategy()).isNull();  // KEEP implicit
        assertThat(w.matchedPattern()).isEqualTo("birth");
    }

    @Test
    @DisplayName("HASH on birth_date triggers a warning (HMAC-deterministic preserves correlation)")
    void hashOnQuasiIdTriggersWarning() {
        DatabaseSchema schema = schemaWith("users",
                new ColumnMetadata("birth_date", "date", true));
        AnonymizerConfig config = configWith("users",
                List.of(new ColumnConfig("birth_date", Strategy.HASH, null, List.of())));

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).effectiveStrategy()).isEqualTo(Strategy.HASH);
    }

    @Test
    @DisplayName("FPE_ID on age triggers a warning (FPE bijection preserves correlation)")
    void fpeIdOnQuasiIdTriggersWarning() {
        DatabaseSchema schema = schemaWith("users",
                new ColumnMetadata("age", "integer", true));
        AnonymizerConfig config = configWith("users",
                List.of(new ColumnConfig("age", Strategy.FPE_ID, null, List.of())));

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).effectiveStrategy()).isEqualTo(Strategy.FPE_ID);
        assertThat(warnings.get(0).matchedPattern()).isEqualTo("age");
    }

    @Test
    @DisplayName("FAKE on birth_date silences the warning (neutralizing)")
    void fakeOnQuasiIdIsNeutralized() {
        DatabaseSchema schema = schemaWith("users",
                new ColumnMetadata("birth_date", "date", true));
        AnonymizerConfig config = configWith("users",
                List.of(new ColumnConfig("birth_date", Strategy.FAKE, null, List.of())));

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("NULLIFY and MASK silence the warning (neutralizing)")
    void nullifyAndMaskAreNeutralizing() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("postal_code", "integer", false),
                        new ColumnMetadata("salary_eur", "numeric", false)
                ), List.of(), "id")
        ));
        AnonymizerConfig config = configWith("users", List.of(
                new ColumnConfig("postal_code", Strategy.NULLIFY, null, List.of()),
                new ColumnConfig("salary_eur", Strategy.MASK, null, List.of())
        ));

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Type filter — non-text quasi-id without rule must still be flagged
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Non-text quasi-id columns (numeric, date) without rule are flagged")
    void nonTextQuasiIdWithoutRuleIsFlagged() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("postal_code", "integer", true),
                        new ColumnMetadata("salary_eur", "numeric", true),
                        new ColumnMetadata("age", "smallint", true)
                ), List.of(), "id")
        ));
        AnonymizerConfig config = configWith("users", List.of());

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).extracting(QuasiIdWarning::column)
                .containsExactlyInAnyOrder("postal_code", "salary_eur", "age");
    }

    @Test
    @DisplayName("Text quasi-id without rule is NOT flagged (PiiDetector handles it)")
    void textQuasiIdWithoutRuleIsDeferredToPiiDetector() {
        DatabaseSchema schema = schemaWith("users",
                new ColumnMetadata("birth_label", "character varying", true));
        AnonymizerConfig config = configWith("users", List.of());

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Pattern matching, defaults & override
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("French and English patterns match by case-insensitive substring")
    void frEnPatternsMatchCaseInsensitive() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("DATE_NAISSANCE", "date", false),
                        new ColumnMetadata("Code_Postal", "integer", false),
                        new ColumnMetadata("user_salaire_brut", "numeric", false)
                ), List.of(), "id")
        ));
        AnonymizerConfig config = configWith("users", List.of(
                new ColumnConfig("DATE_NAISSANCE", Strategy.KEEP, null, List.of()),
                new ColumnConfig("Code_Postal", Strategy.KEEP, null, List.of()),
                new ColumnConfig("user_salaire_brut", Strategy.KEEP, null, List.of())
        ));

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).extracting(QuasiIdWarning::matchedPattern)
                .containsExactlyInAnyOrder("naissance", "postal", "salaire");
    }

    @Test
    @DisplayName("Custom quasi-id-patterns replaces the default list (no merge)")
    void customPatternsReplaceDefaults() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("birth_date", "date", true),     // matches default
                        new ColumnMetadata("plate", "character varying", true)  // custom only
                ), List.of(), "id")
        ));
        AnonymizerConfig config = configWith("users", List.of(
                new ColumnConfig("birth_date", Strategy.KEEP, null, List.of()),
                new ColumnConfig("plate", Strategy.KEEP, null, List.of())
        ));

        AuditProperties customPatterns = new AuditProperties(
                true, List.of("plate"), AuditProperties.DEFAULT_NEUTRALIZING_STRATEGIES);

        List<QuasiIdWarning> warnings = detector(customPatterns).detect(schema, config, "test");

        // "birth" is no longer detected (custom list replaces, not merges)
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).column()).isEqualTo("plate");
        assertThat(warnings.get(0).matchedPattern()).isEqualTo("plate");
    }

    @Test
    @DisplayName("Custom neutralizing-strategies whitelists HASH")
    void customNeutralizingStrategiesWhitelistsHash() {
        DatabaseSchema schema = schemaWith("users",
                new ColumnMetadata("birth_date", "date", true));
        AnonymizerConfig config = configWith("users",
                List.of(new ColumnConfig("birth_date", Strategy.HASH, null, List.of())));

        AuditProperties customNeutralizing = new AuditProperties(
                true,
                AuditProperties.DEFAULT_QUASI_ID_PATTERNS,
                EnumSet.of(Strategy.FAKE, Strategy.NULLIFY, Strategy.MASK, Strategy.HASH)
        );

        List<QuasiIdWarning> warnings = detector(customNeutralizing).detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Enable flag
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("quasiIdEnabled=false returns an empty list and inspects no column")
    void disabledReturnsEmpty() {
        DatabaseSchema schema = schemaWith("users",
                new ColumnMetadata("birth_date", "date", true));
        AnonymizerConfig config = configWith("users", List.of());

        AuditProperties disabled = new AuditProperties(
                false, AuditProperties.DEFAULT_QUASI_ID_PATTERNS,
                AuditProperties.DEFAULT_NEUTRALIZING_STRATEGIES);

        List<QuasiIdWarning> warnings = detector(disabled).detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    // -------------------------------------------------------------------------
    // No false positives on unrelated columns
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Columns whose name does not match any pattern are not flagged")
    void unrelatedColumnsAreSkipped() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("id", "bigint", false),
                        new ColumnMetadata("email", "character varying", true),
                        new ColumnMetadata("created_at", "timestamp", true)
                ), List.of(), "id")
        ));
        AnonymizerConfig config = configWith("users", List.of());

        List<QuasiIdWarning> warnings = detector(AuditProperties.defaults())
                .detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Defaults sanity — full pattern set is non-empty and includes bilingual entries
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Built-in defaults are bilingual fr+en and not empty")
    void builtinDefaultsAreBilingualAndNonEmpty() {
        Set<String> patterns = Set.copyOf(AuditProperties.DEFAULT_QUASI_ID_PATTERNS);
        assertThat(patterns).contains("birth", "postal", "age", "salary", "gender");
        assertThat(patterns).contains("naissance", "salaire", "civilite", "sexe");
        assertThat(AuditProperties.DEFAULT_NEUTRALIZING_STRATEGIES)
                .containsExactlyInAnyOrder(Strategy.FAKE, Strategy.NULLIFY, Strategy.MASK);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private QuasiIdDetector detector(AuditProperties audit) {
        return new QuasiIdDetector(propertiesWith(audit));
    }

    private BrumeProperties propertiesWith(AuditProperties audit) {
        return new BrumeProperties(
                "config.yaml",
                "secret-test",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                com.fungle.brume.config.PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""),
                new BrumeProperties.SinkProperties(
                        com.fungle.brume.writer.SinkType.JDBC, null,
                        com.fungle.brume.writer.CompressionType.NONE,
                        new BrumeProperties.JdbcSinkProperties(com.fungle.brume.writer.CopyMode.NEVER)),
                new BrumeProperties.PlanProperties(com.fungle.brume.plan.PlanMode.EXACT),
                false,
                new BrumeProperties.OutputProperties(com.fungle.brume.output.OutputMode.TEXT),
                BrumeProperties.TimeoutsProperties.defaults(),
                audit
        );
    }

    private DatabaseSchema schemaWith(String tableName, ColumnMetadata... columns) {
        return new DatabaseSchema(Map.of(
                tableName, new TableMetadata(tableName, List.of(columns), List.of(), "id")
        ));
    }

    private AnonymizerConfig configWith(String tableName, List<ColumnConfig> columns) {
        return new AnonymizerConfig(
                new ExtractionConfig(3, 1000, List.of(new TableExtractionConfig(tableName, null))),
                new AnonymizationConfig(List.of(),
                        columns.isEmpty()
                                ? List.of()
                                : List.of(new TableAnonymizationConfig(tableName, columns)))
        );
    }
}
