package com.fungle.brume;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.BrumeErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a {@code .env.brume} parse failure of dotenv-java into
 * {@link BrumeErrorCode#CONFIG_ENV_FILE_INVALID} (#17c, ADR-0061).
 *
 * <p>dotenv-java throws an {@code IllegalStateException} for a duplicated key and a
 * {@code DotenvException} for a malformed line ; both used to reach the operator as a raw
 * Java stack trace. The report names the key and the line numbers, never the line content
 * nor the values : {@code .env.brume} holds the secrets, and a mistyped
 * {@code BRUME_HMAC_SECRET secret} line must not end up in a CI log. For the same reason the
 * dotenv exception (whose message quotes the values) is not kept as cause, so even
 * {@code brume.show-stacktrace=true} does not print it.
 */
final class DotEnvErrors {

    private static final Pattern DUPLICATE = Pattern.compile("^Duplicate key (\\S+)");
    private static final Pattern MALFORMED = Pattern.compile("^Malformed entry (.*)$", Pattern.DOTALL);

    private DotEnvErrors() {
    }

    static ConfigurationException translate(RuntimeException failure, Path file) {
        List<String> lines = readLines(file);
        String message = failure.getMessage() == null ? "" : failure.getMessage();

        Matcher duplicate = DUPLICATE.matcher(message);
        if (duplicate.find()) {
            String key = duplicate.group(1);
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
                if (line.startsWith(key + "=") || line.startsWith(key + " =")) at.add(i + 1);
            }
            return new ConfigurationException(BrumeErrorCode.CONFIG_ENV_FILE_INVALID,
                    ".env.brume declares " + key + " more than once" + linesSuffix(at) + ".",
                    "Keep a single " + key + "=… line in .env.brume.");
        }

        Matcher malformed = MALFORMED.matcher(message);
        if (malformed.find()) {
            String entry = malformed.group(1).trim();
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals(entry)) at.add(i + 1);
            }
            return new ConfigurationException(BrumeErrorCode.CONFIG_ENV_FILE_INVALID,
                    ".env.brume has a malformed line" + linesSuffix(at) + ".",
                    "Each line must be KEY=value, a # comment or empty. Quote values that contain "
                            + "spaces or '#'.");
        }

        return new ConfigurationException(BrumeErrorCode.CONFIG_ENV_FILE_INVALID,
                ".env.brume cannot be read (" + failure.getClass().getSimpleName() + ").",
                "Check that the file is readable UTF-8 text made of KEY=value lines.", failure);
    }

    private static String linesSuffix(List<Integer> at) {
        if (at.isEmpty()) return "";
        return (at.size() == 1 ? " (line " : " (lines ")
                + String.join(", ", at.stream().map(String::valueOf).toList()) + ")";
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
