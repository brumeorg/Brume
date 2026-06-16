package com.fungle.brume.extraction.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for #79c — composite PK key on {@link ExtractionResult.PkKey}.
 *
 * <p>Pre-fix : {@code pkIndex} was a {@code Set<String>} with elements built as
 * {@code table + "." + pkColumn + "." + pkValue}. Identifiers containing {@code "."}
 * (PostgreSQL quoted identifier — legal under {@code "users.profile"}) silently collided
 * with the unrelated row {@code (table="users", pkColumn="profile.id")}.
 *
 * <p>Post-fix : {@code pkIndex} is a {@code Set<PkKey>} where {@link
 * ExtractionResult.PkKey} is a record with value-based equals/hashCode — no separator
 * ambiguity possible.
 */
class ExtractionResultKeyTest {

    private static ExtractedRow row(String table, String pkColumn, Object pkValue) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(pkColumn, pkValue);
        return new ExtractedRow(table, data);
    }

    @Test
    @DisplayName("#79c — distinct rows with collidable concat string yield distinct PK keys")
    void adversarialDotCollisionDoesNotMatch() {
        ExtractionResult result = new ExtractionResult();

        // Pre-fix : both string keys would have been "users.profile.id.42" and collided.
        // (table="users.profile", pkColumn="id", pkValue=42)
        // (table="users",         pkColumn="profile.id", pkValue=42)
        result.addWithPk(row("users.profile", "id", 42L), "id");

        // The second "row" represents a different table / column with a "." in the column
        // name. The pkIndex must NOT consider it already present.
        assertThat(result.containsPrimaryKey("users", "profile.id", 42L))
                .as("a different (table, column) tuple must not collide on concat")
                .isFalse();

        // Sanity : the original row is still found via its proper key.
        assertThat(result.containsPrimaryKey("users.profile", "id", 42L)).isTrue();
    }

    @Test
    @DisplayName("#79c — tryAddWithPk dedup still works with typed key")
    void tryAddWithPkStillDedupsSameRow() {
        ExtractionResult result = new ExtractionResult();
        assertThat(result.tryAddWithPk(row("users", "id", 42L), "id")).isTrue();
        // Second call with same (table, pkColumn, pkValue) → skipped.
        assertThat(result.tryAddWithPk(row("users", "id", 42L), "id")).isFalse();
        // But a different table with same id → accepted.
        assertThat(result.tryAddWithPk(row("orders", "id", 42L), "id")).isTrue();
    }
}
