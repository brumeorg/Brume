package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the {@link ConfigurationException} contract introduced in #17b / ADR-0027 Q3.B:
 * the structured constructors must reject a {@code null} or blank {@code suggestion} at
 * construction time, so the {@code [CODE] message → suggestion} layout from A10 cannot be
 * silently bypassed.
 */
class ConfigurationExceptionTest {

    @Test
    @DisplayName("structured constructor accepts a non-blank suggestion")
    void structured_acceptsNonBlankSuggestion() {
        ConfigurationException ex = new ConfigurationException(
                BrumeErrorCode.CONFIG_HMAC_INVALID,
                "something is off",
                "Try this concrete fix.");

        assertThat(ex.code()).isEqualTo(BrumeErrorCode.CONFIG_HMAC_INVALID);
        assertThat(ex.getMessage()).isEqualTo("something is off");
        assertThat(ex.suggestion()).isEqualTo("Try this concrete fix.");
    }

    @Test
    @DisplayName("structured constructor rejects a null suggestion")
    void structured_rejectsNullSuggestion() {
        assertThatThrownBy(() -> new ConfigurationException(
                BrumeErrorCode.CONFIG_HMAC_INVALID, "msg", (String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank suggestion")
                .hasMessageContaining("#17b");
    }

    @Test
    @DisplayName("structured constructor rejects a blank suggestion")
    void structured_rejectsBlankSuggestion() {
        assertThatThrownBy(() -> new ConfigurationException(
                BrumeErrorCode.CONFIG_HMAC_INVALID, "msg", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank suggestion");
    }

    @Test
    @DisplayName("structured constructor with cause also enforces the non-blank suggestion")
    void structuredWithCause_rejectsNullSuggestion() {
        Throwable cause = new RuntimeException("io failure");
        assertThatThrownBy(() -> new ConfigurationException(
                BrumeErrorCode.CONFIG_HMAC_INVALID, "msg", null, cause))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank suggestion");
    }

    @Test
    @DisplayName("structured constructor with cause keeps the cause when the suggestion is valid")
    void structuredWithCause_preservesCause() {
        Throwable cause = new RuntimeException("io failure");
        ConfigurationException ex = new ConfigurationException(
                BrumeErrorCode.CONFIG_FILE_PARSE_ERROR,
                "Failed to parse config",
                "Re-check YAML syntax.",
                cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.suggestion()).isEqualTo("Re-check YAML syntax.");
    }
}
