package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #26c — names PostgreSQL only takes quoted, on the binary under test : a schema with upper
 * case, an accent and a space ({@code "E2E Édition"}, read by {@code pg_dump -n}), written
 * through JDBC and through the DUMP sink, and the scan of {@code e2e_edge} writing such names
 * into {@code brume.yml}. The table {@code "fiche client"} of {@code e2e_edge} is covered by
 * {@link EdgeCasesE2E}.
 *
 * <p>#26d — the binary runs under {@code LC_ALL=C} : its platform encoding is then ASCII on
 * Linux, and a non-ASCII schema name used to reach {@code pg_dump} as {@code "E2E ?dition"}.
 */
class IdentifiersE2E {

    private static final String SCHEMA = "E2E Édition";
    private static final String CONFIG = """
            extraction:
              fk_depth: 1
              tables:
                - table: Client
                  filter:
            anonymization:
              tables:
                - table: Client
                  columns:
                    - name: e-mail
                      strategy: FAKE
                      type: EMAIL
            """;
    private static final String CLIENTS = "SELECT id, \"e-mail\" AS email, ville FROM \"E2E Édition\".\"Client\" ORDER BY id";

    private final JdbcTemplate target = E2eSettings.target().jdbc();

    @Test
    @DisplayName("#26c / #26d — schema \"E2E Édition\" under LC_ALL=C : plan, then execute through JDBC "
            + "(pg_dump -n gets the quoted name, byte for byte)")
    void schemaWithAccentAndSpaceGoesThroughJdbc() throws IOException {
        E2eProject project = project("identifiers-jdbc");

        BrumeCli.Result plan = project.run("plan");
        assertThat(plan.exitCode()).as(plan.describe()).isZero();
        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertClientsPseudonymized();
    }

    @Test
    @DisplayName("#26c / #26d — schema \"E2E Édition\" under LC_ALL=C : the DUMP sink file restores with psql without error")
    void schemaWithAccentAndSpaceGoesThroughTheDumpSink() throws IOException {
        E2eProject project = project("identifiers-dump");
        project.setEnv("BRUME_PIPELINE_MODE", "STREAMING");
        project.setEnv("BRUME_SINK_TYPE", "DUMP");
        project.setEnv("BRUME_SINK_OUTPUT_PATH", "./dumps/edition.sql");
        project.setEnv("BRUME_SINK_COMPRESSION", "NONE");

        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        target.execute("DROP SCHEMA IF EXISTS \"E2E Édition\" CASCADE");
        assertThat(Psql.run(E2eSettings.target(), project.file("dumps/edition.sql"))).as("psql errors while restoring").isEmpty();
        assertClientsPseudonymized();
    }

    @Test
    @DisplayName("#26c — scan writes \"fiche client\", prénom, \"nom de famille\", téléphone into brume.yml, "
            + "and they read back exactly with the right rule")
    void scanWritesQuotedOnlyNames() throws IOException {
        E2eSettings.seedSource();
        E2eProject project = E2eProject.create("identifiers-scan");
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_edge");

        BrumeCli.Result scan = project.run("scan", "--force");

        assertThat(scan.exitCode()).as(scan.describe()).isZero();
        JsonNode yaml = new YAMLMapper().readTree(project.file("brume.yml").toFile());
        Map<String, String> rules = new LinkedHashMap<>();
        for (JsonNode table : yaml.path("anonymization").path("tables")) {
            if (!table.path("table").asText().equals("fiche client")) continue;
            for (JsonNode column : table.path("columns")) {
                rules.put(column.path("name").asText(),
                        (column.path("strategy").asText() + " " + column.path("type").asText("")).trim());
            }
        }
        assertThat(rules).containsEntry("prénom", "FAKE FIRST_NAME")
                .containsEntry("nom de famille", "FAKE LAST_NAME")
                .containsEntry("téléphone", "MASK PHONE")
                .doesNotContainKey("compte référent");
    }

    // ------------------------------------------------------------------------------------

    private E2eProject project(String name) {
        E2eSettings.seedSource();
        target.execute("DROP SCHEMA IF EXISTS \"E2E Édition\" CASCADE");
        E2eProject project = E2eProject.create(name);
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv(SCHEMA);
        project.useConfig(CONFIG);
        project.processEnv("LANG", "C");
        project.processEnv("LC_ALL", "C");
        return project;
    }

    private void assertClientsPseudonymized() {
        List<Map<String, Object>> rows = target.queryForList(CLIENTS);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("ville")).containsExactly("Saint-Étienne", "Besançon", null);
        assertThat(rows).extracting(r -> (String) r.get("email"))
                .doesNotContain("claire@exemple.fr", "hervé@exemple.fr", "zoé@exemple.fr")
                .doesNotHaveDuplicates().allMatch(email -> email.contains("@"));
    }
}
