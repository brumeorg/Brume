package com.fungle.brume.init;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitFileWriterTest {

    @Test
    @DisplayName("creates file from classpath when target does not exist")
    void createsFile(@TempDir Path tmp) throws IOException {
        InitFileWriter writer = new InitFileWriter(tmp);
        InitFileWriter.Outcome outcome = writer.writeFromClasspath(
                BrumeInitCommand.BRUME_YML_RESOURCE, "brume.yml", false);
        assertThat(outcome).isEqualTo(InitFileWriter.Outcome.CREATED);
        assertThat(Files.exists(tmp.resolve("brume.yml"))).isTrue();
        assertThat(Files.readString(tmp.resolve("brume.yml"))).contains("extraction:");
    }

    @Test
    @DisplayName("skips existing file when overwrite=false")
    void skipsExistingWithoutOverwrite(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("brume.yml"), "existing content");
        InitFileWriter writer = new InitFileWriter(tmp);
        InitFileWriter.Outcome outcome = writer.writeFromClasspath(
                BrumeInitCommand.BRUME_YML_RESOURCE, "brume.yml", false);
        assertThat(outcome).isEqualTo(InitFileWriter.Outcome.SKIPPED);
        assertThat(Files.readString(tmp.resolve("brume.yml"))).isEqualTo("existing content");
    }

    @Test
    @DisplayName("overwrites existing file when overwrite=true")
    void overwritesExisting(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("brume.yml"), "existing content");
        InitFileWriter writer = new InitFileWriter(tmp);
        InitFileWriter.Outcome outcome = writer.writeFromClasspath(
                BrumeInitCommand.BRUME_YML_RESOURCE, "brume.yml", true);
        assertThat(outcome).isEqualTo(InitFileWriter.Outcome.OVERWRITTEN);
        assertThat(Files.readString(tmp.resolve("brume.yml"))).contains("extraction:");
    }

    @Test
    @DisplayName("throws IOException on missing classpath resource")
    void throwsOnMissingResource(@TempDir Path tmp) {
        InitFileWriter writer = new InitFileWriter(tmp);
        assertThatThrownBy(() -> writer.writeFromClasspath("/init/no-such-resource", "x.txt", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Classpath resource not found");
    }

    @Test
    @DisplayName("bundled .env template resolves and contains BRUME_HMAC_SECRET")
    void envTemplateResolves(@TempDir Path tmp) throws IOException {
        InitFileWriter writer = new InitFileWriter(tmp);
        InitFileWriter.Outcome outcome = writer.writeFromClasspath(
                BrumeInitCommand.ENV_RESOURCE, ".env", false);
        assertThat(outcome).isEqualTo(InitFileWriter.Outcome.CREATED);
        assertThat(Files.readString(tmp.resolve(".env"))).contains("BRUME_HMAC_SECRET");
    }
}
