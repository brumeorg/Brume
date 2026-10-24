package com.fungle.brume.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configurations that can only fail are refused by {@code brume plan}, on the binary under
 * test, before anything reaches the target.
 */
class ConfigRejectionE2E {

    @Test
    @DisplayName("#21d — NULLIFY on the NOT NULL FK orders.user_id is refused by plan "
            + "(used to fail every write batch of execute)")
    void nullifyOnNotNullColumnIsRefusedByPlan() throws IOException {
        E2eSettings.seedSource();
        JdbcTemplate target = E2eSettings.target().jdbc();
        target.execute("DROP SCHEMA IF EXISTS e2e_en CASCADE");
        E2eProject project = E2eProject.create("config-rejection");
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        String yaml = Files.readString(E2eSettings.RESOURCES.resolve("brume.yml"));
        String notes = """
                        - name: notes
                          strategy: NULLIFY
                """;
        assertThat(yaml).contains(notes);
        project.useConfig(yaml.replace(notes, notes + """
                        - name: user_id
                          strategy: NULLIFY
                """));

        BrumeCli.Result plan = project.run("plan");

        assertThat(plan.exitCode()).as(plan.describe()).isEqualTo(1);
        assertThat(plan.stderr()).contains("[CONFIG_SCHEMA_NULLIFY_NOT_NULL]", "'orders.user_id'");
        assertThat(target.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'e2e_en'", Integer.class))
                .as("nothing written to the target").isZero();
    }

    @Test
    @DisplayName("#29a — MASK on the UNIQUE email of e2e_edge is refused by plan (masks collide)")
    void maskOnUniqueColumnIsRefusedByPlan() throws IOException {
        E2eSettings.seedSource();
        E2eProject project = E2eProject.create("config-rejection-unique");
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_edge");
        String yaml = Files.readString(E2eSettings.RESOURCES.resolve("edge-brume.yml"));
        String email = """
                        - name: email
                          strategy: FAKE
                          type: EMAIL
                """;
        assertThat(yaml).contains(email);
        project.useConfig(yaml.replace(email, """
                        - name: email
                          strategy: MASK
                          type: EMAIL
                """));

        BrumeCli.Result plan = project.run("plan");

        assertThat(plan.exitCode()).as(plan.describe()).isEqualTo(1);
        assertThat(plan.stderr()).contains("[CONFIG_SCHEMA_UNIQUE_NOT_INJECTIVE]", "'Account.email'");
    }
}
