package com.fungle.brume.checkpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ConfigHash}. */
class ConfigHashTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("same content yields same hash (deterministic)")
    void sameContentSameHash() throws IOException {
        Path a = tempDir.resolve("a.yaml");
        Path b = tempDir.resolve("b.yaml");
        String content = "extraction:\n  tables:\n    - table: users\n";
        Files.writeString(a, content, StandardCharsets.UTF_8);
        Files.writeString(b, content, StandardCharsets.UTF_8);

        assertThat(ConfigHash.of(a)).isEqualTo(ConfigHash.of(b));
    }

    @Test
    @DisplayName("CR/LF and LF yield the same hash (cross-platform stability)")
    void crLfNormalisation() throws IOException {
        Path lf = tempDir.resolve("lf.yaml");
        Path crlf = tempDir.resolve("crlf.yaml");
        Files.writeString(lf, "a\nb\nc\n", StandardCharsets.UTF_8);
        Files.writeString(crlf, "a\r\nb\r\nc\r\n", StandardCharsets.UTF_8);

        assertThat(ConfigHash.of(lf)).isEqualTo(ConfigHash.of(crlf));
    }

    @Test
    @DisplayName("different content yields different hash")
    void differentContentDifferentHash() throws IOException {
        Path a = tempDir.resolve("a.yaml");
        Path b = tempDir.resolve("b.yaml");
        Files.writeString(a, "users:\n", StandardCharsets.UTF_8);
        Files.writeString(b, "orders:\n", StandardCharsets.UTF_8);

        assertThat(ConfigHash.of(a)).isNotEqualTo(ConfigHash.of(b));
    }

    @Test
    @DisplayName("hash is a 64-char lowercase hex string (SHA-256)")
    void hashFormat() throws IOException {
        Path a = tempDir.resolve("a.yaml");
        Files.writeString(a, "x", StandardCharsets.UTF_8);
        String h = ConfigHash.of(a);
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("missing file throws CheckpointIoException")
    void missingFileThrows() {
        Path missing = tempDir.resolve("nope.yaml");
        assertThatThrownBy(() -> ConfigHash.of(missing))
                .isInstanceOf(CheckpointStore.CheckpointIoException.class);
    }
}
