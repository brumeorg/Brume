package com.fungle.brume.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run timeout on the binary under test (#127f, ADR-0033) : a run stuck on a lock is cut by
 * {@code brume.timeouts.total-run-seconds} — exit 7, {@code RUN_TIMEOUT_TOTAL}, no stack trace
 * (before #24d the aborted connection logged one).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeoutE2E {

    private final E2eProject project = E2eProject.create("timeout");

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.useConfig(RunBlocker.blockOrderItems(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml"))));
    }

    @BeforeEach
    void resetEnv() {
        project.configureEnv("e2e_en");
        project.removeEnv("brume.timeouts.statement-seconds");
        project.removeEnv("brume.timeouts.total-run-seconds");
    }

    /**
     * {@code total-run-seconds} cuts the run wherever it waits : in the EXACT plan (its FK walk
     * reads the blocked rows) or in the extraction (ESTIMATE plan). The statement timeout is not
     * tested here : it only guards introspection and reltuples reads since the EXACT plan became
     * a streaming FK walk (ADR-0050) — no realistic way to block them (#23j).
     */
    @ParameterizedTest(name = "{2} plan → {3}")
    @CsvSource({
            "brume.timeouts.total-run-seconds, 4, EXACT,    RUN_TIMEOUT_TOTAL",
            "brume.timeouts.total-run-seconds, 4, ESTIMATE, RUN_TIMEOUT_TOTAL"})
    @DisplayName("a run blocked on a lock stops with exit 7 and RUN_TIMEOUT_TOTAL, without stack trace")
    void blockedRunTimesOut(String setting, String seconds, String planMode, String code) {
        project.setEnv(setting, seconds);
        project.setEnv("brume.plan.mode", planMode);

        try (RunBlocker ignored = RunBlocker.hold()) {
            BrumeCli.Result result = project.run("execute");

            assertThat(result.exitCode()).as(result.describe()).isEqualTo(7);
            assertThat(result.stderr()).contains("[" + code + "] ").contains("→ Exit code: 7");
            assertThat(result.stdout() + result.stderr()).doesNotContainPattern("(?m)^\\s+at [a-zA-Z]");
        }
    }
}
