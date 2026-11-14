package com.fungle.brume.init;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PgDumpChecker {

    /**
     * Oldest pg_dump Brume accepts — the floor of the supported server matrix (ADR-0058).
     * The real rule (pg_dump major ≥ source major) is enforced at run time by the preflight,
     * because init does not know the source yet.
     */
    public static final int MINIMUM_MAJOR_VERSION = 12;

    /**
     * Latest PostgreSQL major : a pg_dump of this version reads every supported source
     * (12 → 18), so it is the one Brume recommends installing (ADR-0058).
     */
    public static final int RECOMMENDED_MAJOR_VERSION = 18;

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("\\bPostgreSQL\\)?\\s+(\\d+)(?:\\.(\\d+))?");

    public record Result(boolean found, Optional<Integer> majorVersion, Optional<String> rawOutput) {

        public boolean meetsRequirement() {
            return found && majorVersion.map(v -> v >= MINIMUM_MAJOR_VERSION).orElse(false);
        }

        /** True when this pg_dump can read every supported source version. */
        public boolean isRecommended() {
            return found && majorVersion.map(v -> v >= RECOMMENDED_MAJOR_VERSION).orElse(false);
        }
    }

    public Result probe() {
        try {
            Process process = new ProcessBuilder("pg_dump", "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, Optional.empty(), Optional.empty());
            }
            if (process.exitValue() != 0 || output == null) {
                return new Result(false, Optional.empty(), Optional.ofNullable(output));
            }
            return new Result(true, parseMajorVersion(output), Optional.of(output));
        } catch (Exception e) {
            return new Result(false, Optional.empty(), Optional.empty());
        }
    }

    static Optional<Integer> parseMajorVersion(String versionLine) {
        if (versionLine == null) return Optional.empty();
        Matcher m = VERSION_PATTERN.matcher(versionLine);
        if (m.find()) {
            return Optional.of(Integer.parseInt(m.group(1)));
        }
        return Optional.empty();
    }
}
