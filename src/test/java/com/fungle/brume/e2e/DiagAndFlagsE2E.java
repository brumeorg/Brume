package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --version}, {@code --help}, {@code diag} and the global output flags
 * {@code -q} / {@code -v} / {@code --json} on the binary under test (#127e).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiagAndFlagsE2E {

    private static final List<String> SUBCOMMANDS = List.of("audit", "diag", "dry-run", "execute", "init", "plan", "scan");

    private final E2eProject project = E2eProject.create("diag-and-flags");
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));
    }

    @Test
    @DisplayName("--version prints 'brume <version>' and nothing else")
    void version() {
        BrumeCli.Result result = project.run("--version");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout().strip()).matches("brume \\d+\\.\\d+.*");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    @DisplayName("--help lists every subcommand and the global flags, without booting Spring")
    void help() {
        BrumeCli.Result result = project.run("--help");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).startsWith("Usage: brume").contains("--json", "--quiet", "--verbose");
        SUBCOMMANDS.forEach(sub -> assertThat(result.stdout()).contains("  " + sub + " "));
        assertThat(result.stdout()).doesNotContain(" INFO ");
    }

    @Test
    @DisplayName("diag : every critical bean wired, exit 0")
    void diagText() {
        BrumeCli.Result result = project.run("diag");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).contains("diagnostic").contains("Beans wired").contains("[✓] ReplicationAgent");
        assertThat(result.stdout()).doesNotContain("[✗]");
    }

    @Test
    @DisplayName("#75a — diag --json : wiring and config reported, and no connection opened (pools at 0)")
    void diagJsonOpensNoConnection() throws IOException {
        BrumeCli.Result result = project.run("diag", "--json");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        JsonNode diag = json.readTree(result.stdout());
        assertThat(diag.path("app").asText()).isEqualTo("Brume");
        assertThat(diag.path("beans")).isNotEmpty().allSatisfy(b -> assertThat(b.path("ok").asBoolean())
                .as(b.path("name").asText()).isTrue());
        assertThat(diag.path("config").path("replication.schema").asText()).isEqualTo("e2e_en");
        assertThat(diag.path("sourcePool").path("total").asInt()).as("source connections opened by diag").isZero();
    }

    @Test
    @DisplayName("diag with the source unreachable : still healthy, it never contacts the databases")
    void diagWithoutDatabase() {
        E2eProject offline = E2eProject.create("diag-offline");
        assertThat(offline.run("init", "jdbc").exitCode()).isZero();
        offline.configureEnv("e2e_en");
        offline.setEnv("BRUME_SOURCE_PORT", "1");
        offline.setEnv("BRUME_TARGET_PORT", "1");

        BrumeCli.Result result = offline.run("diag");

        assertThat(result.exitCode()).as(result.describe()).isZero();
    }

    @Test
    @DisplayName("#22b / #24a — execute -q : only the run result and errors, no 'JdbcSink configured', no 'SIGTERM received'")
    void quietPrintsOnlyTheResult() {
        BrumeCli.Result result = project.run("execute", "-q");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout().lines().filter(l -> !l.contains("[brume.output]")))
                .as("non-result lines under -q").isEmpty();
        assertThat(result.stdout() + result.stderr()).doesNotContain("SIGTERM received").doesNotContain("Termination signal received");
    }

    @Test
    @DisplayName("#24a — a normal run never reports a SIGTERM")
    void normalRunReportsNoSignal() {
        BrumeCli.Result result = project.run("plan");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout() + result.stderr()).doesNotContain("SIGTERM received").doesNotContain("Termination signal received");
    }

    @Test
    @DisplayName("-v : DEBUG logs of Brume are shown")
    void verboseShowsDebug() {
        BrumeCli.Result result = project.run("plan", "-v");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).contains(" DEBUG [c.f.");
    }

    @Test
    @DisplayName("execute --json : one JSON result on stdout, every stderr line is a JSON log")
    void jsonSeparatesResultAndLogs() throws IOException {
        BrumeCli.Result result = project.run("execute", "--json");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        JsonNode wrapper = json.readTree(result.stdout());
        assertThat(wrapper.path("plan").path("sourceSchema").asText()).isEqualTo("e2e_en");
        assertThat(result.stderr().lines().filter(l -> !l.isBlank())).allSatisfy(line ->
                assertThat(isJsonLog(line)).as(line).isTrue());
    }

    private boolean isJsonLog(String line) {
        try {
            return json.readTree(line).has("level");
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @DisplayName("no subcommand : the usage, exit 0")
    void noSubcommandPrintsUsage() {
        BrumeCli.Result result = project.run();

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).startsWith("Usage: brume");
    }
}
