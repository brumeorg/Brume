package com.fungle.brume.scan;

import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.scan.ScanConfigBuilder.ScanResult;
import com.fungle.brume.scan.ScanConfigBuilder.SuggestedColumn;
import com.fungle.brume.scan.ScanConfigBuilder.SuggestedTable;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScanConfigBuilder} — pure logic, no Spring, no DB.
 */
class ScanConfigBuilderTest {

    @Test
    void buildsFpeIdForNumericPkAndFakeEmailForEmailColumn() {
        DatabaseSchema schema = singleTable("users", List.of(
                new ColumnMetadata("id", "bigint", false),
                new ColumnMetadata("email", "varchar", true),
                new ColumnMetadata("country_code", "varchar", true)   // no match
        ), List.of(), "id");

        ScanResult result = ScanConfigBuilder.build(schema);

        assertThat(result.tables()).hasSize(1);
        SuggestedTable users = result.tables().getFirst();
        assertThat(users.tableName()).isEqualTo("users");
        assertThat(users.columns()).extracting(SuggestedColumn::columnName)
                .containsExactly("id", "email");
        assertThat(users.columns().get(0).suggestion().strategy()).isEqualTo(Strategy.FPE_ID);
        assertThat(users.columns().get(1).suggestion().strategy()).isEqualTo(Strategy.FAKE);
        assertThat(result.totalSuggestions()).isEqualTo(2);
    }

    @Test
    void foreignKeyColumnsAreSkipped() {
        // orders.user_id FKs to users.id - must be left out so FkStrategyPropagator fills it.
        DatabaseSchema schema = singleTable("orders", List.of(
                new ColumnMetadata("id", "bigint", false),
                new ColumnMetadata("user_id", "bigint", false),      // FK, skip
                new ColumnMetadata("customer_email", "varchar", true) // regular PII match
        ), List.of(new ForeignKey("orders", "user_id", "users", "id")), "id");

        ScanResult result = ScanConfigBuilder.build(schema);

        assertThat(result.tables()).hasSize(1);
        assertThat(result.tables().getFirst().columns())
                .extracting(SuggestedColumn::columnName)
                .containsExactly("id", "customer_email")
                .doesNotContain("user_id");
    }

    @Test
    void uuidPkMapsToFpeUuid() {
        DatabaseSchema schema = singleTable("sessions", List.of(
                new ColumnMetadata("id", "uuid", false),
                new ColumnMetadata("token", "varchar", true)
        ), List.of(), "id");

        ScanResult result = ScanConfigBuilder.build(schema);

        assertThat(result.tables().getFirst().columns().getFirst().suggestion().strategy())
                .isEqualTo(Strategy.FPE_UUID);
    }

    @Test
    void tableWithNoMatchIsOmitted() {
        DatabaseSchema schema = singleTable("lookup", List.of(
                new ColumnMetadata("code", "varchar", false),
                new ColumnMetadata("label", "varchar", false)
        ), List.of(), null); // no PK, no PII column names

        ScanResult result = ScanConfigBuilder.build(schema);

        assertThat(result.tables()).isEmpty();
        assertThat(result.totalSuggestions()).isZero();
    }

    @Test
    void compositePkIsNotSingleColumnPk_soItDoesNotTriggerFpeId() {
        // singlePrimaryKeyColumn() returns null when the PK is composite, so neither
        // column should get an FPE_ID rule from the PK branch. The columns can still
        // match PII patterns on their own (not the case here).
        TableMetadata table = new TableMetadata("link_table",
                List.of(new ColumnMetadata("left_id", "bigint", false),
                        new ColumnMetadata("right_id", "bigint", false)),
                List.of(),
                List.of("left_id", "right_id"));
        Map<String, TableMetadata> tables = new LinkedHashMap<>();
        tables.put(table.name(), table);
        DatabaseSchema schema = new DatabaseSchema(tables);

        ScanResult result = ScanConfigBuilder.build(schema);

        assertThat(result.tables()).isEmpty();
    }

    private static DatabaseSchema singleTable(String name, List<ColumnMetadata> columns,
                                              List<ForeignKey> fks, String pk) {
        Map<String, TableMetadata> tables = new LinkedHashMap<>();
        tables.put(name, new TableMetadata(name, columns, fks, pk));
        return new DatabaseSchema(tables);
    }
}
