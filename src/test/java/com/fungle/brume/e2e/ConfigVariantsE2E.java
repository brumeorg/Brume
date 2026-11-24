package com.fungle.brume.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings that change the pseudonymized values (#127e) : the Datafaker locale and the HMAC
 * algorithm (resolved through the JCA providers, which a native image must embed), plus the
 * warnings for a setting Brume ignores (#17g), such as the strategy of a JSONB column (ADR-0065).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigVariantsE2E {

    private final E2eProject project = E2eProject.create("config-variants");
    private final JdbcTemplate target = E2eSettings.target().jdbc();
    private String config;

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        config = Files.readString(E2eSettings.RESOURCES.resolve("brume.yml"));
    }

    private List<String> executeAndRead(String column) {
        project.useConfig(config);
        BrumeCli.Result result = project.run("execute");
        assertThat(result.exitCode()).as(result.describe()).isZero();
        return target.queryForList("SELECT " + column + " FROM e2e_en.users ORDER BY id", String.class);
    }

    @Test
    @DisplayName("BRUME_LOCALE=fr : FAKE first names differ from the English run, and are reproducible")
    void localeChangesFakeValues() {
        project.configureEnv("e2e_en");
        project.removeEnv("BRUME_HMAC_ALGORITHM");
        List<String> english = executeAndRead("first_name");
        project.setEnv("BRUME_LOCALE", "fr");

        List<String> french = executeAndRead("first_name");
        List<String> frenchAgain = executeAndRead("first_name");

        assertThat(french).doesNotContainNull().isNotEqualTo(english).isEqualTo(frenchAgain);
    }

    @Test
    @DisplayName("BRUME_HMAC_ALGORITHM=HmacSHA512 : HASH values differ from HmacSHA256, and are reproducible")
    void hmacAlgorithmChangesHashes() {
        project.configureEnv("e2e_en");
        project.removeEnv("BRUME_HMAC_ALGORITHM");
        List<String> sha256 = executeAndRead("password_hash");
        project.setEnv("BRUME_HMAC_ALGORITHM", "HmacSHA512");

        List<String> sha512 = executeAndRead("password_hash");
        List<String> sha512Again = executeAndRead("password_hash");

        assertThat(sha512).doesNotContainNull().isNotEqualTo(sha256).isEqualTo(sha512Again);
    }

    @Test
    @DisplayName("#17g — an unknown brume.yml key and an unknown locale are reported as warnings, the run goes on")
    void ignoredSettingsAreReported() {
        project.configureEnv("e2e_en");
        project.removeEnv("BRUME_HMAC_ALGORITHM");
        project.setEnv("BRUME_LOCALE", "xx");
        project.useConfig(config.replaceFirst("(?m)^  fk_depth:", "  fk_dpeth:") + "unknown_section: 1\n");

        BrumeCli.Result result = project.run("plan");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout())
                .contains("[CONFIG_UNKNOWN_KEY]").contains("'fk_dpeth' under 'extraction'")
                .contains("'unknown_section'")
                .contains("[CONFIG_LOCALE_UNKNOWN]").contains("'xx'");
    }

    @Test
    @DisplayName("#21e, ADR-0065 — a JSONB column without strategy is anonymized path by path ; "
            + "a declared one is reported as ignored, the run goes on")
    void jsonbColumnStrategyIsOptional() {
        project.configureEnv("e2e_en");
        project.removeEnv("BRUME_HMAC_ALGORITHM");
        // The reference config : users.address_json without strategy, audit_logs.payload with 'strategy: FAKE'.
        project.useConfig(config);

        BrumeCli.Result result = project.run("execute");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout())
                .contains("[CONFIG_JSONB_STRATEGY_IGNORED] Column 'audit_logs.payload'", "'strategy: FAKE' is ignored")
                .doesNotContain("Column 'users.address_json' has type JSONB");
        String street = "SELECT address_json->>'street' FROM e2e_en.users ORDER BY id";
        assertThat(target.queryForList(street, String.class)).isNotEmpty().doesNotContainNull()
                .doesNotContainAnyElementsOf(E2eSettings.source().jdbc().queryForList(street, String.class));
    }
}
