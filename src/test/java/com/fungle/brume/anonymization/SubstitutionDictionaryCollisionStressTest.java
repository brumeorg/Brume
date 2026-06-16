package com.fungle.brume.anonymization;

import com.fungle.brume.config.BrumeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in stress test for the 64-bit truncated key collision rate (B2 critère :
 * "Test stress : 10M valeurs distinctes, 0 collision détectée").
 *
 * <p>Disabled by default to keep the regular suite fast. Enable explicitly with
 * {@code -Dbrume.stress.enabled=true -Dtest=SubstitutionDictionaryCollisionStressTest}.
 *
 * <p>Generates 10M distinct values under a single semantic key with a fixed seed
 * (the value's index) and asserts the dictionary stores exactly 10M entries —
 * any collision would leave one entry under-counted.
 */
class SubstitutionDictionaryCollisionStressTest {

    private static final long ENTRIES = 10_000_000L;

    @Test
    @DisplayName("10M distinct values: zero 64-bit hash collisions on a fixed seed")
    @EnabledIfSystemProperty(named = "brume.stress.enabled", matches = "true")
    void tenMillionEntriesNoCollision() {
        var substDictProps = new BrumeProperties.SubstitutionDictProperties(ENTRIES);
        var reportProps = new BrumeProperties.ReportProperties("", "", "");
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "stress-secret-1234", "stress-secret-1234",
                "HmacSHA256", "fr", 0.0, substDictProps, reportProps);
        SubstitutionDictionary dict = new SubstitutionDictionary(props);

        for (long i = 0; i < ENTRIES; i++) {
            String real = "value-" + i;
            // Generator returns a tiny placeholder to keep memory bounded
            dict.getOrCreate("stress_key", real, () -> "x");
        }

        long size = dict.size();
        if (size != ENTRIES) {
            throw new AssertionError(
                    "Expected " + ENTRIES + " entries (one per distinct real value), got " + size
                            + " — implies " + (ENTRIES - size) + " 64-bit hash collision(s).");
        }
    }
}
