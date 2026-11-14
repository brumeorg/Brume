package com.fungle.brume.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code brume dry-run} (#127e) : the whole pipeline runs — extraction, anonymization —
 * but nothing is persisted, whatever sink the project is configured for.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DryRunE2E {

    private final E2eProject project = E2eProject.create("dry-run");
    private final JdbcTemplate target = E2eSettings.target().jdbc();

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));
        target.execute("DROP SCHEMA IF EXISTS e2e_en CASCADE");
    }

    @Test
    @DisplayName("dry-run on a JDBC project : runs end to end, the target schema is never created")
    void jdbcProjectWritesNothing() {
        BrumeCli.Result result = project.run("dry-run");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(result.stdout()).contains("dry-run");
        Integer schemas = target.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'e2e_en'", Integer.class);
        assertThat(schemas).as("dry-run must not touch the target").isZero();
    }

    @Test
    @DisplayName("dry-run on a DUMP project : no dump file is written")
    void dumpProjectWritesNoFile() throws IOException {
        project.setEnv("BRUME_SINK_TYPE", "DUMP");
        project.setEnv("BRUME_SINK_OUTPUT_PATH", "./dumps/dry-run.sql.gz");
        try {
            BrumeCli.Result result = project.run("dry-run");

            assertThat(result.exitCode()).as(result.describe()).isZero();
            assertThat(project.file("dumps/dry-run.sql.gz")).doesNotExist();
            if (Files.isDirectory(project.file("dumps"))) {
                try (Stream<java.nio.file.Path> files = Files.list(project.file("dumps"))) {
                    assertThat(files).as("dry-run must not create any dump").isEmpty();
                }
            }
        } finally {
            project.setEnv("BRUME_SINK_TYPE", "JDBC");
            project.removeEnv("BRUME_SINK_OUTPUT_PATH");
        }
    }
}
