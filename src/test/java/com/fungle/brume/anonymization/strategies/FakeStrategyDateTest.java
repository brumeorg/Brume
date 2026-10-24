package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FakeStrategy}'s {@link com.fungle.brume.config.model.SemanticType#DATE} path.
 *
 * <p>Two surfaces are covered :
 * <ul>
 *   <li>The default path (no bounds) : output in the adult-DOB range 1935-2008 (#45a).</li>
 *   <li>The custom range path {@link FakeStrategy#anonymizeDate} with explicit {@code minYear}
 *       / {@code maxYear} (#45b) : output within the caller's range, deterministic.</li>
 * </ul>
 *
 * <p>Determinism is the central contract — two invocations with the same input must produce
 * identical output across JVM runs. Covered on both paths.
 */
class FakeStrategyDateTest {

    private FakeStrategy fakeStrategy;
    private SubstitutionDictionary dict;

    @BeforeEach
    void setUp() {
        BrumeProperties props = new BrumeProperties(
                "unused-config-path",
                "test-hmac-secret-32-chars-at-least",
                "test-fpe-key-16b",
                "HmacSHA256",
                "fr",
                0.0,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""));
        fakeStrategy = new FakeStrategy(props);
        dict = new SubstitutionDictionary(props);
    }

    // --- Default path (no bounds) ---------------------------------------------------

    @Test
    @DisplayName("default FAKE(DATE) output lands in the adult-DOB range 1935-2008")
    void defaultDateOutputIsAdultRange() {
        Object out = fakeStrategy.anonymize("alice@test.com",
                com.fungle.brume.config.model.SemanticType.DATE, "k", dict);
        assertThat(out).isInstanceOf(String.class);
        LocalDate parsed = LocalDate.parse((String) out);
        assertThat(parsed).isAfterOrEqualTo(LocalDate.of(1935, 1, 1));
        assertThat(parsed).isBefore(LocalDate.of(2008, 1, 1));
    }

    @Test
    void defaultDateIsDeterministic() {
        Object a = fakeStrategy.anonymize("alice@test.com",
                com.fungle.brume.config.model.SemanticType.DATE, "k", dict);
        Object b = fakeStrategy.anonymize("alice@test.com",
                com.fungle.brume.config.model.SemanticType.DATE, "k", dict);
        assertThat(a).isEqualTo(b);
    }

    // --- Custom range path (#45b) ---------------------------------------------------

    @Test
    @DisplayName("anonymizeDate with explicit child range 2008-2026 produces a child DOB")
    void customChildRange() {
        Object out = fakeStrategy.anonymizeDate("student-42", "students.dob", dict, 2008, 2026);
        assertThat(out).isInstanceOf(String.class);
        LocalDate parsed = LocalDate.parse((String) out);
        assertThat(parsed).isAfterOrEqualTo(LocalDate.of(2008, 1, 1));
        assertThat(parsed).isBefore(LocalDate.of(2026, 1, 1));
    }

    @Test
    void customRangeIsDeterministic() {
        Object a = fakeStrategy.anonymizeDate("kid-7", "students.dob", dict, 2008, 2026);
        Object b = fakeStrategy.anonymizeDate("kid-7", "students.dob", dict, 2008, 2026);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void nullBoundsFallBackToDefaultAdultRange() {
        Object out = fakeStrategy.anonymizeDate("alice", "users.dob", dict, null, null);
        assertThat(out).isInstanceOf(String.class);
        LocalDate parsed = LocalDate.parse((String) out);
        assertThat(parsed).isAfterOrEqualTo(LocalDate.of(1935, 1, 1));
        assertThat(parsed).isBefore(LocalDate.of(2008, 1, 1));
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(fakeStrategy.anonymizeDate(null, "k", dict, 2008, 2026)).isNull();
    }

    @Test
    @DisplayName("narrow 2-year range still produces valid dates across the window")
    void narrowRangeStillCoversWindow() {
        // 60 distinct inputs over a 2-year range — expect at least two distinct years observed
        boolean sawYear0 = false;
        boolean sawYear1 = false;
        for (int i = 0; i < 60; i++) {
            Object out = fakeStrategy.anonymizeDate("row-" + i, "k", dict, 2020, 2022);
            LocalDate d = LocalDate.parse((String) out);
            if (d.getYear() == 2020) sawYear0 = true;
            if (d.getYear() == 2021) sawYear1 = true;
            if (sawYear0 && sawYear1) break;
        }
        assertThat(sawYear0).as("output occasionally lands in 2020").isTrue();
        assertThat(sawYear1).as("output occasionally lands in 2021").isTrue();
    }
}
