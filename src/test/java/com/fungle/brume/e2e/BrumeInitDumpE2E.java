package com.fungle.brume.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code brume init dump} on the binary under test (ADR-0049) : the JDBC flavour is
 * covered by the first step of {@link BrumeJourneyE2E}.
 */
class BrumeInitDumpE2E {

    @Test
    @DisplayName("init dump — creates .env.brume (DUMP template) and brume.yml (example config)")
    void initDumpCreatesProjectFiles() throws IOException {
        Path workDir = Path.of("target", "e2e-work", "init-dump").toAbsolutePath();
        FileSystemUtils.deleteRecursively(workDir);
        Files.createDirectories(workDir);

        BrumeCli.Result result = new BrumeCli(E2eSettings.binary(), workDir).run("init", "dump");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(workDir.resolve(".env.brume")).as(".env.brume created")
                .hasSameTextualContentAs(Path.of(".env.dump.template"));
        assertThat(Files.readString(workDir.resolve(".env.brume")))
                .contains("BRUME_SINK_TYPE=DUMP")
                .contains("# BRUME_TARGET_HOST=");
        assertThat(workDir.resolve("brume.yml")).as("brume.yml created")
                .hasSameTextualContentAs(Path.of("brume.example.yml"));
    }
}
