package com.fungle.brume.init;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PgDumpChecker {

    public static final int REQUIRED_MAJOR_VERSION = 17;

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("\\bPostgreSQL\\)?\\s+(\\d+)(?:\\.(\\d+))?");

    public record Result(boolean found, Optional<Integer> majorVersion, Optional<String> rawOutput) {

        public boolean meetsRequirement() {
            return found && majorVersion.map(v -> v >= REQUIRED_MAJOR_VERSION).orElse(false);
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
