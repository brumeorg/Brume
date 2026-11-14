package com.fungle.brume.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same {@code e2e_en} extraction through the other write paths (ADR-0051) : the BATCH pipeline
 * and the DUMP sink must produce the golden dataset of the STREAMING / JDBC journey, and a
 * dump must be byte-identical from one run to the next.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SinkModesE2E {

    private static final List<String> TABLES = List.of("users", "products", "orders", "order_items", "audit_logs");

    private final E2eProject project = E2eProject.create("sink-modes");
    private final E2eSettings.Db targetDb = E2eSettings.target();
    private final JdbcTemplate target = targetDb.jdbc();

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("e2e_en");
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));
    }

    @Test
    @DisplayName("BATCH pipeline : same target as STREAMING, value for value")
    void batchPipelineMatchesGolden() {
        project.setEnv("BRUME_SINK_TYPE", "JDBC");
        project.setEnv("BRUME_PIPELINE_MODE", "BATCH");
        try {
            BrumeCli.Result execute = project.run("execute");

            assertThat(execute.exitCode()).as(execute.describe()).isZero();
            assertThat(goldenDiffs()).isEmpty();
        } finally {
            project.setEnv("BRUME_PIPELINE_MODE", "STREAMING");
        }
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"GZIP, dumps/e2e.sql.gz", "ZSTD, dumps/e2e.sql.zst", "NONE, dumps/e2e.sql"})
    @DisplayName("DUMP sink : the file restored with psql gives the golden dataset")
    void dumpRestoresToGolden(String compression, String outputPath) {
        dumpTo(outputPath, compression);

        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        target.execute("DROP SCHEMA IF EXISTS e2e_en CASCADE");
        assertThat(Psql.run(targetDb, project.file(outputPath))).as("psql errors while restoring").isEmpty();
        assertThat(goldenDiffs()).isEmpty();
    }

    /**
     * The {@code --strip-timestamps} CLI flag is rejected by picocli (known gap #22a), so the
     * option is set the other supported way : {@code .env.brume} entries become system properties.
     *
     * <p>#25e — pg_dump (16.10+ / 17.6+ / 18) wraps its DDL in psql "restrict" / "unrestrict"
     * meta-commands carrying a random key per run. Brume strips them (#3b, ADR-0058), so the
     * two files are identical byte for byte.
     */
    @Test
    @DisplayName("#25e — DUMP with strip-timestamps : two runs are identical byte for byte "
            + "(pg_dump's random \\restrict key is stripped, ADR-0058)")
    void dumpIsByteIdenticalAcrossRuns() throws IOException {
        project.setEnv("brume.sink.strip-timestamps", "true");
        try {
            dumpTo("dumps/run-1.sql", "NONE");
            assertThat(project.run("execute").exitCode()).isZero();
            dumpTo("dumps/run-2.sql", "NONE");
            assertThat(project.run("execute").exitCode()).isZero();
        } finally {
            project.setEnv("brume.sink.strip-timestamps", "false");
        }

        List<String> run1 = Files.readAllLines(project.file("dumps/run-1.sql"));
        List<String> run2 = Files.readAllLines(project.file("dumps/run-2.sql"));
        assertThat(run1).noneMatch(line -> line.contains("generated_at"));
        assertThat(run1).as("#25e — pg_dump's random restrict key lines are stripped")
                .noneMatch(line -> line.startsWith("\\restrict ") || line.startsWith("\\unrestrict "));
        assertThat(run1).isEqualTo(run2);
    }

    private void dumpTo(String outputPath, String compression) {
        project.setEnv("BRUME_PIPELINE_MODE", "STREAMING");
        project.setEnv("BRUME_SINK_TYPE", "DUMP");
        project.setEnv("BRUME_SINK_OUTPUT_PATH", "./" + outputPath);
        project.setEnv("BRUME_SINK_COMPRESSION", compression);
    }

    private Map<String, List<String>> goldenDiffs() {
        Map<String, List<String>> diffs = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<String> tableDiffs = GoldenDataset.compareWith(
                    E2eSettings.GOLDEN_DIR.resolve("e2e_en").resolve(table + ".tsv"),
                    GoldenDataset.snapshot(target, "e2e_en", table, "id"));
            if (!tableDiffs.isEmpty()) diffs.put(table, tableDiffs);
        }
        return diffs;
    }
}
