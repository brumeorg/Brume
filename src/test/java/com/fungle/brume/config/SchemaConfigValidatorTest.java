package com.fungle.brume.config;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ColumnReference;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.LinkedColumnsConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SchemaConfigValidator}.
 *
 * <p>No Spring context — the validator is instantiated directly. Schemas and configs are
 * built in-line with the minimal shape needed for each scenario.
 */
class SchemaConfigValidatorTest {

    private SchemaConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaConfigValidator();
    }

    // -------------------------------------------------------------------------
    // Existence checks — hard errors regardless of strict mode (Q1=A/A)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects an extraction.tables entry that does not exist in the schema")
    void rejectsUnknownExtractionTable() {
        AnonymizerConfig config = config(
                extraction("orders", "userz"),
                anonymization()
        );
        DatabaseSchema schema = schema(table("orders", col("id", "bigint")), table("users", col("id", "bigint")));

        assertThatThrownBy(() -> validator.validate(config, schema, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown table 'userz'")
                .hasMessageContaining("extraction.tables")
                .satisfies(ex -> assertThat(((ConfigurationException) ex).suggestion())
                        .contains("Did you mean 'users'"));
    }

    @Test
    @DisplayName("rejects an anonymization.tables entry that does not exist in the schema")
    void rejectsUnknownAnonymizationTable() {
        AnonymizerConfig config = config(
                extraction("orders"),
                anonymization(tableRules("userz", col("id", Strategy.FPE_ID)))
        );
        DatabaseSchema schema = schema(table("orders", col("id", "bigint")), table("users", col("id", "bigint")));

        assertThatThrownBy(() -> validator.validate(config, schema, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown table 'userz'")
                .hasMessageContaining("anonymization.tables")
                .satisfies(ex -> assertThat(((ConfigurationException) ex).suggestion())
                        .contains("Did you mean 'users'"));
    }

    @Test
    @DisplayName("rejects an unknown column with a Levenshtein suggestion")
    void rejectsUnknownColumnWithSuggestion() {
        AnonymizerConfig config = config(
                extraction("users"),
                anonymization(tableRules("users", col("emial", Strategy.FAKE, SemanticType.EMAIL)))
        );
        DatabaseSchema schema = schema(table("users",
                col("id", "bigint"), col("email", "varchar")));

        assertThatThrownBy(() -> validator.validate(config, schema, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown column 'users.emial'")
                .satisfies(ex -> assertThat(((ConfigurationException) ex).suggestion())
                        .contains("Did you mean 'email'"));
    }

    @Test
    @DisplayName("omits the suggestion when no candidate is within the Levenshtein threshold")
    void noSuggestionWhenTooFar() {
        AnonymizerConfig config = config(
                extraction("users"),
                anonymization(tableRules("users", col("totally_unrelated_name", Strategy.FAKE, SemanticType.EMAIL)))
        );
        DatabaseSchema schema = schema(table("users", col("id", "bigint"), col("email", "varchar")));

        assertThatThrownBy(() -> validator.validate(config, schema, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown column")
                .hasMessageNotContaining("Did you mean");
    }

    @Test
    @DisplayName("rejects an unknown linked_columns reference")
    void rejectsUnknownLinkedColumnsRef() {
        AnonymizerConfig config = config(
                extraction("users"),
                new AnonymizationConfig(
                        List.of(new LinkedColumnsConfig("user_email", List.of(
                                new ColumnReference("users", "email"),
                                new ColumnReference("audit_logs", "user_emial")))),
                        List.of()
                )
        );
        DatabaseSchema schema = schema(
                table("users", col("email", "varchar")),
                table("audit_logs", col("user_email", "varchar"))
        );

        assertThatThrownBy(() -> validator.validate(config, schema, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("audit_logs.user_emial")
                .hasMessageContaining("linked_columns[user_email]")
                .satisfies(ex -> assertThat(((ConfigurationException) ex).suggestion())
                        .contains("Did you mean 'user_email'"));
    }

    // -------------------------------------------------------------------------
    // Existence checks — happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("accepts a config that fully matches the schema")
    void acceptsFullyValidConfig() {
        AnonymizerConfig config = config(
                extraction("users", "orders"),
                anonymization(
                        tableRules("users",
                                col("id", Strategy.FPE_ID),
                                col("email", Strategy.FAKE, SemanticType.EMAIL)),
                        tableRules("orders",
                                col("id", Strategy.FPE_ID))
                )
        );
        DatabaseSchema schema = schema(
                table("users", col("id", "bigint"), col("email", "varchar")),
                table("orders", col("id", "bigint"))
        );

        assertThatCode(() -> validator.validate(config, schema, false)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(config, schema, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("does not flag tables present in the schema but absent from the config")
    void ignoresUnconfiguredTables() {
        // Brume legitimately extracts a subset of the schema; tables outside that subset must not warn.
        AnonymizerConfig config = config(
                extraction("users"),
                anonymization(tableRules("users", col("id", Strategy.FPE_ID)))
        );
        DatabaseSchema schema = schema(
                table("users", col("id", "bigint")),
                table("audit_logs", col("id", "bigint"), col("ip_address", "varchar"))
        );

        assertThatCode(() -> validator.validate(config, schema, false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("matches table and column names case-insensitively")
    void caseInsensitiveLookup() {
        AnonymizerConfig config = config(
                extraction("Users"),
                anonymization(tableRules("Users", col("EMAIL", Strategy.FAKE, SemanticType.EMAIL)))
        );
        DatabaseSchema schema = schema(table("users", col("email", "varchar")));

        assertThatCode(() -> validator.validate(config, schema, false)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Type/strategy compatibility — INCOMPATIBLE: warn by default, error in strict
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("FAKE EMAIL on a bigint column is incompatible — passes lenient, throws strict")
    void fakeEmailOnBigintIsIncompatible() {
        AnonymizerConfig config = config(
                extraction("users"),
                anonymization(tableRules("users",
                        col("id", Strategy.FAKE, SemanticType.EMAIL)))
        );
        DatabaseSchema schema = schema(table("users", col("id", "bigint")));

        // Lenient: log WARN, no throw.
        assertThatCode(() -> validator.validate(config, schema, false)).doesNotThrowAnyException();

        // Strict: throw.
        assertThatThrownBy(() -> validator.validate(config, schema, true))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("users.id")
                .hasMessageContaining("FAKE")
                .hasMessageContaining("bigint");
    }

    @Test
    @DisplayName("FPE_ID on a uuid column is incompatible")
    void fpeIdOnUuidIsIncompatible() {
        AnonymizerConfig config = config(
                extraction("sessions"),
                anonymization(tableRules("sessions", col("id", Strategy.FPE_ID)))
        );
        DatabaseSchema schema = schema(table("sessions", col("id", "uuid")));

        assertThatThrownBy(() -> validator.validate(config, schema, true))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("FPE_ID");
    }

    @Test
    @DisplayName("FPE_UUID on a uuid column is fully compatible")
    void fpeUuidOnUuidIsOk() {
        AnonymizerConfig config = config(
                extraction("sessions"),
                anonymization(tableRules("sessions", col("id", Strategy.FPE_UUID)))
        );
        DatabaseSchema schema = schema(table("sessions", col("id", "uuid")));

        assertThatCode(() -> validator.validate(config, schema, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HASH on a jsonb column is incompatible (use JSONB strategy instead)")
    void hashOnJsonbIsIncompatible() {
        AnonymizerConfig config = config(
                extraction("users"),
                anonymization(tableRules("users", col("address_json", Strategy.HASH)))
        );
        DatabaseSchema schema = schema(table("users", col("address_json", "jsonb")));

        assertThatThrownBy(() -> validator.validate(config, schema, true))
                .isInstanceOf(ConfigurationException.class);
    }

    // -------------------------------------------------------------------------
    // Type/strategy SUSPECT — warn always, never throws even in strict mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("FPE_ID on a varchar column is suspect — warns but never throws")
    void fpeIdOnVarcharIsSuspectNeverThrows() {
        // Real-world case: a varchar column that happens to store numeric IDs (legacy schemas).
        AnonymizerConfig config = config(
                extraction("legacy_users"),
                anonymization(tableRules("legacy_users", col("id", Strategy.FPE_ID)))
        );
        DatabaseSchema schema = schema(table("legacy_users", col("id", "varchar")));

        // Q1 reco: SUSPECT is operator's call, never blocking — even in strict mode.
        assertThatCode(() -> validator.validate(config, schema, false)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(config, schema, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MASK on bigint is suspect — warns but never throws")
    void maskOnBigintIsSuspect() {
        AnonymizerConfig config = config(
                extraction("orders"),
                anonymization(tableRules("orders", col("total_cents", Strategy.MASK)))
        );
        DatabaseSchema schema = schema(table("orders", col("total_cents", "bigint")));

        assertThatCode(() -> validator.validate(config, schema, true)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Universal strategies — KEEP / NULLIFY accept any type
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("KEEP and NULLIFY accept any column type without warning")
    void keepAndNullifyAlwaysOk() {
        AnonymizerConfig config = config(
                extraction("orders"),
                anonymization(tableRules("orders",
                        col("id", Strategy.KEEP),
                        col("notes", Strategy.NULLIFY),
                        col("created_at", Strategy.KEEP)))
        );
        DatabaseSchema schema = schema(table("orders",
                col("id", "bigint"), col("notes", "text"), col("created_at", "timestamp")));

        assertThatCode(() -> validator.validate(config, schema, true)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // #79f — type-family categorization no longer false-positives on custom types
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#79f — FPE_ID on `point` (PG geometric) is INCOMPATIBLE (pre-fix: faux positif NUMERIC via contains('int'))")
    void fpeIdOnPointTypeIsRejectedStrict() {
        // PostgreSQL `point` type (geometric) contains the substring "int" but is NOT numeric.
        // Pre-fix: categorize("point") → NUMERIC (false positive) → FPE_ID passes silently.
        // Post-fix: categorize("point") → OTHER → FPE_ID INCOMPATIBLE → strict throws.
        AnonymizerConfig config = config(
                extraction("locations"),
                anonymization(tableRules("locations", col("coord", Strategy.FPE_ID)))
        );
        DatabaseSchema schema = schema(table("locations", col("coord", "point")));

        assertThatThrownBy(() -> validator.validate(config, schema, true))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PostgreSQL type 'point'")
                .hasMessageContaining("FPE_ID");
    }

    @Test
    @DisplayName("#79f — FAKE EMAIL on user-defined type containing 'char' is INCOMPATIBLE (pre-fix: faux positif STRING)")
    void fakeEmailOnUserDefinedTypeWithCharSubstringIsRejectedStrict() {
        // A user-defined enum / domain whose name contains the substring "char" was wrongly
        // classified STRING pre-fix (via contains("char")). The information_schema actually
        // returns "USER-DEFINED" for such columns ; this test simulates the case where a
        // future PG version or extension returns a custom name that happens to embed "char".
        AnonymizerConfig config = config(
                extraction("audit"),
                anonymization(tableRules("audit", col("event_kind", Strategy.FAKE, SemanticType.EMAIL)))
        );
        DatabaseSchema schema = schema(table("audit", col("event_kind", "arch_log")));

        assertThatThrownBy(() -> validator.validate(config, schema, true))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PostgreSQL type 'arch_log'")
                .hasMessageContaining("FAKE");
    }

    @Test
    @DisplayName("#79f — standard PG numeric types are correctly classified NUMERIC")
    void standardNumericTypesStillRecognized() {
        // Sanity : the fix must not have regressed any standard type.
        for (String pgType : List.of("smallint", "integer", "bigint",
                "decimal", "numeric", "real", "double precision",
                "serial", "bigserial", "smallserial")) {
            AnonymizerConfig config = config(
                    extraction("t"),
                    anonymization(tableRules("t", col("id", Strategy.FPE_ID)))
            );
            DatabaseSchema schema = schema(table("t", col("id", pgType)));
            assertThatCode(() -> validator.validate(config, schema, true))
                    .as("FPE_ID must be OK on standard numeric type '%s'", pgType)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("#79f — standard PG string types are correctly classified STRING")
    void standardStringTypesStillRecognized() {
        for (String pgType : List.of("character", "character varying", "varchar",
                "text", "citext", "name", "bpchar", "char")) {
            AnonymizerConfig config = config(
                    extraction("t"),
                    anonymization(tableRules("t", col("v", Strategy.HASH)))
            );
            DatabaseSchema schema = schema(table("t", col("v", pgType)));
            assertThatCode(() -> validator.validate(config, schema, true))
                    .as("HASH must be OK on standard string type '%s'", pgType)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("#79f — case-insensitive categorization via Locale.ROOT (consistency with BrumePropertiesValidator)")
    void categorizationIsLocaleRootInsensitive() {
        // PostgreSQL information_schema returns canonical lowercase, but defensive : the
        // categorization must not depend on the JVM default locale (cf. Turkish locale gotcha).
        AnonymizerConfig config = config(
                extraction("t"),
                anonymization(tableRules("t", col("id", Strategy.FPE_ID)))
        );
        DatabaseSchema schema = schema(table("t", col("id", "BIGINT")));  // uppercase

        assertThatCode(() -> validator.validate(config, schema, true))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ColumnConfig col(String name, Strategy strategy) {
        return new ColumnConfig(name, strategy, null, null);
    }

    private static ColumnConfig col(String name, Strategy strategy, SemanticType type) {
        return new ColumnConfig(name, strategy, type, null);
    }

    private static ColumnMetadata col(String name, String pgType) {
        return new ColumnMetadata(name, pgType, true);
    }

    private static TableAnonymizationConfig tableRules(String table, ColumnConfig... cols) {
        return new TableAnonymizationConfig(table, List.of(cols));
    }

    private static AnonymizationConfig anonymization(TableAnonymizationConfig... tables) {
        return new AnonymizationConfig(List.of(), List.of(tables));
    }

    private static ExtractionConfig extraction(String... tableNames) {
        List<TableExtractionConfig> tables = java.util.Arrays.stream(tableNames)
                .map(t -> new TableExtractionConfig(t, null))
                .toList();
        return new ExtractionConfig(3, 1000, 1000, 10_000, tables);
    }

    private static AnonymizerConfig config(ExtractionConfig extraction, AnonymizationConfig anon) {
        return new AnonymizerConfig(extraction, anon);
    }

    private static TableMetadata table(String name, ColumnMetadata... cols) {
        return new TableMetadata(name, List.of(cols), List.of(), null);
    }

    private static DatabaseSchema schema(TableMetadata... tables) {
        Map<String, TableMetadata> map = new LinkedHashMap<>();
        for (TableMetadata t : tables) map.put(t.name(), t);
        return new DatabaseSchema(map);
    }
}
