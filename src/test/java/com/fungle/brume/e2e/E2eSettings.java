package com.fungle.brume.e2e;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Environment of the functional E2E suite (ADR-0049) : binary under test, fixture
 * locations and the two Postgres instances.
 *
 * <p>Databases are read from {@code E2E_SOURCE_*} / {@code E2E_TARGET_*} environment
 * variables. Defaults match {@code docker-compose.e2e.yml} ; GitLab CI overrides them
 * with the {@code pg-source} / {@code pg-target} service aliases.
 */
final class E2eSettings {

    static final Path RESOURCES = Path.of("src/test/resources/e2e");
    static final Path GOLDEN_DIR = RESOURCES.resolve("expected");

    private E2eSettings() {
    }

    /**
     * Binary under test, from {@code -De2e.brume.bin}. Defaults to the native image
     * produced by {@code ./mvnw -Pnative native:compile}. A {@code .jar} is accepted for
     * quick local iterations ; CI always points to the native binary.
     */
    static Path binary() {
        Path bin = Path.of(System.getProperty("e2e.brume.bin", "target/brume")).toAbsolutePath();
        if (!Files.isRegularFile(bin)) {
            throw new IllegalStateException("Binary under test not found: " + bin
                    + " — build it with `./mvnw -Pnative native:compile -DskipTests` "
                    + "or pass -De2e.brume.bin=<path>.");
        }
        return bin;
    }

    /** {@code -De2e.updateGolden=true} rewrites the golden dataset instead of comparing. */
    static boolean updateGolden() {
        return Boolean.getBoolean("e2e.updateGolden");
    }

    /** Loads the source fixtures (idempotent : each script drops and recreates its schema). */
    static void seedSource() {
        JdbcTemplate source = source().jdbc();
        for (String script : List.of("source/e2e_en.sql", "source/e2e_fr.sql", "source/e2e_edge.sql")) {
            try {
                source.execute(Files.readString(RESOURCES.resolve(script)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static Db source() {
        return Db.fromEnv("E2E_SOURCE", 55432);
    }

    static Db target() {
        return Db.fromEnv("E2E_TARGET", 55433);
    }

    record Db(String host, int port, String database, String user, String password) {

        static Db fromEnv(String prefix, int defaultPort) {
            return new Db(
                    env(prefix + "_HOST", "localhost"),
                    Integer.parseInt(env(prefix + "_PORT", String.valueOf(defaultPort))),
                    env(prefix + "_DB", "postgres"),
                    env(prefix + "_USER", "postgres"),
                    env(prefix + "_PASSWORD", "postgres"));
        }

        Db withDatabase(String name) {
            return new Db(host, port, name, user, password);
        }

        JdbcTemplate jdbc() {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    "jdbc:postgresql://" + host + ":" + port + "/" + database, user, password);
            return new JdbcTemplate(ds);
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
