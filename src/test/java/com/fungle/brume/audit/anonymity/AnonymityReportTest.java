package com.fungle.brume.audit.anonymity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AnonymityReport#policyViolated()} — the strict-mode gate.
 *
 * <p>Includes the {@code #79e} regression : pre-fix, an empty audit ({@code
 * overallKMin == -1}) was silently treated as "policy OK" in strict mode → CI bypass
 * risk where {@code --strict --k-min N} on an empty schema returned exit 0. Post-fix,
 * strict + empty audit returns {@code true} (violated) so the operator gets exit 8.
 */
class AnonymityReportTest {

    private static AnonymityReport report(boolean strict, long kMin, long overallKMin) {
        return new AnonymityReport(
                "test_schema",
                Instant.parse("2026-05-13T00:00:00Z"),
                List.of(),
                strict,
                kMin,
                overallKMin);
    }

    @Test
    @DisplayName("non-strict mode never violates, regardless of k_min observed")
    void nonStrictNeverViolates() {
        assertThat(report(false, 5, -1).policyViolated()).isFalse();
        assertThat(report(false, 5, 1).policyViolated()).isFalse();
        assertThat(report(false, 5, 100).policyViolated()).isFalse();
    }

    @Test
    @DisplayName("strict + observed k_min below threshold → violated (pre-existing)")
    void strictBelowThresholdIsViolated() {
        assertThat(report(true, 5, 1).policyViolated()).isTrue();
        assertThat(report(true, 5, 4).policyViolated()).isTrue();
    }

    @Test
    @DisplayName("strict + observed k_min meets threshold → not violated (pre-existing)")
    void strictAboveThresholdIsClean() {
        assertThat(report(true, 5, 5).policyViolated()).isFalse();
        assertThat(report(true, 5, 100).policyViolated()).isFalse();
    }

    @Test
    @DisplayName("#79e — strict + empty audit (overallKMin=-1) → violated (CI bypass fix)")
    void strictEmptyAuditIsViolated() {
        // Pre-fix: returned false (policy OK) → exit 0 → silent CI bypass.
        // Post-fix: returns true → exit 8 → operator sees the real problem.
        assertThat(report(true, 5, -1).policyViolated())
                .as("strict mode rejects empty audits — cannot certify k-anonymity on zero rows")
                .isTrue();
        // Threshold value irrelevant when audit is empty.
        assertThat(report(true, 1, -1).policyViolated()).isTrue();
        assertThat(report(true, 1000, -1).policyViolated()).isTrue();
    }

    @Test
    @DisplayName("#79e — non-strict + empty audit → NOT violated (no policy promise made)")
    void nonStrictEmptyAuditIsNotViolated() {
        // The whole point of #79e is the strict-mode bypass. Without --strict the audit
        // is informational ; empty results should not flip to "violated" or the contract
        // is meaningless.
        assertThat(report(false, 5, -1).policyViolated()).isFalse();
    }
}
