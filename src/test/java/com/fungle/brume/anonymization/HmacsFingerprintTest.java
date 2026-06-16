package com.fungle.brume.anonymization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Hmacs#fingerprint(String, String, String)}.
 *
 * <p>Covers the contract used by the no-PII-in-logs initiative (ADR-0025):
 * deterministic, 8-char lowercase hex, null-safe, never reveals the input.
 */
class HmacsFingerprintTest {

    private static final String SECRET = "log-fingerprint-test-secret";
    private static final String ALG = "HmacSHA256";

    @Test
    @DisplayName("returns exactly 8 lowercase hex characters")
    void formatIs8LowerHex() {
        String fp = Hmacs.fingerprint(SECRET, ALG, "alice@example.com");
        assertThat(fp).hasSize(8).matches("[0-9a-f]{8}");
    }

    @Test
    @DisplayName("same input + same secret yields the same fingerprint (deterministic)")
    void deterministic() {
        String a = Hmacs.fingerprint(SECRET, ALG, "alice@example.com");
        String b = Hmacs.fingerprint(SECRET, ALG, "alice@example.com");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("different inputs produce different fingerprints (collision-rare)")
    void differentInputsDiffer() {
        String a = Hmacs.fingerprint(SECRET, ALG, "alice@example.com");
        String b = Hmacs.fingerprint(SECRET, ALG, "bob@example.com");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("different secrets on the same input produce different fingerprints")
    void differentSecretsDiffer() {
        String a = Hmacs.fingerprint("secret-A", ALG, "value");
        String b = Hmacs.fingerprint("secret-B", ALG, "value");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("null input is null-safe and produces a stable fingerprint")
    void nullSafe() {
        String fp = Hmacs.fingerprint(SECRET, ALG, null);
        assertThat(fp).hasSize(8).matches("[0-9a-f]{8}");
        assertThat(fp).isEqualTo(Hmacs.fingerprint(SECRET, ALG, "null")); // stable convention
    }

    @Test
    @DisplayName("output is hex only — would fail loudly if implementation became a substring of the input")
    void outputIsHexOnly() {
        // Use an input made entirely of non-hex characters so a regression where the
        // fingerprint accidentally became a substring of the input would fail this assertion.
        String nonHexInput = "zzz-token-zzz-zzz-zzz";
        String fp = Hmacs.fingerprint(SECRET, ALG, nonHexInput);
        assertThat(fp).matches("[0-9a-f]{8}");
        for (char c : nonHexInput.toCharArray()) {
            if (c != '-') {
                assertThat(fp).doesNotContain(String.valueOf(c));
            }
        }
    }
}
