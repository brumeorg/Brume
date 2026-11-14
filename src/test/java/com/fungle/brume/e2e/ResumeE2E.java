package com.fungle.brume.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash-resume on the binary under test (#127f, ADR-0037, ADR-0062 lot B) : a run stopped by
 * SIGTERM while writing the FK parents ({@code users}, second FK level), then a second
 * {@code execute}, must leave the target equal to the golden dataset — the table completed
 * before the signal ({@code audit_logs}) kept, the rest written, the checkpoint gone.
 *
 * <p>A table is checkpointed when no FK level can add rows to it any more, i.e. at the end of
 * a level : the run is held at level 1 by a trigger on the target {@code users} (see
 * {@link RunBlocker#blockWritesOf}), once level 0 has completed {@code audit_logs}.
 *
 * <p>Until #25i / #25j the second run dropped the target schema (DDL replication) and then
 * skipped the "completed" tables : they ended empty. Linux only : a real signal is needed
 * (on Windows, {@code Process.destroy} is TerminateProcess and runs no shutdown hook).
 */
@EnabledOnOs(OS.LINUX)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResumeE2E {

    private static final List<String> TABLES = List.of("users", "products", "orders", "order_items", "audit_logs");

    private final E2eProject project = E2eProject.create("resume");
    private final JdbcTemplate target = E2eSettings.target().jdbc();

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        RunBlocker.blockWritesOf("e2e_en", "users");
        target.execute("DROP SCHEMA IF EXISTS e2e_en CASCADE");
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.setEnv("BRUME_PIPELINE_MODE", "STREAMING");
        project.setEnv("brume.checkpoint.enabled", "true");
        project.setEnv("brume.checkpoint.path", "./checkpoint.json");
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));
    }

    @Test
    @DisplayName("#25j — SIGTERM while writing users, then execute again : target = golden dataset, checkpoint removed")
    void resumeAfterSigtermGivesTheGoldenDataset() throws IOException {
        BrumeCli.Result interrupted;
        try (RunBlocker blocker = RunBlocker.hold(E2eSettings.target())) {
            BrumeCli.Running run = project.start("execute");
            blocker.awaitBlocked(Duration.ofSeconds(120), run::stdoutSoFar);
            assertThat(Files.readString(project.file("checkpoint.json")))
                    .as("table completed before the signal").contains("\"audit_logs\"").doesNotContain("\"order_items\"");

            run.signal("TERM");
            interrupted = project.log(run.await(), "execute");
        }

        assertThat(interrupted.exitCode()).as(interrupted.describe()).isEqualTo(143);
        assertThat(interrupted.stderr()).contains("[RUN_CANCELLED]");
        assertThat(project.file("checkpoint.json")).as("kept for the resume").exists();

        BrumeCli.Result resumed = project.run("execute");

        assertThat(resumed.exitCode()).as(resumed.describe()).isZero();
        assertThat(resumed.stdout()).contains("Resuming an interrupted run");
        assertThat(goldenDiffs()).isEmpty();
        assertThat(project.file("checkpoint.json")).as("#25i — removed once the run completes").doesNotExist();
    }

    private Map<String, List<String>> goldenDiffs() {
        Map<String, List<String>> diffs = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<String> tableDiffs = GoldenDataset.compareWith(
                    E2eSettings.GOLDEN_DIR.resolve("e2e_en").resolve(table + ".tsv"),
                    GoldenDataset.snapshot(target, "e2e_en", table, "id"));
            if (!tableDiffs.isEmpty()) diffs.put(table, tableDiffs);
        }
        return diffs;
    }
}
