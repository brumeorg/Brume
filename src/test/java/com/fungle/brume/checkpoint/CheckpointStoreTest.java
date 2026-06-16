package com.fungle.brume.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the {@link CheckpointStore} read/write/atomic-move logic. */
class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @Test
    @DisplayName("write then read roundtrips the state")
    void writeReadRoundtrip() {
        Path file = tempDir.resolve("checkpoint.json");
        CheckpointStore store = new CheckpointStore(file, mapper());

        CheckpointState original = CheckpointState
                .initial("run-1", "test_brume", "abcdef", Instant.parse("2026-05-13T10:00:00Z"))
                .withTableCompleted("users", Instant.parse("2026-05-13T10:01:00Z"))
                .withTableCompleted("orders", Instant.parse("2026-05-13T10:02:00Z"));

        store.write(original);
        Optional<CheckpointState> reloaded = store.read();

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get()).isEqualTo(original);
    }

    @Test
    @DisplayName("read() returns empty when file is absent")
    void readEmptyWhenAbsent() {
        Path file = tempDir.resolve("does-not-exist.json");
        CheckpointStore store = new CheckpointStore(file, mapper());
        assertThat(store.read()).isEmpty();
    }

    @Test
    @DisplayName("read() throws on schema version mismatch with an actionable message")
    void readRejectsSchemaVersionMismatch() throws IOException {
        Path file = tempDir.resolve("checkpoint.json");
        // Hand-craft a checkpoint with an unsupported schemaVersion
        String malformed = """
                {
                  "schemaVersion": "2.0",
                  "runId": "run-9",
                  "startedAt": "2026-05-13T10:00:00Z",
                  "updatedAt": "2026-05-13T10:00:00Z",
                  "sourceSchema": "test_brume",
                  "configHash": "abc",
                  "completedTables": ["users"]
                }
                """;
        Files.writeString(file, malformed, StandardCharsets.UTF_8);

        CheckpointStore store = new CheckpointStore(file, mapper());
        assertThatThrownBy(store::read)
                .isInstanceOf(CheckpointStore.CheckpointIoException.class)
                .hasMessageContaining("schemaVersion=2.0")
                .hasMessageContaining("expects 1.0");
    }

    @Test
    @DisplayName("write() overwrites a pre-existing file (atomic replace)")
    void writeOverwrites() {
        Path file = tempDir.resolve("checkpoint.json");
        CheckpointStore store = new CheckpointStore(file, mapper());

        CheckpointState s1 = CheckpointState.initial("r", "s", "h", Instant.now());
        store.write(s1);
        CheckpointState s2 = s1.withTableCompleted("users", Instant.now());
        store.write(s2);

        assertThat(store.read()).hasValue(s2);
    }
}
