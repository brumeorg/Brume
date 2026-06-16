package com.fungle.brume.config;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.JsonPathConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ConfigValidator}.
 *
 * <p>No Spring context is loaded — ConfigValidator is instantiated directly.
 */
class ConfigValidatorTest {

    private ConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConfigValidator();
    }

    // -------------------------------------------------------------------------
    // Valid configurations — must not throw
    // -------------------------------------------------------------------------

    @Test
    void validate_doesNotThrow_forValidConfig() {
        AnonymizerConfig config = buildValidConfig();
        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // extraction.tables empty
    // -------------------------------------------------------------------------

    @Test
    void validate_throws_whenExtractionTablesEmpty() {
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, List.of());
        AnonymizerConfig config = new AnonymizerConfig(extraction, emptyAnonymization());

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("extraction.tables must not be empty");
    }

    @Test
    void validate_throws_whenExtractionNull() {
        AnonymizerConfig config = new AnonymizerConfig(null, emptyAnonymization());

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("extraction.tables must not be empty");
    }

    @Test
    void validate_throws_whenFetchSizeIsNotPositive() {
        ExtractionConfig extraction = new ExtractionConfig(3, -1, 1000, 10_000, List.of(new TableExtractionConfig("orders", null)));
        AnonymizerConfig config = new AnonymizerConfig(extraction, emptyAnonymization());

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("extraction.fetch_size must be > 0");
    }

    @Test
    void validate_throws_whenChunkSizeIsNotPositive() {
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, 1000, -1, List.of(new TableExtractionConfig("orders", null)));
        AnonymizerConfig config = new AnonymizerConfig(extraction, emptyAnonymization());

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("extraction.chunk_size must be > 0");
    }

    // -------------------------------------------------------------------------
    // extraction.tables[].filter — anti SQL-injection (T01, ADR-0017)
    // -------------------------------------------------------------------------

    @Test
    void validate_throws_whenFilterContainsSemicolon_failsBeforeAnyDbConnection() {
        TableExtractionConfig malicious = new TableExtractionConfig(
                "orders", "1=1; DROP SCHEMA test_brume CASCADE");
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, 1000, 10_000, List.of(malicious));
        AnonymizerConfig config = new AnonymizerConfig(extraction, emptyAnonymization());

        // ConfigValidator is a plain @Component instantiated without Spring (see setUp) —
        // if this assertion passes, the rejection happened with no DataSource bean ever
        // resolved, which is exactly the fail-fast guarantee T01 requires.
        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining("';'");
    }

    @Test
    void validate_throws_whenFilterContainsForbiddenKeyword() {
        TableExtractionConfig malicious = new TableExtractionConfig(
                "orders", "1=1 OR DELETE FROM x WHERE 1=1");
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, 1000, 10_000, List.of(malicious));
        AnonymizerConfig config = new AnonymizerConfig(extraction, emptyAnonymization());

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("DELETE");
    }

    @Test
    void validate_doesNotThrow_whenFilterIsLegitimate() {
        TableExtractionConfig legit = new TableExtractionConfig(
                "orders", "created_at >= '2025-01-01' AND status = 'paid'");
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, 1000, 10_000, List.of(legit));
        AnonymizerConfig config = new AnonymizerConfig(extraction, emptyAnonymization());

        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // FAKE strategy requires SemanticType
    // -------------------------------------------------------------------------

    @Test
    void validate_throws_whenFakeStrategyHasNoType() {
        ColumnConfig invalid = new ColumnConfig("email", Strategy.FAKE, null, null);
        AnonymizerConfig config = configWithColumn("users", invalid);

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("strategy FAKE")
                .hasMessageContaining("users.email");
    }

    @Test
    void validate_doesNotThrow_whenFakeStrategyHasType() {
        ColumnConfig valid = new ColumnConfig("email", Strategy.FAKE, SemanticType.EMAIL, null);
        AnonymizerConfig config = configWithColumn("users", valid);

        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // JSONB type requires json_paths
    // -------------------------------------------------------------------------

    @Test
    void validate_throws_whenJsonbTypeHasNoJsonPaths() {
        ColumnConfig invalid = new ColumnConfig("payload", Strategy.FAKE, SemanticType.JSONB, null);
        AnonymizerConfig config = configWithColumn("audit_logs", invalid);

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("type JSONB")
                .hasMessageContaining("audit_logs.payload");
    }

    @Test
    void validate_throws_whenJsonbTypeHasEmptyJsonPaths() {
        ColumnConfig invalid = new ColumnConfig("payload", Strategy.FAKE, SemanticType.JSONB, List.of());
        AnonymizerConfig config = configWithColumn("audit_logs", invalid);

        assertThatThrownBy(() -> validator.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("json_paths");
    }

    @Test
    void validate_doesNotThrow_whenJsonbTypeHasJsonPaths() {
        JsonPathConfig path = new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE);
        ColumnConfig valid = new ColumnConfig("payload", Strategy.FAKE, SemanticType.JSONB, List.of(path));
        AnonymizerConfig config = configWithColumn("audit_logs", valid);

        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Strategies that do not need a SemanticType must pass validation
    // -------------------------------------------------------------------------

    @Test
    void validate_doesNotThrow_forFpeIdWithoutType() {
        ColumnConfig col = new ColumnConfig("id", Strategy.FPE_ID, null, null);
        AnonymizerConfig config = configWithColumn("orders", col);
        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    @Test
    void validate_doesNotThrow_forNullifyWithoutType() {
        ColumnConfig col = new ColumnConfig("notes", Strategy.NULLIFY, null, null);
        AnonymizerConfig config = configWithColumn("orders", col);
        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    @Test
    void validate_doesNotThrow_forKeepWithoutType() {
        ColumnConfig col = new ColumnConfig("total_amount", Strategy.KEEP, null, null);
        AnonymizerConfig config = configWithColumn("orders", col);
        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    @Test
    void validate_doesNotThrow_forHashWithoutType() {
        ColumnConfig col = new ColumnConfig("iban", Strategy.HASH, null, null);
        AnonymizerConfig config = configWithColumn("users", col);
        assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a minimal valid config used as a baseline. */
    private AnonymizerConfig buildValidConfig() {
        TableExtractionConfig table = new TableExtractionConfig("orders", null);
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, 1000, 10_000, List.of(table));

        ColumnConfig emailCol = new ColumnConfig("email", Strategy.FAKE, SemanticType.EMAIL, null);
        TableAnonymizationConfig anonTable = new TableAnonymizationConfig("orders", List.of(emailCol));
        AnonymizationConfig anonymization = new AnonymizationConfig(List.of(), List.of(anonTable));

        return new AnonymizerConfig(extraction, anonymization);
    }

    /** Builds a config containing a single column rule for the given table. */
    private AnonymizerConfig configWithColumn(String tableName, ColumnConfig col) {
        TableExtractionConfig extractionTable = new TableExtractionConfig(tableName, null);
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, 1000, 10_000, List.of(extractionTable));

        TableAnonymizationConfig anonTable = new TableAnonymizationConfig(tableName, List.of(col));
        AnonymizationConfig anonymization = new AnonymizationConfig(List.of(), List.of(anonTable));

        return new AnonymizerConfig(extraction, anonymization);
    }

    /** Empty anonymization section. */
    private AnonymizationConfig emptyAnonymization() {
        return new AnonymizationConfig(List.of(), List.of());
    }
}

