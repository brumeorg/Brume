package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DateShiftStrategy} — deterministic signed day shift anchored on
 * an HMAC seed of the input value.
 *
 * <p>No Spring context, no DB — the strategy is a pure function of the input value and
 * the configured {@link BrumeProperties}. Determinism is the central contract and is
 * covered first.
 */
class DateShiftStrategyTest {

    private DateShiftStrategy strategy;
    private BrumeProperties props;

    @BeforeEach
    void setUp() {
        // 30-day max shift ⇒ output in input ± 30 days
        props = new BrumeProperties(
                "unused-config-path",
                "test-hmac-secret-32-chars-at-least",
                "test-fpe-key-16b",
                "HmacSHA256",
                "fr",
                0.0,
                new BrumeProperties.ReportProperties("", "", ""));
        strategy = new DateShiftStrategy(props);
    }

    private int maxDays() {
        return props.dateShift().maxDays();
    }

    @Test
    @DisplayName("same LocalDate input produces identical output across invocations")
    void deterministicForLocalDate() {
        LocalDate input = LocalDate.of(2024, 3, 15);
        Object a = strategy.anonymize(input, null, "irrelevant", null);
        Object b = strategy.anonymize(input, null, "irrelevant", null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(strategy.anonymize(null, null, "k", null)).isNull();
    }

    @Test
    @DisplayName("LocalDate output stays within ±max-days of input")
    void shiftWithinRangeForLocalDate() {
        LocalDate input = LocalDate.of(2024, 3, 15);
        LocalDate out = (LocalDate) strategy.anonymize(input, null, "k", null);
        long delta = ChronoUnit.DAYS.between(input, out);
        assertThat(Math.abs(delta)).isLessThanOrEqualTo(maxDays());
    }

    @Test
    void shiftIsSignedBothWays() {
        // Scan a handful of different seeds to ensure we see both negative and positive
        // deltas — otherwise the floorMod math would be subtly broken.
        boolean sawPositive = false;
        boolean sawNegative = false;
        for (int d = 1; d <= 60; d++) {
            LocalDate in = LocalDate.of(2024, 1, 1).plusDays(d);
            LocalDate out = (LocalDate) strategy.anonymize(in, null, "k", null);
            long delta = ChronoUnit.DAYS.between(in, out);
            if (delta > 0) sawPositive = true;
            if (delta < 0) sawNegative = true;
            if (sawPositive && sawNegative) break;
        }
        assertThat(sawPositive).as("at least one positive shift seen").isTrue();
        assertThat(sawNegative).as("at least one negative shift seen").isTrue();
    }

    @Test
    void localDateTimePreservesTimeComponent() {
        LocalDateTime input = LocalDateTime.of(2024, 3, 15, 14, 30, 45);
        LocalDateTime out = (LocalDateTime) strategy.anonymize(input, null, "k", null);
        // Hour/minute/second must be untouched — shift is day-granular
        assertThat(out.getHour()).isEqualTo(14);
        assertThat(out.getMinute()).isEqualTo(30);
        assertThat(out.getSecond()).isEqualTo(45);
    }

    @Test
    void offsetDateTimePreservesOffset() {
        OffsetDateTime input = OffsetDateTime.of(2024, 3, 15, 14, 30, 0, 0, ZoneOffset.ofHours(2));
        OffsetDateTime out = (OffsetDateTime) strategy.anonymize(input, null, "k", null);
        assertThat(out.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void sqlDateProducesSqlDate() {
        java.sql.Date input = java.sql.Date.valueOf(LocalDate.of(2024, 3, 15));
        Object out = strategy.anonymize(input, null, "k", null);
        assertThat(out).isInstanceOf(java.sql.Date.class);
    }

    @Test
    void sqlTimestampProducesSqlTimestamp() {
        Timestamp input = Timestamp.valueOf(LocalDateTime.of(2024, 3, 15, 10, 0));
        Object out = strategy.anonymize(input, null, "k", null);
        assertThat(out).isInstanceOf(Timestamp.class);
    }

    @Test
    void instantPreservesType() {
        Instant input = Instant.parse("2024-03-15T10:00:00Z");
        Object out = strategy.anonymize(input, null, "k", null);
        assertThat(out).isInstanceOf(Instant.class);
    }

    @Test
    void stringIsoDateReturnsStringIsoDate() {
        Object out = strategy.anonymize("2024-03-15", null, "k", null);
        assertThat(out).isInstanceOf(String.class);
        // Round-trip parse to confirm it's still an ISO date
        LocalDate parsed = LocalDate.parse((String) out);
        long delta = Math.abs(ChronoUnit.DAYS.between(LocalDate.of(2024, 3, 15), parsed));
        assertThat(delta).isLessThanOrEqualTo(maxDays());
    }

    @Test
    void stringIsoDateTimeReturnsStringIsoDateTime() {
        Object out = strategy.anonymize("2024-03-15T10:00:00", null, "k", null);
        assertThat(out).isInstanceOf(String.class);
        // LocalDateTime.toString() may elide trailing ":00" seconds — accept both forms.
        assertThat((String) out).containsPattern("T10:00(:00)?");
        // Round-trip to confirm shape and shift bound
        LocalDateTime parsed = LocalDateTime.parse((String) out);
        long delta = Math.abs(ChronoUnit.DAYS.between(LocalDateTime.of(2024, 3, 15, 10, 0, 0), parsed));
        assertThat(delta).isLessThanOrEqualTo(maxDays());
    }

    @Test
    void sqlStyleStringWithSpaceSeparatorIsAccepted() {
        Object out = strategy.anonymize("2024-03-15 10:00:00", null, "k", null);
        assertThat(out).isInstanceOf(String.class);
    }

    @Test
    void unsupportedTypeThrowsClearError() {
        assertThatThrownBy(() -> strategy.anonymize(42, null, "k", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATE_SHIFT")
                .hasMessageContaining("Integer");
    }

    @Test
    @DisplayName("SubstitutionDictionary is never consulted — HMAC-self-consistent")
    void doesNotUseDictionary() {
        // Pass a null dictionary ; a correct implementation never dereferences it.
        LocalDate input = LocalDate.of(2024, 3, 15);
        Object out = strategy.anonymize(input, null, "k", (SubstitutionDictionary) null);
        assertThat(out).isNotNull();
    }
}
