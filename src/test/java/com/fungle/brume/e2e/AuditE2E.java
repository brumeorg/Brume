package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code brume audit --anonymity} on the pseudonymized {@code e2e_en} target (#127e, ADR-0036).
 *
 * <p>The audited values come from the fixture : the 5 extracted users all have a NULL
 * {@code gender}, so {@code users:gender} is a single class of 5 ({@code k_min = 5}), and
 * {@code users:gender,birth_date} makes 5 singletons ({@code k_min = 1}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditE2E {

    private final E2eProject project = E2eProject.create("audit");
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void anonymizeIntoTheTarget() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));
        BrumeCli.Result execute = project.run("execute");
        assertThat(execute.exitCode()).as(execute.describe()).isZero();
    }

    @Test
    @DisplayName("informational audit : k_min per table on the console, exit 0 even with singletons")
    void informationalAudit() {
        BrumeCli.Result result = project.run("audit", "--anonymity", "--quasi-id", "users:gender,birth_date");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).contains("Table : users").contains("Quasi-id : gender, birth_date")
                .contains("k_min       : 1").contains("[CRITICAL]");
    }

    @Test
    @DisplayName("--strict --k-min 50 : exit 8, JSON and Markdown reports written with the verdict")
    void strictPolicyViolation() throws IOException {
        BrumeCli.Result result = project.run("audit", "--anonymity", "--quasi-id", "users:gender",
                "--strict", "--k-min", "50", "--output-json", "audit.json", "--output-markdown", "audit.md");

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(8);
        JsonNode report = json.readTree(project.file("audit.json").toFile());
        assertThat(report.path("policyViolated").asBoolean()).isTrue();
        JsonNode users = report.path("report").path("tables").get(0);
        assertThat(users.path("table").asText()).isEqualTo("users");
        assertThat(users.path("distribution").path("kMin").asInt()).isEqualTo(5);
        assertThat(users.path("distribution").path("totalRows").asInt()).isEqualTo(5);
        assertThat(Files.readString(project.file("audit.md")))
                .contains("# Audit anonymité")
                .contains("POLICY VIOLÉE")
                .contains("| `users` | `gender` | 5 |");
    }

    @Test
    @DisplayName("--strict --k-min 5 : the policy holds, exit 0")
    void strictPolicyHolds() {
        BrumeCli.Result result = project.run("audit", "--anonymity", "--quasi-id", "users:gender",
                "--strict", "--k-min", "5");

        assertThat(result.exitCode()).as(result.describe()).isZero();
    }

    @Test
    @DisplayName("--json : the report is the only thing on stdout")
    void jsonReport() throws IOException {
        BrumeCli.Result result = project.run("audit", "--anonymity", "--quasi-id", "users:gender", "--json");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(json.readTree(result.stdout()).path("report").path("schema").asText()).isEqualTo("e2e_en");
    }

    @Test
    @DisplayName("--auto-detect-quasi-id and --sample-rate 0.5 run, exit 0")
    void autoDetectAndSampling() {
        assertThat(project.run("audit", "--anonymity", "--auto-detect-quasi-id").exitCode()).isZero();
        assertThat(project.run("audit", "--anonymity", "--quasi-id", "users:gender", "--sample-rate", "0.5")
                .exitCode()).isZero();
    }

    @Test
    @DisplayName("audit reads the target only : it runs with the source unreachable (preflight AUDIT mode)")
    void sourceIsNotNeeded() {
        project.setEnv("BRUME_SOURCE_PORT", "1");
        try {
            BrumeCli.Result result = project.run("audit", "--anonymity", "--quasi-id", "users:gender");

            assertThat(result.exitCode()).as(result.describe()).isZero();
        } finally {
            project.configureEnv("e2e_en");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "no --anonymity               | --quasi-id=users:gender                  | AUDIT_KIND_REQUIRED",
            "--quasi-id without colon     | --anonymity --quasi-id=users             | AUDIT_QUASI_ID_INVALID",
            "unknown table                | --anonymity --quasi-id=nope:x            | AUDIT_UNKNOWN_TABLE",
            "unknown column               | --anonymity --quasi-id=users:nope        | AUDIT_UNKNOWN_COLUMN",
            "--sample-rate out of (0, 1]  | --anonymity --quasi-id=users:gender --sample-rate=2 | AUDIT_SAMPLE_RATE_INVALID"})
    @DisplayName("#17f — invalid audit arguments : typed error, exit 1")
    void invalidArguments(String name, String args, String code) {
        String[] argv = ("audit " + args.strip()).split(" ");

        BrumeCli.Result result = project.run(argv);

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(1);
        assertThat(result.stderr()).contains("[" + code + "] ").contains("→ Exit code: 1");
    }
}
