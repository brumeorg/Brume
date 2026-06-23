package com.fungle.brume.schema.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Régression #81b (ADR-0042) — contrat du modèle {@link ForeignKey} composite : ctor compat
 * single-column, détection composite, accesseurs de convenance, et invariant d'alignement
 * positionnel des deux listes de colonnes.
 */
class ForeignKeyTest {

    @Test
    @DisplayName("single-column compat constructor wraps into one-element aligned lists")
    void singleColumnCompatCtor() {
        ForeignKey fk = new ForeignKey("orders", "user_id", "users", "id");
        assertThat(fk.fromColumns()).containsExactly("user_id");
        assertThat(fk.toColumns()).containsExactly("id");
        assertThat(fk.fromColumn()).isEqualTo("user_id");
        assertThat(fk.toColumn()).isEqualTo("id");
        assertThat(fk.isComposite()).isFalse();
    }

    @Test
    @DisplayName("composite FK keeps positionally-aligned column lists; fromColumn() is the first")
    void compositeFk() {
        ForeignKey fk = new ForeignKey("memberships",
                List.of("tenant_id", "user_id"), "users", List.of("tenant_id", "id"));
        assertThat(fk.isComposite()).isTrue();
        assertThat(fk.fromColumns()).containsExactly("tenant_id", "user_id");
        assertThat(fk.toColumns()).containsExactly("tenant_id", "id");
        assertThat(fk.fromColumn()).isEqualTo("tenant_id");
        assertThat(fk.toColumn()).isEqualTo("tenant_id");
    }

    @Test
    @DisplayName("mismatched column-list sizes are rejected (positional alignment invariant)")
    void mismatchedSizesRejected() {
        assertThatThrownBy(() -> new ForeignKey("t", List.of("a", "b"), "p", List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positionally aligned");
    }

    @Test
    @DisplayName("an empty column pair is rejected")
    void emptyRejected() {
        assertThatThrownBy(() -> new ForeignKey("t", List.of(), "p", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one column pair");
    }
}
