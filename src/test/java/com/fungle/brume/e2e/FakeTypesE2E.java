package com.fungle.brume.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #127i, GitHub #3 — every semantic type FAKE can generate, run by the native binary : the golden config
 * masks {@code phone} and {@code ip_address}, so FAKE + PHONE — whose Datafaker generator
 * reaches libphonenumber by reflection — had never run in a native image before it failed
 * there with NoSuchMethodException. This variant turns both to FAKE, plus a JSON path, which
 * covers the remaining types (EMAIL, names, IBAN, ADDRESS and DATE are in the golden config).
 * Plus #79j : one value in two JSON leaves of different types is faked per type.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FakeTypesE2E {

    private final E2eProject project = E2eProject.create("fake-types");
    private final JdbcTemplate source = E2eSettings.source().jdbc();
    private final JdbcTemplate target = E2eSettings.target().jdbc();
    private String config;

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        String golden = Files.readString(E2eSettings.RESOURCES.resolve("brume.yml"));
        config = replaceEvery(golden,
                "- name: phone\n          strategy: MASK",
                "- name: phone\n          strategy: FAKE");
        // users.ip_address and audit_logs.ip_address
        config = replaceEvery(config,
                "- name: ip_address\n          strategy: MASK",
                "- name: ip_address\n          strategy: FAKE");
        config = replaceEvery(config,
                "- path: \"$.city\"\n              type: ADDRESS\n              strategy: MASK",
                "- path: \"$.city\"\n              type: PHONE\n              strategy: FAKE");
    }

    @ParameterizedTest(name = "BRUME_LOCALE={0}")
    @ValueSource(strings = {"en", "fr"})
    @DisplayName("#127i, GitHub #3 — FAKE + PHONE (column and json_paths) and FAKE + IP_ADDRESS run in the native binary")
    void everyFakeTypeRuns(String locale) {
        project.configureEnv("e2e_en");
        project.setEnv("BRUME_LOCALE", locale);
        project.useConfig(config);

        BrumeCli.Result result = project.run("execute");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertFaked("phone", "\\+?[\\d ().-]{6,20}");
        assertFaked("ip_address", "\\d{1,3}(\\.\\d{1,3}){3}");
        assertFaked("address_json ->> 'city'", "\\+?[\\d ().-]{6,20}");
    }

    @Test
    @DisplayName("#79j — one value in two FAKE leaves of different types in a JSONB column : "
            + "each leaf gets the fake of its own type")
    void sameValueInTwoJsonLeavesIsFakedPerType() {
        // No collision in the golden datasets : a dedicated schema, left out of every other e2e.
        source.execute("""
                DROP SCHEMA IF EXISTS e2e_jsonb CASCADE;
                CREATE SCHEMA e2e_jsonb;
                CREATE TABLE e2e_jsonb.contacts (id integer PRIMARY KEY, nom text, data jsonb);
                INSERT INTO e2e_jsonb.contacts VALUES (1, 'Martin', '{"prenom": "Martin", "nom": "Martin"}');
                """);
        project.configureEnv("e2e_jsonb");
        project.useConfig("""
                extraction:
                  fk_depth: 1
                  tables:
                    - table: contacts
                anonymization:
                  tables:
                    - table: contacts
                      columns:
                        - name: nom
                          strategy: FAKE
                          type: LAST_NAME
                        - name: data
                          type: JSONB
                          json_paths:
                            - path: "$.prenom"
                              type: FIRST_NAME
                              strategy: FAKE
                            - path: "$.nom"
                              type: LAST_NAME
                              strategy: FAKE
                """);

        BrumeCli.Result result = project.run("execute");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        Map<String, Object> row = target.queryForMap(
                "SELECT nom, data ->> 'prenom' AS prenom, data ->> 'nom' AS json_nom FROM e2e_jsonb.contacts");
        assertThat(row.get("json_nom")).as("$.nom : the LAST_NAME fake of 'Martin', as in the nom column")
                .isEqualTo(row.get("nom"))
                .isNotEqualTo(row.get("prenom"))
                .isNotEqualTo("Martin");
    }

    /**
     * Every non-null target value is a fake : shaped like {@code pattern}, never a source value.
     * Rows cannot be paired (ids are FPE_ID-pseudonymized, extraction keeps a subset of users).
     */
    private void assertFaked(String column, String pattern) {
        String query = "SELECT " + column + " FROM e2e_en.users";
        List<String> original = source.queryForList(query, String.class);
        List<String> faked = target.queryForList(query, String.class).stream()
                .filter(Objects::nonNull).toList();

        assertThat(faked).as(column).isNotEmpty()
                .allMatch(value -> value.matches(pattern), "matches " + pattern)
                .doesNotContainAnyElementsOf(original);
    }

    private static String replaceEvery(String yaml, String from, String to) {
        assertThat(yaml).as("e2e brume.yml rule to vary").contains(from);
        return yaml.replace(from, to);
    }
}
