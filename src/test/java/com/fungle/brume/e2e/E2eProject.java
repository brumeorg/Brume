package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Working directory of a brume project during an E2E scenario (ADR-0049) : the files
 * {@code init} creates, a {@code .env.brume} pointed at the E2E databases, and one log file per
 * command, kept under {@code target/e2e-work/<name>} so a failed CI run can be inspected from
 * the job artifacts.
 */
final class E2eProject {

    // Test-owned secrets : the golden dataset must not depend on the values shipped in the
    // .env templates.
    private static final String HMAC_SECRET = "e2e-hmac-secret-0001";
    private static final String FPE_KEY = "e2e-fpe-key-0001";

    private final Path dir;
    private final BrumeCli cli;
    private int commands;

    private E2eProject(Path dir) {
        this.dir = dir;
        this.cli = new BrumeCli(E2eSettings.binary(), dir);
    }

    /** Fresh, empty project directory {@code target/e2e-work/<name>}. */
    static E2eProject create(String name) {
        Path dir = Path.of("target", "e2e-work", name).toAbsolutePath();
        try {
            FileSystemUtils.deleteRecursively(dir);
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new E2eProject(dir);
    }

    Path dir() {
        return dir;
    }

    Path file(String name) {
        return dir.resolve(name);
    }

    /** Runs {@code brume <args>} in the project directory and logs its output to a numbered file. */
    BrumeCli.Result run(String... args) {
        BrumeCli.Result result = cli.run(args);
        String name = String.format("%02d-brume-%s.log", ++commands,
                String.join("-", args).replace("--", "").replaceAll("[^A-Za-z0-9._=-]", "_"));
        write(name, "$ brume " + String.join(" ", args) + "\n" + result.describe());
        return result;
    }

    /** Sets a variable of the binary's process environment (not of {@code .env.brume}). */
    void processEnv(String key, String value) {
        cli.putEnv(key, value);
    }

    /** Replaces {@code brume.yml}. */
    void useConfig(String yaml) {
        write("brume.yml", yaml);
    }

    /**
     * Points the {@code .env.brume} created by {@code init} to the E2E databases. Only
     * existing keys are rewritten : a key missing from the template fails the scenario,
     * which is the drift we want to catch between the template and {@code application.yaml}.
     */
    void configureEnv(String schema) {
        E2eSettings.Db source = E2eSettings.source();
        E2eSettings.Db target = E2eSettings.target();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("BRUME_HMAC_SECRET", HMAC_SECRET);
        values.put("BRUME_FPE_KEY", FPE_KEY);
        values.put("BRUME_SOURCE_HOST", source.host());
        values.put("BRUME_SOURCE_PORT", String.valueOf(source.port()));
        values.put("BRUME_SOURCE_DB", source.database());
        values.put("BRUME_SOURCE_USER", source.user());
        values.put("BRUME_SOURCE_PASSWORD", source.password());
        values.put("BRUME_SOURCE_SSLMODE", "disable");
        values.put("BRUME_SCHEMA", schema);
        values.put("BRUME_TARGET_HOST", target.host());
        values.put("BRUME_TARGET_PORT", String.valueOf(target.port()));
        values.put("BRUME_TARGET_DB", target.database());
        values.put("BRUME_TARGET_USER", target.user());
        values.put("BRUME_TARGET_PASSWORD", target.password());
        values.put("BRUME_PARALLELISM", "4");
        values.put("BRUME_LOCALE", "en");
        values.put("BRUME_SINK_TYPE", "JDBC");
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file(".env.brume"), StandardCharsets.UTF_8));
            values.forEach((key, value) -> {
                int index = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(key + "=")) index = i;
                }
                assertThat(index).as("%s must be an active key of the .env template created by init", key).isNotNegative();
                lines.set(index, key + "=" + value);
            });
            Files.write(file(".env.brume"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sets {@code key=value} in {@code .env.brume}, replacing an active line or appending one —
     * for options the templates do not list (pipeline mode, sink output of a JDBC project…).
     */
    void setEnv(String key, String value) {
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file(".env.brume"), StandardCharsets.UTF_8));
            lines.removeIf(line -> line.startsWith(key + "="));
            lines.add(key + "=" + value);
            Files.write(file(".env.brume"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Planned rows per table (direct + FK parents + FK children) from {@code brume plan --json}. */
    static Map<String, Integer> plannedRows(BrumeCli.Result planJson) {
        try {
            JsonNode plan = new ObjectMapper().readTree(planJson.stdout()).path("plan");
            Map<String, Integer> rows = new LinkedHashMap<>();
            plan.path("tableStats").forEach(t -> rows.put(t.path("table").asText(),
                    t.path("plannedDirect").asInt() + t.path("plannedFkParents").asInt()
                            + t.path("plannedFkChildren").asInt()));
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("plan --json did not print JSON :\n" + planJson.describe(), e);
        }
    }

    private void write(String name, String content) {
        try {
            Files.writeString(file(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
