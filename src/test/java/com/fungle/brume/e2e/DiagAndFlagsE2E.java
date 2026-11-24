package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /**
     * {@code -h} on audit, {@code --help} elsewhere : {@code BrumeApplication.main} intercepts both before
     * Spring. {@code audit --help} stays covered by {@link #usageErrorPointsToAWorkingHelp()}.
     */
    static Stream<Arguments> subcommands() {
        return SUBCOMMANDS.stream().map(sub -> Arguments.of(sub, sub.equals("audit") ? "-h" : "--help"));
    }

    @ParameterizedTest(name = "brume {0} {1}")
    @MethodSource("subcommands")
    @DisplayName("#17h, GitHub #4 — every subcommand answers --help / -h with its own usage, exit 0, without booting Spring")
    void subcommandHelp(String subcommand, String flag) {
        BrumeCli.Result result = project.run(subcommand, flag);

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).startsWith("Usage: brume " + subcommand).doesNotContain(" INFO ");
    }

    @Test
    @DisplayName("#17h, GitHub #4 — the --help a usage error points to works, and lists the real audit options")
    void usageErrorPointsToAWorkingHelp() {
        BrumeCli.Result error = project.run("audit", "--report-format", "markdown");
        Matcher suggested = Pattern.compile("Run 'brume ([^']+)'").matcher(error.stderr());
        assertThat(suggested.find()).as("usage error suggests a command\n" + error.describe()).isTrue();

        BrumeCli.Result help = project.run(suggested.group(1).split(" "));

        assertThat(help.exitCode()).as(help.describe()).isZero();
        assertThat(help.stdout()).startsWith("Usage: brume audit").contains("--output-markdown", "--output-json");
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
    @DisplayName("#127h, GitHub #1 — diag prints the version of --version, in text and JSON "
            + "(the native binary printed '0.0.1-SNAPSHOT')")
    void diagPrintsTheBinaryVersion() throws IOException {
        String version = project.run("--version").stdout().strip().replace("brume ", "");

        BrumeCli.Result text = project.run("diag");
        BrumeCli.Result jsonResult = project.run("diag", "--json");

        assertThat(text.stdout()).as(text.describe()).contains("Brume " + version + " — diagnostic");
        assertThat(json.readTree(jsonResult.stdout()).path("version").asText()).isEqualTo(version);
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
