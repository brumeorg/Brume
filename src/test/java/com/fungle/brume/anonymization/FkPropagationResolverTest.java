package com.fungle.brume.anonymization;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FkPropagationResolver} — verifies that {@link Strategy#FPE_ID} and
 * {@link Strategy#FPE_UUID} are automatically propagated from anonymized PKs to every FK
 * column referencing them.
 *
 * <p>This is the missing feature documented on {@link Strategy#FPE_ID}: "FK columns pointing
 * to an FPE_ID primary key are automatically propagated." Without it, the dump fails with FK
 * violations at post-data constraint creation because the FK column keeps its original value
 * while the PK has been encrypted.
 */
class FkPropagationResolverTest {

    private final FkPropagationResolver resolver = new FkPropagationResolver();

    private static AnonymizerConfig configWith(TableAnonymizationConfig... tables) {
        return new AnonymizerConfig(
                new ExtractionConfig(3, 1000, 1000, 10000, List.of()),
                new AnonymizationConfig(List.of(), List.of(tables)));
    }

    private static ColumnConfig col(String name, Strategy strategy) {
        return new ColumnConfig(name, strategy, null, null);
    }

    @Test
    @DisplayName("FPE_ID on PK propagates to a self-referential FK in the same table")
    void selfReferentialFkPropagatesFpeId() {
        // utilisateur.id_utilisateur_creation → utilisateur.id_utilisateur (self-ref)
        ForeignKey selfRef = new ForeignKey(
                "utilisateur", "id_utilisateur_creation", "utilisateur", "id_utilisateur");
        TableMetadata utilisateur = new TableMetadata(
                "utilisateur", List.of(), List.of(selfRef), "id_utilisateur");
        DatabaseSchema schema = new DatabaseSchema(Map.of("utilisateur", utilisateur));

        AnonymizerConfig input = configWith(
                new TableAnonymizationConfig("utilisateur",
                        List.of(col("id_utilisateur", Strategy.FPE_ID))));

        AnonymizerConfig out = resolver.propagate(input, schema);

        TableAnonymizationConfig table = out.anonymization().tables().getFirst();
        assertThat(table.columns())
                .as("PK rule preserved + FK rule synthesized with FPE_ID")
                .extracting(ColumnConfig::name, ColumnConfig::strategy)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("id_utilisateur", Strategy.FPE_ID),
                        org.assertj.core.groups.Tuple.tuple("id_utilisateur_creation", Strategy.FPE_ID));
    }

    @Test
    @DisplayName("FPE_ID propagates to a FK in another table that has no existing rules")
    void crossTableFkPropagatesToTableWithoutRules() {
        // commande.id_utilisateur_creation → utilisateur.id_utilisateur
        ForeignKey fk = new ForeignKey(
                "commande", "id_utilisateur_creation", "utilisateur", "id_utilisateur");
        TableMetadata utilisateur = new TableMetadata(
                "utilisateur", List.of(), List.of(), "id_utilisateur");
        TableMetadata commande = new TableMetadata(
                "commande", List.of(), List.of(fk), "id_commande");
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "utilisateur", utilisateur, "commande", commande));

        AnonymizerConfig input = configWith(
                new TableAnonymizationConfig("utilisateur",
                        List.of(col("id_utilisateur", Strategy.FPE_ID))));

        AnonymizerConfig out = resolver.propagate(input, schema);

        TableAnonymizationConfig commandeRules = out.anonymization().tables().stream()
                .filter(t -> "commande".equals(t.table()))
                .findFirst().orElseThrow();
        assertThat(commandeRules.columns())
                .as("commande received a synthetic FPE_ID rule on its FK column")
                .extracting(ColumnConfig::name, ColumnConfig::strategy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "id_utilisateur_creation", Strategy.FPE_ID));
    }

    @Test
    @DisplayName("Existing user rule on FK column is never overridden by propagation")
    void explicitUserRuleWins() {
        ForeignKey fk = new ForeignKey(
                "utilisateur", "id_utilisateur_creation", "utilisateur", "id_utilisateur");
        TableMetadata utilisateur = new TableMetadata(
                "utilisateur", List.of(), List.of(fk), "id_utilisateur");
        DatabaseSchema schema = new DatabaseSchema(Map.of("utilisateur", utilisateur));

        // User explicitly chose NULLIFY on the FK — propagation must NOT overwrite this
        AnonymizerConfig input = configWith(
                new TableAnonymizationConfig("utilisateur", List.of(
                        col("id_utilisateur", Strategy.FPE_ID),
                        col("id_utilisateur_creation", Strategy.NULLIFY))));

        AnonymizerConfig out = resolver.propagate(input, schema);

        TableAnonymizationConfig table = out.anonymization().tables().getFirst();
        assertThat(table.columns())
                .extracting(ColumnConfig::name, ColumnConfig::strategy)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("id_utilisateur", Strategy.FPE_ID),
                        org.assertj.core.groups.Tuple.tuple("id_utilisateur_creation", Strategy.NULLIFY));
    }

    @Test
    @DisplayName("Strategies other than FPE_ID/FPE_UUID are not propagated (would break FKs)")
    void nonFormatPreservingStrategiesNotPropagated() {
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        TableMetadata users = new TableMetadata("users", List.of(), List.of(), "id");
        TableMetadata orders = new TableMetadata("orders", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("users", users, "orders", orders));

        // HASH on PK — propagating it to user_id would still break FKs because hash output
        // length differs. We do NOT propagate non-deterministic / non-format-preserving
        // strategies.
        AnonymizerConfig input = configWith(
                new TableAnonymizationConfig("users", List.of(col("id", Strategy.HASH))));

        AnonymizerConfig out = resolver.propagate(input, schema);

        // No new rule added on orders
        assertThat(out.anonymization().tables())
                .as("only the original users rule is present")
                .hasSize(1)
                .first()
                .satisfies(t -> assertThat(t.table()).isEqualTo("users"));
    }

    @Test
    @DisplayName("FPE_UUID on PK also propagates to its FK columns")
    void fpeUuidPropagates() {
        ForeignKey fk = new ForeignKey("orders", "user_uuid", "users", "uuid");
        TableMetadata users = new TableMetadata("users", List.of(), List.of(), "uuid");
        TableMetadata orders = new TableMetadata("orders", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("users", users, "orders", orders));

        AnonymizerConfig input = configWith(
                new TableAnonymizationConfig("users", List.of(col("uuid", Strategy.FPE_UUID))));

        AnonymizerConfig out = resolver.propagate(input, schema);

        TableAnonymizationConfig ordersRules = out.anonymization().tables().stream()
                .filter(t -> "orders".equals(t.table()))
                .findFirst().orElseThrow();
        assertThat(ordersRules.columns())
                .extracting(ColumnConfig::name, ColumnConfig::strategy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "user_uuid", Strategy.FPE_UUID));
    }

    @Test
    @DisplayName("No FPE_ID PKs in config: propagation is a no-op (returns input unchanged)")
    void noPropagationWhenNoFpeIdPks() {
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        TableMetadata users = new TableMetadata("users", List.of(), List.of(), "id");
        TableMetadata orders = new TableMetadata("orders", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("users", users, "orders", orders));

        AnonymizerConfig input = configWith(
                new TableAnonymizationConfig("users", List.of(col("email", Strategy.FAKE))));

        AnonymizerConfig out = resolver.propagate(input, schema);

        assertThat(out)
                .as("no FPE_ID/FPE_UUID PKs → config returned unchanged")
                .isSameAs(input);
    }

    @Test
    @DisplayName("Multi-hop chain: PK → FK → FK all receive FPE_ID propagation")
    void multiHopFkChain() {
        // users.id (FPE_ID) ← orders.user_id ← order_items.order_user_id (transitive — but
        // order_items only references orders, not users directly). Test: propagation runs
        // only from a directly-anonymized PK, not transitively. order_items.order_user_id is
        // NOT propagated since order_items references orders (not users).
        ForeignKey fk1 = new ForeignKey("orders", "user_id", "users", "id");
        ForeignKey fk2 = new ForeignKey("order_items", "order_id", "orders", "id");
        TableMetadata users = new TableMetadata("users", List.of(), List.of(), "id");
        TableMetadata orders = new TableMetadata("orders", List.of(), List.of(fk1), "id");
        TableMetadata orderItems = new TableMetadata("order_items", List.of(), List.of(fk2), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", users, "orders", orders, "order_items", orderItems));

        // FPE_ID on users.id AND orders.id → both propagate to their respective FKs
        AnonymizerConfig input = configWith(
                new TableAnonymizationConfig("users", List.of(col("id", Strategy.FPE_ID))),
                new TableAnonymizationConfig("orders", List.of(col("id", Strategy.FPE_ID))));

        AnonymizerConfig out = resolver.propagate(input, schema);

        // orders.user_id propagated from users.id
        TableAnonymizationConfig ordersOut = out.anonymization().tables().stream()
                .filter(t -> "orders".equals(t.table())).findFirst().orElseThrow();
        assertThat(ordersOut.columns())
                .extracting(ColumnConfig::name, ColumnConfig::strategy)
                .contains(org.assertj.core.groups.Tuple.tuple("user_id", Strategy.FPE_ID))
                .contains(org.assertj.core.groups.Tuple.tuple("id", Strategy.FPE_ID));

        // order_items.order_id propagated from orders.id
        TableAnonymizationConfig orderItemsOut = out.anonymization().tables().stream()
                .filter(t -> "order_items".equals(t.table())).findFirst().orElseThrow();
        assertThat(orderItemsOut.columns())
                .extracting(ColumnConfig::name, ColumnConfig::strategy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("order_id", Strategy.FPE_ID));
    }
}
