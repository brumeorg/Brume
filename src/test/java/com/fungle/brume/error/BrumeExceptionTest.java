package com.fungle.brume.error;

import com.fungle.brume.config.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BrumeException} and its sub-classes.
 *
 * <p>Verifies the constructor matrix, the accessor contract, the exception hierarchy used
 * by the picocli handler for exit-code mapping, and the behavior of pre-existing classes
 * that have been re-parented under {@link BrumeException}.
 */
class BrumeExceptionTest {

    @Test
    @DisplayName("a code is required — passing null throws IllegalArgumentException")
    void codeRequired() {
        assertThatThrownBy(() -> new SchemaException(null, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("accessors expose the code and the optional suggestion")
    void accessorsExposeCodeAndSuggestion() {
        SchemaException ex = new SchemaException(
                BrumeErrorCode.SCHEMA_PGDUMP_FAILED,
                "pg_dump failed (exit 1)",
                "Verify pg_dump is in the path");

        assertThat(ex.code()).isEqualTo(BrumeErrorCode.SCHEMA_PGDUMP_FAILED);
        assertThat(ex.getMessage()).isEqualTo("pg_dump failed (exit 1)");
        assertThat(ex.suggestion()).isEqualTo("Verify pg_dump is in the path");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("constructor without suggestion stores null")
    void noSuggestion() {
        AnonymizationException ex = new AnonymizationException(
                BrumeErrorCode.ANON_FPE_ID_OUT_OF_RANGE, "negative ID");

        assertThat(ex.suggestion()).isNull();
        assertThat(ex.getMessage()).isEqualTo("negative ID");
    }

    @Test
    @DisplayName("cause is preserved through the constructor")
    void causePreserved() {
        Throwable root = new java.io.IOException("disk full");
        WriteException ex = new WriteException(
                BrumeErrorCode.WRITE_DUMP_IO, "sink failed", root);

        assertThat(ex.getCause()).isSameAs(root);
    }

    // -------------------------------------------------------------------------
    // Hierarchy — needed by BrumeExecutionExceptionHandler.exitCodeFor()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ConfigurationException IS-A BrumeException")
    void configurationExceptionIsBrumeException() {
        ConfigurationException ex = new ConfigurationException(
                BrumeErrorCode.CONFIG_HMAC_INVALID, "msg", "fix the hmac config");
        assertThat(ex).isInstanceOf(BrumeException.class);
        assertThat(ex.code()).isEqualTo(BrumeErrorCode.CONFIG_HMAC_INVALID);
    }

    @Test
    @DisplayName("MaxTargetRowsExceededException IS-A ConfigurationException IS-A BrumeException")
    void maxTargetRowsHierarchy() {
        MaxTargetRowsExceededException ex = new MaxTargetRowsExceededException(
                "100 > 50", "tighten filter");

        assertThat(ex).isInstanceOf(ConfigurationException.class);
        assertThat(ex).isInstanceOf(BrumeException.class);
        assertThat(ex.code()).isEqualTo(BrumeErrorCode.CONFIG_MAX_TARGET_ROWS_EXCEEDED);
    }

    @Test
    @DisplayName("SubstitutionDictionaryOverflowException IS-A WriteException IS-A BrumeException")
    void substitutionOverflowHierarchy() {
        com.fungle.brume.anonymization.SubstitutionDictionaryOverflowException ex =
                new com.fungle.brume.anonymization.SubstitutionDictionaryOverflowException(101, 100);

        assertThat(ex).isInstanceOf(WriteException.class);
        assertThat(ex).isInstanceOf(BrumeException.class);
        assertThat(ex.code()).isEqualTo(BrumeErrorCode.WRITE_DICT_OVERFLOW);
        assertThat(ex.suggestion()).contains("brume.substitution-dict.max-entries");
    }

    @Test
    @DisplayName("BatchErrorThresholdExceededException IS-A WriteException IS-A BrumeException")
    void batchErrorThresholdHierarchy() {
        com.fungle.brume.writer.BatchErrorThresholdExceededException ex =
                new com.fungle.brume.writer.BatchErrorThresholdExceededException(
                        "users", 5, 10, 0.5, 0.1);

        assertThat(ex).isInstanceOf(WriteException.class);
        assertThat(ex).isInstanceOf(BrumeException.class);
        assertThat(ex.code()).isEqualTo(BrumeErrorCode.WRITE_BATCH_THRESHOLD);
    }
}
