package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run stopped by a signal (#127f, ADR-0063) : the JDBC query in flight is aborted, the main
 * thread reports {@code [RUN_CANCELLED]} and closes the sinks before the JVM exits with
 * 128 + signal (143 for SIGTERM, 130 for SIGINT).
 *
 * <p>Before #24c / #24d the native binary died on the signal without running any shutdown
 * hook (no {@code --install-exit-handlers}), and on the JVM the hook returned before the main
 * thread could report : no message either way. Linux only (a real signal is needed).
 */
@EnabledOnOs(OS.LINUX)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignalE2E {

    private final E2eProject project = E2eProject.create("signal");

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.setEnv("brume.plan.mode", "ESTIMATE");
        project.useConfig(RunBlocker.blockOrderItems(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml"))));
    }

    private BrumeCli.Result interrupt(String signal, String... args) {
        try (RunBlocker blocker = RunBlocker.hold()) {
            BrumeCli.Running run = project.start(args);
            blocker.awaitBlocked(Duration.ofSeconds(120), run::stdoutSoFar);
            run.signal(signal);
            return project.log(run.await(), args);
        }
    }

    @Test
    @DisplayName("#24c / #24d — SIGTERM (docker stop) : [RUN_CANCELLED] on stderr, exit 143")
    void sigterm() {
        BrumeCli.Result result = interrupt("TERM", "execute");

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(143);
        assertThat(result.stderr()).contains("[RUN_CANCELLED]").contains("Exit code: 143 (SIGTERM) / 130 (SIGINT)");
        assertThat(result.stdout()).contains("Termination signal received");
    }

    @Test
    @DisplayName("SIGINT (Ctrl-C) : [RUN_CANCELLED], exit 130")
    void sigint() {
        BrumeCli.Result result = interrupt("INT", "execute");

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(130);
        assertThat(result.stderr()).contains("[RUN_CANCELLED]");
    }

    @Test
    @DisplayName("SIGTERM with --json : one RUN_CANCELLED JSON error on stderr")
    void sigtermJson() throws IOException {
        BrumeCli.Result result = interrupt("TERM", "execute", "--json");

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(143);
        String line = result.stderr().lines().filter(l -> l.startsWith("{\"error\":")).findFirst()
                .orElseThrow(() -> new AssertionError("no JSON error\n" + result.describe()));
        JsonNode error = new ObjectMapper().readTree(line);
        assertThat(error.path("error").path("code").asText()).isEqualTo("RUN_CANCELLED");
        assertThat(error.path("exitCode").asInt()).isEqualTo(143);
    }

    @Test
    @DisplayName("#24d — SIGTERM during a gzip DUMP : the file is closed, its gzip trailer written (readable to the end)")
    void sigtermClosesTheDump() throws IOException {
        project.setEnv("BRUME_SINK_TYPE", "DUMP");
        project.setEnv("BRUME_SINK_OUTPUT_PATH", "./dumps/interrupted.sql.gz");
        try {
            BrumeCli.Result result = interrupt("TERM", "execute");

            assertThat(result.exitCode()).as(result.describe()).isEqualTo(143);
            assertThat(result.stderr()).contains("[RUN_CANCELLED]");
            try (InputStream in = new GZIPInputStream(Files.newInputStream(project.file("dumps/interrupted.sql.gz")))) {
                String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                // Blocked at the first FK level : only the DDL is written yet ; what matters is
                // that the gzip stream is complete (readAllBytes would throw on a missing trailer).
                assertThat(sql).contains("CREATE TABLE");
            }
        } finally {
            project.setEnv("BRUME_SINK_TYPE", "JDBC");
            project.removeEnv("BRUME_SINK_OUTPUT_PATH");
        }
    }
}
