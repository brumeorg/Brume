package com.fungle.brume;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for the databases and PostgreSQL client tools used by the ITs, so the
 * same suite runs against any server version (12 → 18) — locally against
 * {@code docker-compose.it.yml} / {@code docker-compose.yml}, in CI against GitLab
 * {@code services:} (ADR-0059).
 *
 * <p>Each value is read from a system property, then an environment variable, then a local
 * default. Spring contexts read the same values through the {@code it.db.*} placeholders of
 * {@code src/test/resources/application.yaml}.
 *
 * <ul>
 *   <li>{@code it.db.source-url} / {@code BRUME_IT_SOURCE_URL}</li>
 *   <li>{@code it.db.target-url} / {@code BRUME_IT_TARGET_URL}</li>
 *   <li>{@code it.db.pgdump-path} / {@code BRUME_IT_PGDUMP_PATH} — a pg_dump 18 by default,
 *       run in a container so no local install is needed : the latest client reads every
 *       supported source, which is what users run (ADR-0058).</li>
 *   <li>{@code it.db.psql} / {@code BRUME_IT_PSQL} — same idea for {@code psql}.</li>
 * </ul>
 */
public final class ItDatabases {

    public static final String USER = "postgres";
    public static final String PASSWORD = "postgres";

    public static final String SOURCE_URL =
            setting("it.db.source-url", "BRUME_IT_SOURCE_URL", "jdbc:postgresql://localhost:5432/postgres");
    public static final String TARGET_URL =
            setting("it.db.target-url", "BRUME_IT_TARGET_URL", "jdbc:postgresql://localhost:5460/postgres");
    public static final String PGDUMP_PATH = setting("it.db.pgdump-path", "BRUME_IT_PGDUMP_PATH",
            "docker run --rm -i --network host -e PGPASSWORD -e PGSSLMODE postgres:18 pg_dump");
    public static final String PSQL = setting("it.db.psql", "BRUME_IT_PSQL",
            "docker run --rm -i --network host -e PGPASSWORD postgres:18 psql");

    private static final Pattern HOST_PORT_DB =
            Pattern.compile("^jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+)");

    private ItDatabases() {
    }

    /**
     * {@code psql} connected to the target database, password set, followed by {@code extraArgs}
     * (e.g. {@code "-v", "ON_ERROR_STOP=1", "--quiet"}). Reads the script on stdin.
     */
    public static ProcessBuilder targetPsql(String... extraArgs) {
        Matcher m = HOST_PORT_DB.matcher(TARGET_URL);
        if (!m.find()) {
            throw new IllegalStateException("Unparseable target URL for psql: " + TARGET_URL);
        }
        List<String> cmd = new ArrayList<>();
        for (String token : PSQL.split("\\s+")) {
            if (!token.isBlank()) cmd.add(token);
        }
        cmd.addAll(List.of("-h", m.group(1), "-p", m.group(2) == null ? "5432" : m.group(2),
                "-U", USER, "-d", m.group(3)));
        cmd.addAll(List.of(extraArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", PASSWORD);
        return pb;
    }

    private static String setting(String property, String env, String fallback) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv(env);
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : fallback;
    }
}
