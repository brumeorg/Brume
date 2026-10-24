package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.HmacSeeder;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.SemanticType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Anonymization strategy that applies a deterministic signed day shift to a temporal value.
 *
 * <p>The shift magnitude is drawn from {@code [-maxDays, +maxDays]} where
 * {@code maxDays} is configured by {@code brume.date-shift.max-days} (default 30).
 * The offset is derived from an HMAC of the input value — the same input always yields
 * the same shifted output across JVM runs, provided the same secret.
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li><b>Deterministic</b> — same input + same secret → same output.</li>
 *   <li><b>Order-preserving within the window</b> — two source dates less than
 *       {@code 2·maxDays} apart may swap order, which is intentional : full ordering
 *       preservation would make the shift trivially reversible by sorting.</li>
 *   <li><b>Cohort-preserving</b> — ages, event spacing, and seasonality remain within
 *       {@code ±maxDays} of truth.</li>
 *   <li><b>Non-reversible</b> without the HMAC secret.</li>
 * </ul>
 *
 * <h2>Supported input types</h2>
 * {@link java.time.LocalDate}, {@link java.time.LocalDateTime}, {@link java.time.OffsetDateTime},
 * {@link java.time.Instant}, {@link java.sql.Date}, {@link java.sql.Timestamp}, and
 * {@code String} (ISO-8601). Output type matches input type to preserve the target column type.
 *
 * <p>Does NOT consult {@link SubstitutionDictionary} — the shift is self-consistent via HMAC,
 * so two tables with the same date value naturally get the same shifted output without a
 * cross-table lookup.
 */
@Component
public final class DateShiftStrategy implements AnonymizationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DateShiftStrategy.class);

    private final BrumeProperties brumeProperties;

    public DateShiftStrategy(BrumeProperties brumeProperties) {
        this.brumeProperties = brumeProperties;
    }

    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        if (value == null) {
            return null;
        }
        long shiftDays = computeShiftDays(value.toString());
        return switch (value) {
            case LocalDate ld -> ld.plusDays(shiftDays);
            case LocalDateTime ldt -> ldt.plusDays(shiftDays);
            case OffsetDateTime odt -> odt.plusDays(shiftDays);
            case Instant inst -> inst.plus(java.time.Duration.ofDays(shiftDays));
            case java.sql.Date sd -> java.sql.Date.valueOf(sd.toLocalDate().plusDays(shiftDays));
            case Timestamp ts -> Timestamp.valueOf(ts.toLocalDateTime().plusDays(shiftDays));
            case String s -> shiftString(s, shiftDays);
            default -> throw new IllegalArgumentException(
                    "DATE_SHIFT does not support type " + value.getClass().getName()
                            + " — expected a java.time or java.sql date/timestamp type, or an ISO string.");
        };
    }

    /**
     * Parses an ISO-8601 date or date-time string, applies the shift, and returns the
     * same representation shape (date vs date-time). Falls back to date if the string
     * has no time component.
     */
    private static Object shiftString(String s, long shiftDays) {
        String trimmed = s.trim();
        if (trimmed.contains("T") || trimmed.contains(" ")) {
            // ISO date-time with 'T' or SQL-style space separator
            try {
                return LocalDateTime.parse(trimmed.replace(' ', 'T')).plusDays(shiftDays).toString();
            } catch (java.time.format.DateTimeParseException e) {
                // offset-datetime like "2024-03-15T10:00:00+02:00"
                return OffsetDateTime.parse(trimmed).plusDays(shiftDays).toString();
            }
        }
        return LocalDate.parse(trimmed).plusDays(shiftDays).toString();
    }

    /**
     * Derives a signed day offset in {@code [-maxDays, +maxDays]} from the HMAC seed of
     * the input value. Uses {@link Math#floorMod} so the sign of the seed does not matter —
     * the full {@code 2·maxDays + 1} range is covered.
     */
    private long computeShiftDays(String rawInput) {
        int maxDays = brumeProperties.dateShift().maxDays();
        long seed;
        try {
            seed = HmacSeeder.seedFrom(rawInput, brumeProperties.hmacSecret(), brumeProperties.hmacAlgorithm());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC seeding failed for DATE_SHIFT — using fallback (hashCode)", e);
            seed = rawInput.hashCode();
        }
        long range = 2L * maxDays + 1L;
        return Math.floorMod(seed, range) - maxDays;
    }
}
