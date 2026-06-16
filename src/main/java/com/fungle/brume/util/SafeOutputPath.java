package com.fungle.brume.util;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.BrumeErrorCode;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Path-traversal safety for output file paths read from operator configuration.
 *
 * <p>Brume accepts four user-supplied filesystem paths via configuration :
 * {@code brume.sink.output-path}, {@code brume.report.html-file},
 * {@code brume.report.plan-html-file}, {@code brume.report.json-file}. All four
 * historically forwarded the raw string straight to {@code Path.of(...)} +
 * {@code Files.newOutputStream(...)}, with no validation of absolute paths or
 * {@code ..} segments.
 *
 * <p>Threat model (audit 2026-05-05 § A4) : Brume configurations are commonly
 * templated in CI and consumed by a Docker container running as root with a
 * bind-mounted output volume. An attacker who controls the templating can write
 * arbitrary files on the host (e.g. {@code /etc/cron.daily/poison}) or escape
 * the bind mount via {@code ../}.
 *
 * <p>Mitigation (ADR-0020) : a path is "safe" iff
 * {@code baseDir.resolve(rawPath).normalize().startsWith(baseDir)} where
 * {@code baseDir} is the JVM startup working directory. This rejects :
 * <ul>
 *   <li>NUL bytes (filesystem-level injection guard)</li>
 *   <li>Absolute paths pointing outside cwd</li>
 *   <li>{@code ..} segments that climb above cwd</li>
 *   <li>null / blank paths (a misconfiguration that would crash later)</li>
 * </ul>
 *
 * <p>The class exposes a package-visible overload that takes an explicit
 * {@code baseDir} so unit tests can assert behavior deterministically without
 * depending on the JVM's actual working directory.
 */
public final class SafeOutputPath {

    private static final char NUL = (char) 0;

    /**
     * Working directory captured at class load. Used as the implicit anchor for
     * relative paths and as the boundary that no resolved path may escape.
     */
    private static final Path DEFAULT_BASE_DIR = Path.of(".").toAbsolutePath().normalize();

    private SafeOutputPath() {
    }

    /**
     * Validates {@code rawPath} as an output file path read from operator
     * configuration. Returns the normalized absolute {@link Path}.
     *
     * @param rawPath      the raw string read from configuration
     * @param propertyName the property name (e.g. {@code brume.sink.output-path}) used in error messages
     * @return the normalized absolute path
     * @throws ConfigurationException if the path is null/blank, contains a NUL byte,
     *                                is malformed, or resolves outside the JVM working directory
     */
    public static Path validate(String rawPath, String propertyName) {
        return validate(rawPath, propertyName, DEFAULT_BASE_DIR);
    }

    /**
     * Test-only overload that allows callers to override the base directory used
     * as the path-traversal boundary. Production code uses
     * {@link #validate(String, String)} which anchors on JVM cwd.
     */
    static Path validate(String rawPath, String propertyName, Path baseDir) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_OUTPUT_PATH_BLANK,
                    propertyName + " must not be blank",
                    "Set '" + propertyName + "' in application.yaml to a relative path under "
                            + "the JVM working directory (e.g. 'dumps/output.sql.gz').");
        }
        if (rawPath.indexOf(NUL) >= 0) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_OUTPUT_PATH_INVALID,
                    propertyName + " contains a NUL byte — rejected for filesystem safety",
                    "Remove the NUL byte (U+0000) from '" + propertyName + "'. NUL bytes are "
                            + "filesystem injection vectors and are never legitimate in a path.");
        }
        Path resolved;
        try {
            resolved = baseDir.resolve(rawPath).normalize();
        } catch (InvalidPathException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_OUTPUT_PATH_INVALID,
                    propertyName + "='" + rawPath + "' is not a valid filesystem path: "
                            + e.getMessage(),
                    "Fix the path syntax in '" + propertyName + "' — common culprits are illegal "
                            + "characters on the host OS (e.g. ':' on Windows outside a drive "
                            + "letter, or non-UTF-8 bytes).",
                    e);
        }
        if (!resolved.startsWith(baseDir)) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_OUTPUT_PATH_TRAVERSAL,
                    propertyName + "='" + rawPath + "' resolves to '" + resolved
                            + "' which is outside the working directory '" + baseDir
                            + "' — path traversal rejected (audit § A4, ADR-0020)",
                    "Use a path relative to (or absolute under) the working directory. If you "
                            + "need to write outside cwd, change the JVM's working directory at "
                            + "launch (e.g. WORKDIR in the Dockerfile) — Brume refuses to escape "
                            + "it from configuration alone.");
        }
        return resolved;
    }
}
