package com.fungle.brume.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command-line flags of the binary under test (ADR-0051).
 */
class CliFlagsE2E {

    /**
     * KNOWN GAP #22a — {@code BrumeApplication} applies these flags before Spring boots, but
     * picocli does not declare them on the subcommands : the run stops on "Unknown option",
     * exit 2. Asserted as-is so the fix turns this test red : then expect the flag to work.
     */
    @ParameterizedTest(name = "brume {0} {1}")
    @CsvSource({"plan, --strict-config", "plan, --no-quasi-id-warn", "execute, --strip-timestamps",
            "execute, --resume=./checkpoint.json"})
    @DisplayName("KNOWN GAP #22a — pre-boot flags are rejected by picocli")
    void preBootFlagsAreRejected(String command, String flag) {
        E2eProject project = E2eProject.create("cli-flags");
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");

        BrumeCli.Result result = project.run(command, flag);

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(2);
        assertThat(result.stderr()).contains("Unknown option");
    }
}
