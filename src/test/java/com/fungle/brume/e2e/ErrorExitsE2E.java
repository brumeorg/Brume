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
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way a run can fail, seen by the operator (#127e, ADR-0061) : the binary must stop with
 * the exit code of the error family, a {@code [CODE] message → suggestion → Exit code} report
 * on stderr (one JSON object with {@code --json}), no Java stack trace, and nothing written to
 * the target.
 *
 * <p>Before ADR-0061, the failures detected before the subcommand (Spring boot, {@code .env.brume},
 * command line) printed a Spring stack trace, and a missing secret let the run succeed with the
 * literal placeholder as key. The target is a dedicated empty database, checked after each case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorExitsE2E {

    private static final String TARGET_DB = "e2e_errors";
    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s+at [a-zA-Z]");

    private JdbcTemplate target;

    @BeforeAll
    void createEmptyTarget() {
        E2eSettings.seedSource();
        JdbcTemplate admin = E2eSettings.target().jdbc();
        admin.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + TARGET_DB + "'");
        admin.execute("DROP DATABASE IF EXISTS " + TARGET_DB);
        admin.execute("CREATE DATABASE " + TARGET_DB);
        target = E2eSettings.target().withDatabase(TARGET_DB).jdbc();
    }

    /** A project ready to run, its target pointed at the empty database. */
    private E2eProject project(String name) {
        E2eProject project = E2eProject.create("errors-" + name);
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.setEnv("BRUME_TARGET_DB", TARGET_DB);
        try {
            project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return project;
    }

    record Case(String name, Consumer<E2eProject> setup, List<String> args, int exitCode, String code) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Case of(String name, Consumer<E2eProject> setup, String command, int exitCode, String code) {
        return new Case(name, setup, List.of(command.split(" ")), exitCode, code);
    }

    static Stream<Case> cases() {
        return Stream.of(
                // --- settings (.env.brume) : exit 1 ---
                of("no .env.brume", p -> {
                    try {
                        Files.delete(p.file(".env.brume"));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, "execute", 1, "CONFIG_ENV_MISSING"),
                of("BRUME_HMAC_SECRET missing (#17d)", p -> p.removeEnv("BRUME_HMAC_SECRET"), "execute", 1, "CONFIG_ENV_VAR_MISSING"),
                of("BRUME_FPE_KEY missing (#17d)", p -> p.removeEnv("BRUME_FPE_KEY"), "execute", 1, "CONFIG_ENV_VAR_MISSING"),
                of("BRUME_SCHEMA missing (#17d)", p -> p.removeEnv("BRUME_SCHEMA"), "plan", 1, "CONFIG_ENV_VAR_MISSING"),
                of("BRUME_TARGET_HOST missing (#17d)", p -> p.removeEnv("BRUME_TARGET_HOST"), "execute", 1, "CONFIG_ENV_VAR_MISSING"),
                of("duplicated key in .env.brume (#17c)", p -> p.appendEnvLine("BRUME_SCHEMA=e2e_fr"), "plan", 1, "CONFIG_ENV_FILE_INVALID"),
                of("malformed line in .env.brume (#17c)", p -> p.appendEnvLine("THIS IS NOT A SETTING"), "plan", 1, "CONFIG_ENV_FILE_INVALID"),
                of("HMAC secret too short (#17c)", p -> p.setEnv("BRUME_HMAC_SECRET", "short"), "execute", 1, "CONFIG_HMAC_SECRET_TOO_SHORT"),
                of("FPE key of 3 bytes (#17c)", p -> p.setEnv("BRUME_FPE_KEY", "abc"), "execute", 1, "CONFIG_FPE_KEY_INVALID_LENGTH"),
                of("unknown HMAC algorithm (#17g)", p -> p.setEnv("BRUME_HMAC_ALGORITHM", "HmacNope"), "plan", 1, "CONFIG_HMAC_INVALID"),
                of("unknown pipeline mode (#17c)", p -> p.setEnv("BRUME_PIPELINE_MODE", "FOO"), "execute", 1, "CONFIG_PROPERTY_INVALID"),
                of("non-numeric parallelism (#17c)", p -> p.setEnv("BRUME_PARALLELISM", "abc"), "execute", 1, "CONFIG_PROPERTY_INVALID"),
                of("parallelism 0 (#17g)", p -> p.setEnv("BRUME_PARALLELISM", "0"), "execute", 1, "CONFIG_POOL_SIZE_INVALID"),
                of("unknown sslmode (#17g)", p -> p.setEnv("BRUME_SOURCE_SSLMODE", "foo"), "plan", 1, "CONFIG_JDBC_SSLMODE_INVALID"),
                of("DUMP without output path (#17c)", p -> p.setEnv("BRUME_SINK_TYPE", "DUMP"), "execute", 1, "CONFIG_SINK_OUTPUT_PATH_MISSING"),
                of("pg_dump not found", p -> {
                    p.setEnv("BRUME_SINK_TYPE", "DUMP");
                    p.setEnv("BRUME_SINK_OUTPUT_PATH", "./dumps/e2e.sql.gz");
                    p.setEnv("BRUME_PGDUMP_PATH", "/nonexistent/pg_dump");
                }, "execute", 1, "PREFLIGHT_PG_DUMP_NOT_FOUND"),
                // --- brume.yml : exit 1 ---
                of("brume.yml missing", p -> {
                    try {
                        Files.delete(p.file("brume.yml"));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, "execute", 1, "CONFIG_FILE_NOT_FOUND"),
                of("unknown strategy in brume.yml (#17g)", p -> replaceInConfig(p, "strategy: FAKE", "strategy: NOPE"), "execute", 1, "CONFIG_FILE_PARSE_ERROR"),
                of("filter on a missing column (#17e)", p -> replaceInConfig(p, "created_at >= '2025-01-01'",
                        "no_such_column = 1"), "execute", 1, "CONFIG_EXTRACTION_FILTER_INVALID"),
                of("filter on a missing column, plan (#17e)", p -> replaceInConfig(p, "created_at >= '2025-01-01'",
                        "no_such_column = 1"), "plan", 1, "CONFIG_EXTRACTION_FILTER_INVALID"),
                of("max-target-rows exceeded", p -> p.setEnv("brume.max-target-rows", "3"), "execute", 3, "CONFIG_MAX_TARGET_ROWS_EXCEEDED"),
                // --- command line : exit 1 (2 before ADR-0061) ---
                of("unknown subcommand (#17f)", p -> { }, "frobnicate", 1, "CONFIG_CLI_USAGE"),
                of("unknown option (#17f)", p -> { }, "plan --nope", 1, "CONFIG_CLI_USAGE"),
                of("--verbose with --quiet (#17f)", p -> { }, "plan -v -q", 1, "CONFIG_INVALID_FLAGS"),
                // --- connection : exit 2 ---
                of("source unreachable", p -> p.setEnv("BRUME_SOURCE_PORT", "1"), "plan", 2, "CONNECTION_SOURCE_UNREACHABLE"),
                of("source password refused", p -> p.setEnv("BRUME_SOURCE_PASSWORD", "wrong"), "plan", 2, "CONNECTION_SOURCE_UNREACHABLE"),
                of("target unreachable", p -> p.setEnv("BRUME_TARGET_PORT", "1"), "execute", 2, "CONNECTION_TARGET_UNREACHABLE"),
                // --- schema : exit 4 ---
                of("source schema missing", p -> p.setEnv("BRUME_SCHEMA", "no_such_schema"), "plan", 4, "PREFLIGHT_SOURCE_SCHEMA_NOT_FOUND"));
    }

    private static void replaceInConfig(E2eProject p, String from, String to) {
        try {
            String yaml = Files.readString(p.file("brume.yml"));
            assertThat(yaml).as("brume.yml fixture contains %s", from).contains(from);
            p.useConfig(yaml.replaceFirst(Pattern.quote(from), to));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("[CODE] → suggestion → exit code of the family, no stack trace, target untouched")
    void failsCleanly(Case c) {
        E2eProject project = project(c.name().replaceAll("[^A-Za-z0-9]+", "-"));
        c.setup().accept(project);

        BrumeCli.Result result = project.run(c.args().toArray(String[]::new));

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(c.exitCode());
        assertThat(result.stderr()).as(result.describe())
                .contains("[" + c.code() + "] ")
                .contains("→ Exit code: " + c.exitCode());
        assertThat(result.stdout() + result.stderr()).as("no Java stack trace\n" + result.describe())
                .doesNotContainPattern(STACK_FRAME)
                .doesNotContain("APPLICATION FAILED TO START")
                .doesNotContain("Application run failed");
        assertThat(userTables()).as("the target must stay empty").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("--json : the same error as one JSON object on stderr")
    void failsCleanlyInJson(Case c) throws IOException {
        E2eProject project = project("json-" + c.name().replaceAll("[^A-Za-z0-9]+", "-"));
        c.setup().accept(project);
        String[] args = Stream.concat(c.args().stream(), Stream.of("--json")).toArray(String[]::new);

        BrumeCli.Result result = project.run(args);

        assertThat(result.exitCode()).as(result.describe()).isEqualTo(c.exitCode());
        String errorLine = result.stderr().lines().filter(l -> l.startsWith("{\"error\":")).findFirst()
                .orElseThrow(() -> new AssertionError("no JSON error on stderr\n" + result.describe()));
        JsonNode error = new ObjectMapper().readTree(errorLine);
        assertThat(error.path("error").path("code").asText()).isEqualTo(c.code());
        assertThat(error.path("error").path("suggestion").asText()).isNotBlank();
        assertThat(error.path("exitCode").asInt()).isEqualTo(c.exitCode());
    }

    @Test
    @DisplayName("#17d — with BRUME_HMAC_SECRET missing, the run stops instead of using '${BRUME_HMAC_SECRET}' as key")
    void missingSecretNeverReachesTheRun() {
        E2eProject project = project("no-secret-message");
        project.removeEnv("BRUME_HMAC_SECRET");
        project.removeEnv("BRUME_FPE_KEY");

        BrumeCli.Result result = project.run("execute");

        assertThat(result.stderr()).as(result.describe())
                .contains("Required settings missing : BRUME_HMAC_SECRET, BRUME_FPE_KEY.");
        assertThat(project.file("reports/brume-execution-report.json")).doesNotExist();
    }

    @Test
    @DisplayName("#17c — a duplicated secret in .env.brume is reported by line, its values never printed")
    void duplicatedSecretIsNotPrinted() {
        E2eProject project = project("duplicated-secret");
        project.appendEnvLine("BRUME_HMAC_SECRET=leaky-second-secret-0002");

        BrumeCli.Result result = project.run("plan");

        assertThat(result.stderr()).as(result.describe()).contains("declares BRUME_HMAC_SECRET more than once");
        assertThat(result.stdout() + result.stderr()).doesNotContain("leaky-second-secret").doesNotContain("e2e-hmac-secret");
    }

    @Test
    @DisplayName("#17f — a mistyped subcommand gets a suggestion and a pointer to --help, not the whole usage")
    void typoGetsASuggestion() {
        BrumeCli.Result result = project("typo").run("excute");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("Did you mean 'execute'?").contains("--help");
        assertThat(result.stdout() + result.stderr()).doesNotContain("Usage:");
    }

    private long userTables() {
        Long n = target.queryForObject("SELECT count(*) FROM pg_catalog.pg_tables "
                + "WHERE schemaname NOT IN ('pg_catalog', 'information_schema')", Long.class);
        return n == null ? 0 : n;
    }
}
