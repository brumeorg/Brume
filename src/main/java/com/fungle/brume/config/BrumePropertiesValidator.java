package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.util.SafeOutputPath;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.SinkType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Validates cryptographic key properties from {@link BrumeProperties} at application startup.
 *
 * <p>This validator ensures that:
 * <ul>
 *   <li>{@code brume.hmac-secret} is at least 16 UTF-8 bytes (recommended minimum entropy)</li>
 *   <li>{@code brume.fpe-key} is exactly 16, 24, or 32 UTF-8 bytes (AES-128/192/256)</li>
 * </ul>
 *
 * <p>Validation runs in {@link #validate()} annotated with {@code @PostConstruct}, so the application
 * fails fast with a clear error message if keys are misconfigured, rather than silently truncating or
 * padding them (which would produce weak cryptographic outputs).
 *
 * <p>Thread safety: Spring calls {@code @PostConstruct} once, in a single thread, before bean usage.
 *
 * @see BrumeProperties
 * @see com.fungle.brume.anonymization.strategies.FpeIdStrategy
 * @see com.fungle.brume.anonymization.strategies.FakeStrategy
 */
@Component
public class BrumePropertiesValidator {

    private static final Logger log = LoggerFactory.getLogger(BrumePropertiesValidator.class);

    /** Minimum required length for HMAC secret in UTF-8 bytes (128 bits minimum recommended). */
    private static final int HMAC_SECRET_MIN_LENGTH = 16;

    /** Valid FPE key lengths in UTF-8 bytes (AES-128, AES-192, AES-256). */
    private static final Set<Integer> VALID_FPE_KEY_LENGTHS = Set.of(16, 24, 32);

    private final BrumeProperties properties;

    /**
     * Creates a new validator for the provided Brume configuration properties.
     *
     * @param properties the Brume properties to validate
     */
    public BrumePropertiesValidator(BrumeProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates HMAC secret and FPE key length requirements at application startup.
     *
     * @throws ConfigurationException if any validation rule is violated
     */
    @PostConstruct
    public void validate() {
        log.debug("Validating cryptographic key properties...");
        validateHmacSecret();
        validateFpeKey();
        validateGuardrails();
        validateOutputPaths();
        validateSinkCompressionExtension();
        log.info("✓ Cryptographic key properties validated successfully");
    }

    /**
     * Validates that {@code brume.hmac-secret} is set and meets minimum length requirements.
     *
     * @throws ConfigurationException if the HMAC secret is null, blank, or too short
     */
    private void validateHmacSecret() {
        String secret = properties.hmacSecret();

        if (secret == null || secret.isBlank()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_HMAC_SECRET_MISSING,
                    "brume.hmac-secret must be set",
                    "Set 'brume.hmac-secret' in application.yaml (or via the BRUME_HMAC_SECRET "
                            + "environment variable) to a high-entropy value of at least "
                            + HMAC_SECRET_MIN_LENGTH + " UTF-8 bytes.");
        }

        int lengthBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (lengthBytes < HMAC_SECRET_MIN_LENGTH) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_HMAC_SECRET_TOO_SHORT,
                    "brume.hmac-secret must be at least " + HMAC_SECRET_MIN_LENGTH
                            + " UTF-8 bytes (got " + lengthBytes + ")",
                    "Generate a longer secret — e.g. 'openssl rand -hex 32' for a 64-byte hex "
                            + "value — and update 'brume.hmac-secret' accordingly.");
        }
    }

    /**
     * Validates that {@code brume.fpe-key} is set and has a valid AES key length (16, 24, or 32 bytes).
     *
     * @throws ConfigurationException if the FPE key is null, blank, or has an invalid length
     */
    private void validateFpeKey() {
        String key = properties.fpeKey();

        if (key == null || key.isBlank()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_FPE_KEY_MISSING,
                    "brume.fpe-key must be set",
                    "Set 'brume.fpe-key' in application.yaml (or via the BRUME_FPE_KEY environment "
                            + "variable) to a 16-, 24-, or 32-byte UTF-8 value matching the AES "
                            + "key size you intend to use (AES-128, AES-192, or AES-256).");
        }

        int lengthBytes = key.getBytes(StandardCharsets.UTF_8).length;
        if (!VALID_FPE_KEY_LENGTHS.contains(lengthBytes)) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_FPE_KEY_INVALID_LENGTH,
                    "brume.fpe-key must be exactly 16, 24, or 32 UTF-8 bytes (got " + lengthBytes + ")",
                    "Adjust the value of 'brume.fpe-key' so its UTF-8 length is exactly 16 "
                            + "(AES-128), 24 (AES-192), or 32 (AES-256). Generate a fresh key with "
                            + "'openssl rand -hex 16' (→ 32 hex chars = 32 bytes).");
        }
    }

    /**
     * Rejects path-traversal vectors on output paths read from operator configuration
     * (audit § A4, ADR-0020). Each non-blank path must resolve under the JVM working
     * directory.
     *
     * @throws ConfigurationException if any output path escapes the working directory
     */
    private void validateOutputPaths() {
        if (properties.sink() != null && properties.sink().type() == SinkType.DUMP) {
            SafeOutputPath.validate(properties.sink().outputPath(), "brume.sink.output-path");
        }
        if (properties.report() != null) {
            String json = properties.report().jsonFile();
            if (json != null && !json.isBlank()) {
                SafeOutputPath.validate(json, "brume.report.json-file");
            }
            String html = properties.report().htmlFile();
            if (html != null && !html.isBlank()) {
                SafeOutputPath.validate(html, "brume.report.html-file");
            }
            String planHtml = properties.report().planHtmlFile();
            if (planHtml != null && !planHtml.isBlank()) {
                SafeOutputPath.validate(planHtml, "brume.report.plan-html-file");
            }
        }
    }

    /**
     * Rejects inconsistent {@code brume.sink.output-path} ↔ {@code brume.sink.compression}
     * combinations at boot (#4b, ADR-0022). When {@code sink.type=DUMP}:
     * <ul>
     *   <li>{@code GZIP} → path must end with {@code .gz} (case-insensitive)</li>
     *   <li>{@code ZSTD} → path must end with {@code .zst} (case-insensitive)</li>
     *   <li>{@code NONE} → path must not end with {@code .gz} or {@code .zst}</li>
     * </ul>
     *
     * <p>Supersedes Q3 of ADR-0008 (originally "respect path as-is, no validation"),
     * which proved UX-hostile in YAML-config flows where the default {@code GZIP}
     * compression silently produced gzipped content under a misleading {@code .sql}
     * extension. Runs after {@link #validateOutputPaths()}, which already guarantees
     * the path is non-blank for {@code DUMP} sinks.
     *
     * @throws ConfigurationException if the path extension is inconsistent with the compression
     */
    private void validateSinkCompressionExtension() {
        if (properties.sink() == null || properties.sink().type() != SinkType.DUMP) {
            return;
        }
        String path = properties.sink().outputPath();
        if (path == null || path.isBlank()) {
            return;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        CompressionType compression = properties.sink().compression();

        switch (compression) {
            case GZIP -> {
                if (!lower.endsWith(".gz")) {
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_SINK_COMPRESSION_EXTENSION_MISMATCH,
                            "brume.sink.compression=GZIP but brume.sink.output-path='" + path
                                    + "' does not end with '.gz'",
                            "Use a path ending in '.gz' (e.g. '" + path + ".gz') or set "
                                    + "brume.sink.compression=NONE to keep the path as-is. The "
                                    + "produced file would otherwise be gzipped under a misleading "
                                    + "extension.");
                }
            }
            case ZSTD -> {
                if (!lower.endsWith(".zst")) {
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_SINK_COMPRESSION_EXTENSION_MISMATCH,
                            "brume.sink.compression=ZSTD but brume.sink.output-path='" + path
                                    + "' does not end with '.zst'",
                            "Use a path ending in '.zst' (e.g. '" + path + ".zst') or set "
                                    + "brume.sink.compression=NONE to keep the path as-is. The "
                                    + "produced file would otherwise be zstd-compressed under a "
                                    + "misleading extension.");
                }
            }
            case NONE -> {
                if (lower.endsWith(".gz") || lower.endsWith(".zst")) {
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_SINK_COMPRESSION_EXTENSION_MISMATCH,
                            "brume.sink.compression=NONE but brume.sink.output-path='" + path
                                    + "' ends with a compressed extension",
                            "Use a path without '.gz'/'.zst' or set brume.sink.compression="
                                    + "GZIP/ZSTD to match the extension. The produced file would "
                                    + "otherwise be plain SQL under a misleading extension.");
                }
            }
        }
    }

    private void validateGuardrails() {
        if (properties.maxBatchErrorRate() < 0.0 || properties.maxBatchErrorRate() > 1.0) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_GUARDRAIL_OUT_OF_RANGE,
                    "brume.max-batch-error-rate must be between 0.0 and 1.0 (got "
                            + properties.maxBatchErrorRate() + ")",
                    "Set 'brume.max-batch-error-rate' to a value in [0.0, 1.0] in "
                            + "application.yaml. 0.0 fails on any batch error; 0.05 tolerates up "
                            + "to 5% errors per table before aborting.");
        }

        if (properties.maxTargetRows() < 0) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_GUARDRAIL_OUT_OF_RANGE,
                    "brume.max-target-rows must be >= 0 (got " + properties.maxTargetRows() + ")",
                    "Set 'brume.max-target-rows' to a non-negative integer in application.yaml "
                            + "(use 0 to disable the cap entirely).");
        }

        if (properties.heapWarningThresholdPercent() < 1 || properties.heapWarningThresholdPercent() > 100) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_GUARDRAIL_OUT_OF_RANGE,
                    "brume.heap-warning-threshold-percent must be between 1 and 100 (got "
                            + properties.heapWarningThresholdPercent() + ")",
                    "Set 'brume.heap-warning-threshold-percent' to an integer in [1, 100] in "
                            + "application.yaml. 80 is a sensible default.");
        }
    }
}
