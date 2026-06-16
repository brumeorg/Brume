package com.fungle.brume.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BrumeProperties.TimeoutsProperties} (#23 / A21, ADR-0033) —
 * defaults match the cadrage (600s statement, 0s total = disabled), and the compact
 * constructor rejects negative values at boot so a misconfigured YAML fails fast with a
 * stack pointing at the offending property rather than firing a phantom timeout mid-run.
 */
class TimeoutsPropertiesTest {

    @Test
    @DisplayName("defaults: 600s statement, 0s total-run (disabled)")
    void defaults_match_cadrage() {
        BrumeProperties.TimeoutsProperties defaults = BrumeProperties.TimeoutsProperties.defaults();
        assertThat(defaults.statementSeconds()).isEqualTo(600);
        assertThat(defaults.totalRunSeconds()).isEqualTo(0);
    }

    @Test
    @DisplayName("negative statement-seconds rejected at construction")
    void negative_statement_rejected() {
        assertThatThrownBy(() -> new BrumeProperties.TimeoutsProperties(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("brume.timeouts.statement-seconds must be >= 0")
                .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("negative total-run-seconds rejected at construction")
    void negative_total_rejected() {
        assertThatThrownBy(() -> new BrumeProperties.TimeoutsProperties(600, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("brume.timeouts.total-run-seconds must be >= 0")
                .hasMessageContaining("-10");
    }

    @Test
    @DisplayName("zero values accepted (disabled semantics)")
    void zero_disables_each_dimension() {
        BrumeProperties.TimeoutsProperties disabled = new BrumeProperties.TimeoutsProperties(0, 0);
        assertThat(disabled.statementSeconds()).isZero();
        assertThat(disabled.totalRunSeconds()).isZero();
    }
}
