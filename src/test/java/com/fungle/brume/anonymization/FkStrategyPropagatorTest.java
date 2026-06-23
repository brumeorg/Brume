package com.fungle.brume.anonymization;

import com.fungle.brume.config.ConfigurationException;
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
import com.fungle.brume.schema.model.ForeignKey;
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
 * Unit tests for {@link FkStrategyPropagator}.
 *
 * <p>No Spring context — the propagator is instantiated directly. Schemas are built in-line
 * with the minimal shape needed for each scenario.
 */
class FkStrategyPropagatorTest {

    private FkStrategyPropagator propagator;

    @BeforeEach
    void setUp() {
        propagator = new FkStrategyPropagator();
    }

    // -------------------------------------------------------------------------
    // Happy path — propagation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("propagates FPE_ID from PK to a single FK that has no declared rule")
    void propagatesFpeIdToUndeclaredFk() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.FPE_ID))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        ColumnConfig propagated = findRule(enriched, "orders", "user_id");
        assertThat(propagated).isNotNull();
        assertThat(propagated.strategy()).isEqualTo(Strategy.FPE_ID);
    }

    @Test
    @DisplayName("propagates per-position across a composite FK — each column inherits its own PK strategy (#81b Q4)")
    void propagatesAcrossCompositeForeignKey() {
        AnonymizerConfig config = configWith(
                tableRules("accounts",
                        col("tenant_id", Strategy.FPE_ID),
                        col("account_id", Strategy.HASH))
        );
        ForeignKey compositeFk = new ForeignKey("memberships",
                List.of("tenant_id", "account_id"), "accounts", List.of("tenant_id", "account_id"));
        DatabaseSchema schema = schema(
                new TableMetadata("accounts",
                        List.of(new ColumnMetadata("tenant_id", "integer", false),
                                new ColumnMetadata("account_id", "integer", false)),
                        List.of(),
                        List.of("tenant_id", "account_id")),
                new TableMetadata("memberships",
                        List.of(new ColumnMetadata("id", "bigint", false)),
                        List.of(compositeFk),
                        List.of("id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        // Each FK column inherits the strategy of its positionally-aligned PK column independently.
        assertThat(findRule(enriched, "memberships", "tenant_id").strategy()).isEqualTo(Strategy.FPE_ID);
        assertThat(findRule(enriched, "memberships", "account_id").strategy()).isEqualTo(Strategy.HASH);
    }

    @Test
    @DisplayName("propagates HASH and FPE_UUID alongside FPE_ID")
    void propagatesAllDeterministicStrategies() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.HASH)),
                tableRules("sessions", col("id", Strategy.FPE_UUID))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                table("sessions", "id"),
                tableWithFk("logs", "id",
                        fk("logs", "user_id", "users", "id"),
                        fk("logs", "session_id", "sessions", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        assertThat(findRule(enriched, "logs", "user_id").strategy()).isEqualTo(Strategy.HASH);
        assertThat(findRule(enriched, "logs", "session_id").strategy()).isEqualTo(Strategy.FPE_UUID);
    }

    @Test
    @DisplayName("propagates self-FK (e.g. users.manager_id → users.id)")
    void propagatesSelfFk() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.FPE_ID))
        );
        DatabaseSchema schema = schema(
                tableWithFk("users", "id", fk("users", "manager_id", "users", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        ColumnConfig propagated = findRule(enriched, "users", "manager_id");
        assertThat(propagated.strategy()).isEqualTo(Strategy.FPE_ID);
    }

    @Test
    @DisplayName("propagation cascades through multi-hop FK chains in a single pass")
    void propagatesAcrossChain() {
        // users.id (FPE_ID) ← orders.user_id ← order_items.order_id (transitive via orders.id)
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.FPE_ID)),
                tableRules("orders", col("id", Strategy.FPE_ID))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id")),
                tableWithFk("order_items", "id", fk("order_items", "order_id", "orders", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        assertThat(findRule(enriched, "orders", "user_id").strategy()).isEqualTo(Strategy.FPE_ID);
        assertThat(findRule(enriched, "order_items", "order_id").strategy()).isEqualTo(Strategy.FPE_ID);
    }

    // -------------------------------------------------------------------------
    // No-op cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("does nothing when the parent PK is not anonymized")
    void noOpWhenPkNotDeclared() {
        AnonymizerConfig config = configWith(/* no rules at all */);
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        assertThat(findRule(enriched, "orders", "user_id")).isNull();
    }

    @Test
    @DisplayName("does nothing when the parent PK is declared KEEP")
    void noOpWhenPkIsKeep() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.KEEP))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        assertThat(findRule(enriched, "orders", "user_id")).isNull();
    }

    @Test
    @DisplayName("user-declared FK rule with same strategy as PK is left untouched (no duplication)")
    void respectsCompatibleUserDeclaration() {
        ColumnConfig userDeclared = new ColumnConfig("user_id", Strategy.FPE_ID, null, null);
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.FPE_ID)),
                tableRules("orders", userDeclared)
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        // The user's ColumnConfig instance must still be present (no replacement, no shadow rule).
        List<ColumnConfig> ordersCols = findTable(enriched, "orders").columns();
        assertThat(ordersCols).filteredOn(c -> c.name().equals("user_id")).hasSize(1);
        assertThat(ordersCols.get(0)).isSameAs(userDeclared);
    }

    @Test
    @DisplayName("propagation is idempotent — running twice does not re-add or duplicate rules")
    void idempotent() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.FPE_ID))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        AnonymizerConfig once = propagator.propagate(config, schema);
        AnonymizerConfig twice = propagator.propagate(once, schema);

        assertThat(findTable(twice, "orders").columns())
                .filteredOn(c -> c.name().equals("user_id")).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // FAKE strategy on referenced PK
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects FAKE on referenced PK when no linked_columns covers the FK")
    void rejectsFakeOnReferencedPkWithoutLinkedColumns() {
        AnonymizerConfig config = configWith(
                List.of(),
                tableRules("users", col("email", Strategy.FAKE, SemanticType.EMAIL))
        );
        DatabaseSchema schema = schema(
                table("users", "email"), // pretend email is the PK for this scenario
                tableWithFk("audit_logs", "id",
                        fk("audit_logs", "user_email", "users", "email"))
        );

        assertThatThrownBy(() -> propagator.propagate(config, schema))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("FAKE")
                .hasMessageContaining("linked_columns")
                .hasMessageContaining("audit_logs.user_email");
    }

    @Test
    @DisplayName("accepts FAKE on referenced PK when a linked_columns covers both endpoints")
    void acceptsFakeWhenLinkedColumnsCovers() {
        AnonymizerConfig config = configWith(
                List.of(new LinkedColumnsConfig("user_email", List.of(
                        new ColumnReference("users", "email"),
                        new ColumnReference("audit_logs", "user_email")))),
                tableRules("users", col("email", Strategy.FAKE, SemanticType.EMAIL))
        );
        DatabaseSchema schema = schema(
                table("users", "email"),
                tableWithFk("audit_logs", "id",
                        fk("audit_logs", "user_email", "users", "email"))
        );

        assertThatCode(() -> propagator.propagate(config, schema)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Integrity-breaking strategies on referenced PK
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects NULLIFY on a referenced PK")
    void rejectsNullifyOnReferencedPk() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.NULLIFY))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        assertThatThrownBy(() -> propagator.propagate(config, schema))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("NULLIFY")
                .hasMessageContaining("users.id")
                .hasMessageContaining("orders.user_id");
    }

    @Test
    @DisplayName("rejects MASK on a referenced PK")
    void rejectsMaskOnReferencedPk() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.MASK))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        assertThatThrownBy(() -> propagator.propagate(config, schema))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("MASK");
    }

    // -------------------------------------------------------------------------
    // FK explicitly declared with conflicting strategy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects FK declared with a strategy different from its parent PK")
    void rejectsConflictingFkDeclaration() {
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.FPE_ID)),
                tableRules("orders", col("user_id", Strategy.KEEP))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        assertThatThrownBy(() -> propagator.propagate(config, schema))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("orders.user_id")
                .hasMessageContaining("KEEP")
                .hasMessageContaining("FPE_ID");
    }

    @Test
    @DisplayName("accepts NULLIFY on an FK as an explicit opt-out from the parent link")
    void acceptsNullifyOnFkAsOptOut() {
        // users.manager_id is a self-FK to users.id; declaring it NULLIFY is a legitimate way
        // to say "drop the link in target, set it to NULL".
        AnonymizerConfig config = configWith(
                tableRules("users",
                        col("id", Strategy.FPE_ID),
                        col("manager_id", Strategy.NULLIFY))
        );
        DatabaseSchema schema = schema(
                tableWithFk("users", "id", fk("users", "manager_id", "users", "id"))
        );

        assertThatCode(() -> propagator.propagate(config, schema)).doesNotThrowAnyException();

        // The user-declared NULLIFY rule must be preserved verbatim.
        ColumnConfig managerRule = findRule(propagator.propagate(config, schema), "users", "manager_id");
        assertThat(managerRule.strategy()).isEqualTo(Strategy.NULLIFY);
    }

    // -------------------------------------------------------------------------
    // Equivalence-class propagation (#70b / ADR-0044) — supersedes ADR-0023's unidirectional
    // PK → FK semantics. Declaring on any column of a class propagates to the rest.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("propagates FK → PK: declaring the strategy on a child FK fills in the parent PK (the founder's main_courante_utilisateur case)")
    void propagatesUpFromFkToReferencedPk() {
        // Composite-junction shape: main_courante_utilisateur has a composite PK whose two
        // components are themselves FKs to main_courante.id and utilisateur.id. Declaring
        // the strategy on one FK column should propagate to the upstream PK without the
        // user having to add the parent table to the config.
        AnonymizerConfig config = configWith(
                tableRules("main_courante_utilisateur",
                        col("id_main_courante", Strategy.FPE_ID))
        );
        DatabaseSchema schema = schema(
                table("main_courante", "id"),
                tableWithFk("main_courante_utilisateur", "id",
                        fk("main_courante_utilisateur", "id_main_courante",
                                "main_courante", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        ColumnConfig parent = findRule(enriched, "main_courante", "id");
        assertThat(parent).as("upstream PK must inherit the class strategy").isNotNull();
        assertThat(parent.strategy()).isEqualTo(Strategy.FPE_ID);
    }

    @Test
    @DisplayName("propagates to siblings: declaring on one FK fills in the PK and any other FK sharing it")
    void propagatesAcrossSiblingsOfTheClass() {
        // Class = {users.id, orders.user_id, audit_logs.user_id}.
        // User declares the strategy on the audit_logs side only — both other columns
        // should be enriched.
        AnonymizerConfig config = configWith(
                tableRules("audit_logs", col("user_id", Strategy.FPE_ID))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id")),
                tableWithFk("audit_logs", "id",
                        fk("audit_logs", "user_id", "users", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        assertThat(findRule(enriched, "users", "id").strategy()).isEqualTo(Strategy.FPE_ID);
        assertThat(findRule(enriched, "orders", "user_id").strategy()).isEqualTo(Strategy.FPE_ID);
    }

    @Test
    @DisplayName("rejects a class with two diverging non-NULLIFY strategies declared explicitly (Q2 fail-fast)")
    void rejectsConflictingClassDeclarations() {
        // users.id = FPE_ID and orders.user_id = HASH are in the same class — they cannot
        // both hold true. Brume must refuse rather than silently picking one.
        AnonymizerConfig config = configWith(
                tableRules("users", col("id", Strategy.FPE_ID)),
                tableRules("orders", col("user_id", Strategy.HASH))
        );
        DatabaseSchema schema = schema(
                table("users", "id"),
                tableWithFk("orders", "id", fk("orders", "user_id", "users", "id"))
        );

        assertThatThrownBy(() -> propagator.propagate(config, schema))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("conflicting strategies")
                .hasMessageContaining("users.id=FPE_ID")
                .hasMessageContaining("orders.user_id=HASH");
    }

    @Test
    @DisplayName("composite junction: each PK component is its own class — declaring on each FK propagates to its own parent PK independently")
    void compositeJunctionPropagatesPerPosition() {
        // main_courante_utilisateur PK = (id_main_courante, id_utilisateur), both FK to
        // their respective parents. Declaring on both should propagate to both parents
        // (two separate equivalence classes, position-wise per #81b).
        AnonymizerConfig config = configWith(
                tableRules("main_courante_utilisateur",
                        col("id_main_courante", Strategy.FPE_ID),
                        col("id_utilisateur", Strategy.HASH))
        );
        DatabaseSchema schema = schema(
                table("main_courante", "id"),
                table("utilisateur", "id"),
                tableWithFk("main_courante_utilisateur", "id_main_courante",
                        fk("main_courante_utilisateur", "id_main_courante",
                                "main_courante", "id"),
                        fk("main_courante_utilisateur", "id_utilisateur",
                                "utilisateur", "id"))
        );

        AnonymizerConfig enriched = propagator.propagate(config, schema);

        assertThat(findRule(enriched, "main_courante", "id").strategy())
                .as("FPE_ID propagates to main_courante.id via the id_main_courante class")
                .isEqualTo(Strategy.FPE_ID);
        assertThat(findRule(enriched, "utilisateur", "id").strategy())
                .as("HASH propagates to utilisateur.id via the id_utilisateur class (independent class)")
                .isEqualTo(Strategy.HASH);
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

    private static TableAnonymizationConfig tableRules(String table, ColumnConfig... cols) {
        return new TableAnonymizationConfig(table, List.of(cols));
    }

    private static AnonymizerConfig configWith(TableAnonymizationConfig... tables) {
        return configWith(List.of(), tables);
    }

    private static AnonymizerConfig configWith(List<LinkedColumnsConfig> linked,
                                                TableAnonymizationConfig... tables) {
        ExtractionConfig extraction = new ExtractionConfig(3, 1000, 1000, 10_000,
                List.of(new TableExtractionConfig("users", null)));
        AnonymizationConfig anon = new AnonymizationConfig(linked, List.of(tables));
        return new AnonymizerConfig(extraction, anon);
    }

    private static ForeignKey fk(String fromT, String fromC, String toT, String toC) {
        return new ForeignKey(fromT, fromC, toT, toC);
    }

    private static TableMetadata table(String name, String pk) {
        return new TableMetadata(name,
                List.of(new ColumnMetadata(pk, "bigint", false)),
                List.of(),
                pk);
    }

    private static TableMetadata tableWithFk(String name, String pk, ForeignKey... fks) {
        return new TableMetadata(name,
                List.of(new ColumnMetadata(pk, "bigint", false)),
                List.of(fks),
                pk);
    }

    private static DatabaseSchema schema(TableMetadata... tables) {
        Map<String, TableMetadata> map = new LinkedHashMap<>();
        for (TableMetadata t : tables) map.put(t.name(), t);
        return new DatabaseSchema(map);
    }

    private static ColumnConfig findRule(AnonymizerConfig config, String table, String column) {
        TableAnonymizationConfig t = findTable(config, table);
        if (t == null || t.columns() == null) return null;
        return t.columns().stream().filter(c -> c.name().equals(column)).findFirst().orElse(null);
    }

    private static TableAnonymizationConfig findTable(AnonymizerConfig config, String table) {
        return config.anonymization().tables().stream()
                .filter(t -> t.table().equals(table)).findFirst().orElse(null);
    }
}
