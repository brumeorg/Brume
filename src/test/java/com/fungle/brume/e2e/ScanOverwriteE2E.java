package com.fungle.brume.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code brume scan} on a project that already has a {@code brume.yml} (#127e) : without a
 * terminal there is nobody to answer the overwrite prompt, so the file is kept unless
 * {@code --force} is passed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanOverwriteE2E {

    private static final String HAND_WRITTEN = "# hand-written, must survive a scan without --force\n";

    private final E2eProject project = E2eProject.create("scan-overwrite");

    @BeforeAll
    void createProject() {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
    }

    @Test
    @DisplayName("scan without --force, no terminal : brume.yml is kept byte for byte, the warning names --force")
    void keepsExistingFile() throws IOException {
        project.useConfig(HAND_WRITTEN);

        BrumeCli.Result result = project.run("scan");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).contains("keeping existing brume.yml").contains("--force");
        assertThat(Files.readString(project.file("brume.yml"))).isEqualTo(HAND_WRITTEN);
    }

    @Test
    @DisplayName("scan --force : brume.yml is replaced by the scan output")
    void forceOverwrites() throws IOException {
        project.useConfig(HAND_WRITTEN);

        BrumeCli.Result result = project.run("scan", "--force");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(Files.readString(project.file("brume.yml")))
                .doesNotContain("hand-written")
                .contains("- table: users");
    }
}
