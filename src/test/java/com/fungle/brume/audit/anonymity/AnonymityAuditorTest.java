package com.fungle.brume.audit.anonymity;

import com.fungle.brume.audit.QuasiIdDetector;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.schema.SchemaAnalyzer;
import com.fungle.brume.state.ExecutionStateReader;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AnonymityAuditor} — focuses on the spec validation
 * and quasi-id resolution paths. The SQL execution path is covered by the IT.
 */
class AnonymityAuditorTest {

    private static AnonymityAuditor buildAuditor(Optional<DataSource> ds) {
        return new AnonymityAuditor(
                mock(SchemaAnalyzer.class),
                mock(QuasiIdDetector.class),
                mock(ConfigLoader.class),
                mock(ReplicationProperties.class),
                ds,
                mock(ExecutionStateReader.class));
    }

    @Test
    @DisplayName("audit() throws AUDIT_QUASI_ID_REQUIRED when spec under-specified")
    void underSpecifiedThrows() {
        AnonymityAuditor auditor = buildAuditor(Optional.empty());
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of(), false, false, 5, 1.0, null, null);
        assertThatThrownBy(() -> auditor.audit(spec))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("--quasi-id or --auto-detect-quasi-id");
    }

    @Test
    @DisplayName("audit() throws AUDIT_TARGET_NOT_CONFIGURED when targetDataSource missing")
    void missingTargetThrows() {
        AnonymityAuditor auditor = buildAuditor(Optional.empty());
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("users", List.of("birth_date")), false, false, 5, 1.0, null, null);
        assertThatThrownBy(() -> auditor.audit(spec))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.type=JDBC");
    }

    @Test
    @DisplayName("resolveQuasiIdSets surfaces Levenshtein suggestion on unknown column")
    void unknownColumnSuggestsLevenshtein() {
        AnonymityAuditor auditor = buildAuditor(Optional.empty());
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("birth_date", "date", true),
                        new ColumnMetadata("postal_code", "integer", true)
                ), List.of(), "id")));
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("users", List.of("birth_dat")),  // typo: missing 'e'
                false, false, 5, 1.0, null, null);
        assertThatThrownBy(() -> auditor.resolveQuasiIdSets(spec, schema))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> {
                    ConfigurationException ce = (ConfigurationException) e;
                    assert ce.code() == BrumeErrorCode.AUDIT_UNKNOWN_COLUMN;
                })
                .hasMessageContaining("birth_dat");
    }

    @Test
    @DisplayName("resolveQuasiIdSets fails fast on unknown table")
    void unknownTableThrows() {
        AnonymityAuditor auditor = buildAuditor(Optional.empty());
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("id", "bigint", false)
                ), List.of(), "id")));
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("uzers", List.of("id")),  // typo
                false, false, 5, 1.0, null, null);
        assertThatThrownBy(() -> auditor.resolveQuasiIdSets(spec, schema))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> {
                    ConfigurationException ce = (ConfigurationException) e;
                    assert ce.code() == BrumeErrorCode.AUDIT_UNKNOWN_TABLE;
                });
    }

    @Test
    @DisplayName("#79g — unknown-table error wording is human-readable, not '12' tables")
    void unknownTableErrorMessageIsReadable() {
        AnonymityAuditor auditor = buildAuditor(Optional.empty());
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users", List.of(
                        new ColumnMetadata("id", "bigint", false)
                ), List.of(), "id")));
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("nonexistent_table", List.of("id")),
                false, false, 5, 1.0, null, null);

        assertThatThrownBy(() -> auditor.resolveQuasiIdSets(spec, schema))
                .isInstanceOf(ConfigurationException.class)
                // Pre-fix : message contained "'1' tables" — interpolated count as schema name.
                .hasMessageNotContaining("'1' tables")
                // Post-fix : "...(target schema has 1 tables)."
                .hasMessageContaining("target schema has 1 tables");
    }
}
