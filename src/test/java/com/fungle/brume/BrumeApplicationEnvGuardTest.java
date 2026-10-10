package com.fungle.brume;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the pre-boot guard added for the "I cloned the repo and ran `brume plan`" trap :
 * {@link BrumeApplication#hasAnyEnvSource(Path)} must detect any one of
 * {@code .env.brume} on disk / {@code BRUME_HMAC_SECRET} in the OS env /
 * {@code brume.hmac-secret} (or {@code BRUME_HMAC_SECRET}) in system properties, and must
 * return false when none is present so {@code guardEnvOrFail} can emit a friendly
 * {@code [CONFIG_ENV_MISSING]} message instead of letting Spring crash on a
 * {@code BindException}. ADR-0047 § consequences.
 */
class BrumeApplicationEnvGuardTest {

    private String originalHmacSysProp;
    private String originalHmacSysPropUpper;

    @BeforeEach
    void clearSysProps() {
        originalHmacSysProp = System.getProperty("brume.hmac-secret");
        originalHmacSysPropUpper = System.getProperty("BRUME_HMAC_SECRET");
        System.clearProperty("brume.hmac-secret");
        System.clearProperty("BRUME_HMAC_SECRET");
    }

    @AfterEach
    void restoreSysProps() {
        if (originalHmacSysProp != null) System.setProperty("brume.hmac-secret", originalHmacSysProp);
        else System.clearProperty("brume.hmac-secret");
        if (originalHmacSysPropUpper != null) System.setProperty("BRUME_HMAC_SECRET", originalHmacSysPropUpper);
        else System.clearProperty("BRUME_HMAC_SECRET");
    }

    @Test
    @DisplayName("empty cwd, no sys prop, no env var → guard reports no source (friendly error path)")
    void noEnvSourceAtAllTriggersGuard(@TempDir Path tmp) {
        // OS env BRUME_HMAC_SECRET is not controlled here. If a dev happens to have it set,
        // the assertion flips — assumeFalse keeps the test honest and skips.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("BRUME_HMAC_SECRET") == null
                        || System.getenv("BRUME_HMAC_SECRET").isBlank(),
                "Skipped — BRUME_HMAC_SECRET is set in the real OS env of this runner.");

        assertThat(BrumeApplication.hasAnyEnvSource(tmp)).isFalse();
    }

    @Test
    @DisplayName("a .env.brume file in the base dir satisfies the guard")
    void dotEnvBrumeFilePresentSatisfiesGuard(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve(".env.brume"), "BRUME_HMAC_SECRET=whatever");

        assertThat(BrumeApplication.hasAnyEnvSource(tmp)).isTrue();
    }

    @Test
    @DisplayName("brume.hmac-secret system property satisfies the guard")
    void hmacSystemPropertySatisfiesGuard(@TempDir Path tmp) {
        System.setProperty("brume.hmac-secret", "injected-via-D-flag-16b");

        assertThat(BrumeApplication.hasAnyEnvSource(tmp)).isTrue();
    }

    @Test
    @DisplayName("BRUME_HMAC_SECRET system property (uppercase shape) also satisfies the guard")
    void uppercaseHmacSystemPropertySatisfiesGuard(@TempDir Path tmp) {
        System.setProperty("BRUME_HMAC_SECRET", "injected-via-D-flag-16b");

        assertThat(BrumeApplication.hasAnyEnvSource(tmp)).isTrue();
    }

    @Test
    @DisplayName("a blank system property does not count as a valid source")
    void blankSystemPropertyDoesNotSatisfyGuard(@TempDir Path tmp) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("BRUME_HMAC_SECRET") == null
                        || System.getenv("BRUME_HMAC_SECRET").isBlank(),
                "Skipped — BRUME_HMAC_SECRET is set in the real OS env of this runner.");
        System.setProperty("brume.hmac-secret", "   ");

        assertThat(BrumeApplication.hasAnyEnvSource(tmp)).isFalse();
    }
}
