package com.fungle.brume.checkpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the {@link CheckpointState} record. */
class CheckpointStateTest {

    private static final Instant T0 = Instant.parse("2026-05-13T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-13T10:05:00Z");

    @Test
    @DisplayName("initial() builds an empty completedTables list")
    void initialEmpty() {
        CheckpointState s = CheckpointState.initial("run-1", "test_brume", "deadbeef", T0);
        assertThat(s.completedTables()).isEmpty();
        assertThat(s.startedAt()).isEqualTo(T0);
        assertThat(s.updatedAt()).isEqualTo(T0);
        assertThat(s.schemaVersion()).isEqualTo(CheckpointState.CURRENT_SCHEMA_VERSION);
    }

    @Test
    @DisplayName("withTableCompleted appends in order and bumps updatedAt")
    void withTableCompletedAppendsInOrder() {
        CheckpointState s = CheckpointState.initial("run-1", "test_brume", "h", T0);
        CheckpointState s1 = s.withTableCompleted("users", T1);
        CheckpointState s2 = s1.withTableCompleted("orders", T1);
        assertThat(s2.completedTables()).containsExactly("users", "orders");
        assertThat(s2.updatedAt()).isEqualTo(T1);
        assertThat(s2.startedAt()).as("startedAt is preserved").isEqualTo(T0);
    }

    @Test
    @DisplayName("withTableCompleted is idempotent — same table added twice yields one entry")
    void withTableCompletedIsIdempotent() {
        CheckpointState s = CheckpointState.initial("r", "s", "h", T0)
                .withTableCompleted("users", T1)
                .withTableCompleted("users", T1);
        assertThat(s.completedTables()).containsExactly("users");
    }

    @Test
    @DisplayName("isCompleted reports membership")
    void isCompleted() {
        CheckpointState s = CheckpointState.initial("r", "s", "h", T0)
                .withTableCompleted("users", T1);
        assertThat(s.isCompleted("users")).isTrue();
        assertThat(s.isCompleted("orders")).isFalse();
    }

    @Test
    @DisplayName("completedTables is defensively copied on construction")
    void completedTablesDefensiveCopy() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("users"));
        CheckpointState s = new CheckpointState(
                "1.0", "r", T0, T0, "s", "h", mutable);
        mutable.add("ghost");
        assertThat(s.completedTables()).containsExactly("users");
    }
}
