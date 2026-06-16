package com.fungle.brume.config;

import com.fungle.brume.config.BrumeProperties.JdbcSinkProperties;
import com.fungle.brume.config.BrumeProperties.PlanProperties;
import com.fungle.brume.config.BrumeProperties.ReportProperties;
import com.fungle.brume.config.BrumeProperties.SinkProperties;
import com.fungle.brume.plan.PlanMode;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.SinkType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BrumePropertiesValidator}.
 *
 * <p>Validates that cryptographic key properties are checked at application startup:
 * <ul>
 *   <li>HMAC secret minimum length (16 UTF-8 bytes)</li>
 *   <li>FPE key exact lengths (16, 24, or 32 UTF-8 bytes for AES-128/192/256)</li>
 *   <li>UTF-8 byte counting (not character counting)</li>
 * </ul>
 */
class BrumePropertiesValidatorTest {

    // ==================== HMAC Secret validation ====================

    @Test
    void validate_throwsConfigurationException_whenHmacSecretIsNull() {
        BrumeProperties props = createProperties(null, "0123456789abcdef");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.hmac-secret must be set");
    }

    @Test
    void validate_throwsConfigurationException_whenHmacSecretIsBlank() {
        BrumeProperties props = createProperties("   ", "0123456789abcdef");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.hmac-secret must be set");
    }

    @Test
    void validate_throwsConfigurationException_whenHmacSecretIsTooShort() {
        BrumeProperties props = createProperties("short", "0123456789abcdef"); // 5 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.hmac-secret must be at least 16 UTF-8 bytes (got 5)");
    }

    @Test
    void validate_throwsConfigurationException_whenHmacSecretIs15Bytes() {
        BrumeProperties props = createProperties("012345678901234", "0123456789abcdef"); // 15 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.hmac-secret must be at least 16 UTF-8 bytes (got 15)");
    }

    @Test
    void validate_succeeds_whenHmacSecretIs16Bytes() {
        BrumeProperties props = createProperties("0123456789abcdef", "0123456789abcdef"); // 16 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void validate_succeeds_whenHmacSecretIs100Bytes() {
        String longSecret = "a".repeat(100); // 100 bytes
        BrumeProperties props = createProperties(longSecret, "0123456789abcdef");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }

    // ==================== FPE Key validation ====================

    @Test
    void validate_throwsConfigurationException_whenFpeKeyIsNull() {
        BrumeProperties props = createProperties("0123456789abcdef", null);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.fpe-key must be set");
    }

    @Test
    void validate_throwsConfigurationException_whenFpeKeyIsBlank() {
        BrumeProperties props = createProperties("0123456789abcdef", "   ");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.fpe-key must be set");
    }

    @Test
    void validate_throwsConfigurationException_whenFpeKeyIs15Bytes() {
        BrumeProperties props = createProperties("0123456789abcdef", "012345678901234"); // 15 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.fpe-key must be exactly 16, 24, or 32 UTF-8 bytes (got 15)");
    }

    @Test
    void validate_succeeds_whenFpeKeyIs16BytesAscii() {
        BrumeProperties props = createProperties("0123456789abcdef", "0123456789abcdef"); // 16 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void validate_throwsConfigurationException_whenFpeKeyIs17Bytes() {
        BrumeProperties props = createProperties("0123456789abcdef", "0123456789abcdefg"); // 17 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.fpe-key must be exactly 16, 24, or 32 UTF-8 bytes (got 17)");
    }

    @Test
    void validate_succeeds_whenFpeKeyIs24BytesAscii() {
        BrumeProperties props = createProperties("0123456789abcdef", "0123456789abcdef01234567"); // 24 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void validate_succeeds_whenFpeKeyIs32BytesAscii() {
        BrumeProperties props = createProperties("0123456789abcdef", "0123456789abcdef0123456789abcdef"); // 32 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void validate_throwsConfigurationException_whenFpeKeyIs33Bytes() {
        BrumeProperties props = createProperties("0123456789abcdef", "0123456789abcdef0123456789abcdefX"); // 33 bytes
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.fpe-key must be exactly 16, 24, or 32 UTF-8 bytes (got 33)");
    }

