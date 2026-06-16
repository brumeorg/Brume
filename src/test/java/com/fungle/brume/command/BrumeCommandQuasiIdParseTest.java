package com.fungle.brume.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BrumeCommand#parseQuasiIdFlags(List)} — the --quasi-id
 * flag parser.
 */
class BrumeCommandQuasiIdParseTest {

    @Test
    @DisplayName("empty / null input returns empty map")
    void emptyInput() {
        assertThat(BrumeCommand.parseQuasiIdFlags(null)).isEmpty();
        assertThat(BrumeCommand.parseQuasiIdFlags(List.of())).isEmpty();
    }

    @Test
    @DisplayName("single 'table:col' input parsed to one entry")
    void singleEntry() {
        Map<String, List<String>> r = BrumeCommand.parseQuasiIdFlags(
                List.of("users:birth_date,postal_code"));
        assertThat(r)
                .hasSize(1)
                .containsEntry("users", List.of("birth_date", "postal_code"));
    }

    @Test
    @DisplayName("multiple entries preserve declaration order")
    void multipleEntriesOrdered() {
        Map<String, List<String>> r = BrumeCommand.parseQuasiIdFlags(
                List.of("users:birth_date", "orders:created_at,zip"));
        assertThat(r.keySet()).containsExactly("users", "orders");
        assertThat(r.get("orders")).containsExactly("created_at", "zip");
    }

    @Test
    @DisplayName("whitespace around table and columns is trimmed")
    void whitespaceTrimmed() {
        Map<String, List<String>> r = BrumeCommand.parseQuasiIdFlags(
                List.of("  users : birth_date , postal_code  "));
        // Note: ":" splits on first colon — table part is "  users " (trimmed to "users")
        assertThat(r).containsKey("users");
        assertThat(r.get("users")).containsExactly("birth_date", "postal_code");
    }

    @Test
    @DisplayName("missing colon throws with actionable message")
    void missingColon() {
        assertThatThrownBy(() -> BrumeCommand.parseQuasiIdFlags(List.of("users_birth_date")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected 'table:col1");
    }

    @Test
    @DisplayName("colon at end (no columns) throws")
    void trailingColon() {
        assertThatThrownBy(() -> BrumeCommand.parseQuasiIdFlags(List.of("users:")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty columns segment throws")
    void emptyColumns() {
        assertThatThrownBy(() -> BrumeCommand.parseQuasiIdFlags(List.of("users: , ,")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no column");
    }
}