    @Test
    void validate_countsUtf8Bytes_notCharacters() {
        // "é" is 2 bytes in UTF-8 (0xC3 0xA9)
        // 8 characters × 2 bytes = 16 bytes → should pass
        String eightAccentedChars = "éééééééé"; // 16 UTF-8 bytes, 8 characters
        BrumeProperties props = createProperties("0123456789abcdef", eightAccentedChars);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void validate_throwsConfigurationException_whenUtf8ByteCountIsInvalid() {
        // "é" = 2 bytes × 9 = 18 bytes → invalid (not 16/24/32)
        String nineAccentedChars = "ééééééééé"; // 18 UTF-8 bytes, 9 characters
        BrumeProperties props = createProperties("0123456789abcdef", nineAccentedChars);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.fpe-key must be exactly 16, 24, or 32 UTF-8 bytes (got 18)");
    }

    @Test
    void validate_throwsConfigurationException_whenMaxTargetRowsIsNegative() {
        BrumeProperties props = new BrumeProperties(
                "config.yaml",
                "0123456789abcdef",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                -1L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", "")
        );

        assertThatThrownBy(() -> new BrumePropertiesValidator(props).validate())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.max-target-rows must be >= 0");
    }

    @Test
    void validate_throwsConfigurationException_whenHeapWarningThresholdPercentIsOutOfRange() {
        BrumeProperties props = new BrumeProperties(
                "config.yaml",
                "0123456789abcdef",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                101,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", "")
        );

        assertThatThrownBy(() -> new BrumePropertiesValidator(props).validate())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.heap-warning-threshold-percent must be between 1 and 100");
    }

    // ==================== Output path validation (audit § A4, ADR-0020) ====================

    @Test
    void validate_throwsConfigurationException_whenSinkOutputPathEscapesCwd() {
        BrumeProperties props = withSinkOutputPath("../../host-secret.sql");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.output-path")
                .hasMessageContaining("path traversal rejected");
    }

    @Test
    void validate_throwsConfigurationException_whenJsonReportPathEscapesCwd() {
        BrumeProperties props = withReportPaths("../../host-secret.json", "", "");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.report.json-file")
                .hasMessageContaining("path traversal rejected");
    }

    @Test
    void validate_throwsConfigurationException_whenHtmlReportPathEscapesCwd() {
        BrumeProperties props = withReportPaths("", "../../host-secret.html", "");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.report.html-file")
                .hasMessageContaining("path traversal rejected");
    }

    @Test
    void validate_throwsConfigurationException_whenPlanHtmlReportPathEscapesCwd() {
        BrumeProperties props = withReportPaths("", "", "../../host-secret-plan.html");
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.report.plan-html-file")
                .hasMessageContaining("path traversal rejected");
    }

    @Test
    void validate_skipsSinkPathValidation_whenSinkTypeIsNotDump() {
        // outputPath would normally be rejected, but sink.type=JDBC means it's ignored
        BrumeProperties props = new BrumeProperties(
                "config.yaml",
                "0123456789abcdef",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", ""),
                new SinkProperties(SinkType.JDBC, "../../would-be-rejected", CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT)
        );
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    // ==================== Compression vs extension validation (#4b, ADR-0022) ====================

    @Test
    void validate_throwsConfigurationException_whenGzipPathMissesGzExtension() {
        BrumeProperties props = withSinkCompression("dumps/foo.sql", CompressionType.GZIP);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.compression=GZIP")
                .hasMessageContaining("does not end with '.gz'");
    }

    @Test
    void validate_throwsConfigurationException_whenZstdPathMissesZstExtension() {
        BrumeProperties props = withSinkCompression("dumps/foo.sql", CompressionType.ZSTD);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.compression=ZSTD")
                .hasMessageContaining("does not end with '.zst'");
    }

    @Test
    void validate_throwsConfigurationException_whenNonePathHasGzExtension() {
        BrumeProperties props = withSinkCompression("dumps/foo.sql.gz", CompressionType.NONE);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.compression=NONE")
                .hasMessageContaining("compressed extension");
    }

    @Test
    void validate_throwsConfigurationException_whenNonePathHasZstExtension() {
        BrumeProperties props = withSinkCompression("dumps/foo.sql.zst", CompressionType.NONE);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.compression=NONE")
                .hasMessageContaining("compressed extension");
    }

    @Test
    void validate_throwsConfigurationException_whenZstdPathHasGzExtension() {
        BrumeProperties props = withSinkCompression("dumps/foo.sql.gz", CompressionType.ZSTD);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.compression=ZSTD")
                .hasMessageContaining("does not end with '.zst'");
    }

    @Test
    void validate_succeeds_whenGzipPathEndsWithGzCaseInsensitive() {
        BrumeProperties props = withSinkCompression("dumps/FOO.SQL.GZ", CompressionType.GZIP);
        BrumePropertiesValidator validator = new BrumePropertiesValidator(props);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_succeeds_whenAllValidCompressionExtensionPairs() {
        assertThatCode(() -> new BrumePropertiesValidator(
                withSinkCompression("dumps/foo.sql", CompressionType.NONE)).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> new BrumePropertiesValidator(
                withSinkCompression("dumps/foo.sql.gz", CompressionType.GZIP)).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> new BrumePropertiesValidator(
                withSinkCompression("dumps/foo.sql.zst", CompressionType.ZSTD)).validate())
                .doesNotThrowAnyException();
    }

    // ==================== Helper ====================

    /**
     * Creates a {@link BrumeProperties} instance with the given secrets.
     *
     * @param hmacSecret HMAC secret string (may be null)
     * @param fpeKey     FPE key string (may be null)
     * @return a properties record with dummy values for other fields
     */
    private BrumeProperties createProperties(String hmacSecret, String fpeKey) {
        return new BrumeProperties(
                "config.yaml",
                hmacSecret,
                fpeKey,
                "HmacSHA256",
                "fr",
                0.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", "")
        );
    }

    private BrumeProperties withSinkOutputPath(String outputPath) {
        return new BrumeProperties(
                "config.yaml",
                "0123456789abcdef",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", ""),
                new SinkProperties(SinkType.DUMP, outputPath, CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT)
        );
    }

    private BrumeProperties withSinkCompression(String outputPath, CompressionType compression) {
        return new BrumeProperties(
                "config.yaml",
                "0123456789abcdef",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", ""),
                new SinkProperties(SinkType.DUMP, outputPath, compression,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT)
        );
    }

    private BrumeProperties withReportPaths(String json, String html, String planHtml) {
        return new BrumeProperties(
                "config.yaml",
                "0123456789abcdef",
                "0123456789abcdef",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new ReportProperties(json, html, planHtml),
                new SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT)
        );
    }
}

