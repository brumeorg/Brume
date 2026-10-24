package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.HmacSeeder;
import com.fungle.brume.anonymization.Hmacs;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.SemanticType;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Anonymization strategy that generates deterministic fake data using {@link Faker}.
 *
 * <p>Determinism is achieved by seeding the Faker RNG with an HMAC of the original value
 * (via {@link HmacSeeder}). The same original value always produces the same fake output
 * across JVM runs, provided the same secret and algorithm are configured.
 *
 * <p>Cross-table consistency is ensured through the {@link SubstitutionDictionary}: if the
 * same semantic key and real value appear in multiple tables, they produce the same fake value.
 *
 * <h2>Collision mitigation</h2>
 * <ul>
 *   <li><b>Case normalization</b>: the original value is lowercased before both the dictionary
 *       key computation and the seed derivation, so {@code "Alice@Test.com"} and
 *       {@code "alice@test.com"} always produce the same fake output.</li>
 *   <li><b>EMAIL uniqueness</b>: a 6-char hex suffix derived from the seed is appended to the
 *       username ({@code user.a3f1c2@domain.com}), making email collisions from Faker's finite
 *       dictionary effectively impossible.</li>
 *   <li><b>IP_ADDRESS uniqueness</b>: the four octets are derived directly from the seed bits
 *       rather than from Faker's limited pool, guaranteeing distinct IPs for distinct values.</li>
 *   <li><b>IBAN formatting</b>: spaces emitted by Datafaker are stripped to fit {@code VARCHAR(34)}.</li>
 * </ul>
 *
 * <p>The generated data type depends on the {@link SemanticType}:
 * <ul>
 *   <li>{@code EMAIL} → fake email address with seed-derived suffix</li>
 *   <li>{@code FIRST_NAME} → fake first name</li>
 *   <li>{@code LAST_NAME} → fake last name</li>
 *   <li>{@code PHONE} → fake phone number</li>
 *   <li>{@code ADDRESS} → fake full street address</li>
 *   <li>{@code IBAN} → fake IBAN (spaces stripped)</li>
 *   <li>{@code IP_ADDRESS} → fake IPv4 address derived from seed bits</li>
 *   <li>{@code JSONB} → throws — JSONB columns must use {@code JsonPathProcessor} instead</li>
 * </ul>
 */
@Component
public final class FakeStrategy implements AnonymizationStrategy {

    private static final Logger log = LoggerFactory.getLogger(FakeStrategy.class);

    private final BrumeProperties brumeProperties;

    /**
     * Types drawn from a small vocabulary (≈ 5 000 first names in Datafaker) : on a UNIQUE
     * column, distinct real values collide after a few dozen rows, so they get a uniqueness
     * suffix (#29a, {@link #withUniqueSuffix}). EMAIL and IP_ADDRESS already carry seed-derived
     * entropy and keep their format.
     */
    public static final Set<SemanticType> SMALL_VOCABULARY =
            EnumSet.of(SemanticType.FIRST_NAME, SemanticType.LAST_NAME, SemanticType.ADDRESS);

    private static final int UNIQUE_SUFFIX_HEX_CHARS = 16;

    /**
     * Creates a new {@code FakeStrategy} backed by the given Brume configuration.
     *
     * @param brumeProperties Brume runtime configuration (provides secret, algorithm and locale)
     */
    public FakeStrategy(BrumeProperties brumeProperties) {
        this.brumeProperties = brumeProperties;
    }

    /**
     * Returns a deterministic fake value for the given original value and semantic type.
     *
     * <p>If {@code value} is {@code null}, returns {@code null}.
     * The input is normalized to lowercase before key derivation so that values that differ
     * only in case produce the same fake output consistently.
     * Cross-table consistency is maintained via the {@link SubstitutionDictionary}.
     *
     * @param value       the original field value; may be {@code null}
     * @param type        the semantic type determining what kind of fake data to generate
     * @param semanticKey a logical key grouping linked columns across tables
     * @param dict        the cross-table substitution dictionary
     * @return a deterministic fake string, or {@code null} if input is {@code null}
     * @throws IllegalArgumentException if {@code type} is {@link SemanticType#JSONB}
     */
    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        if (value == null) {
            return null;
        }
        // FIX 1 — normalize to lowercase BEFORE buildKey so that "Alice@Test.com" and
        // "alice@test.com" produce the same dictKey (and therefore the same fake output).
        // HmacSeeder already lowercases internally, so seed derivation was already consistent;
        // this fix closes the gap on the dictionary-key side.
        String normalized = value.toString().toLowerCase();
        return dict.getOrCreate(semanticKey, normalized, () -> generate(normalized, type));
    }

    /**
     * Makes a fake value safe for a UNIQUE column (#29a) : appends {@code "."} and 64 bits (16 hex
     * chars) of an HMAC of the <em>exact</em> original value. Distinct real values — which a
     * UNIQUE source column guarantees — then give distinct fakes (collision odds ≈ 2⁻⁶⁴ per
     * pair). No case folding : the constraint sees {@code "Alice"} and {@code "alice"} as two
     * values. The HMAC input is prefixed so the suffix cannot be matched against a HASH of the
     * same value in another column. The fake part is cut so the result fits {@code maxLength}.
     *
     * @param fake      the fake value produced by {@link #anonymize}
     * @param original  the original value (non-null)
     * @param maxLength the column's declared length, or {@code null} when unbounded
     * @return the fake value with its uniqueness suffix
     */
    public String withUniqueSuffix(String fake, Object original, Integer maxLength) {
        String suffix = "." + Hmacs.hex(brumeProperties.hmacSecret(), brumeProperties.hmacAlgorithm(),
                "brume:unique:" + original).substring(0, UNIQUE_SUFFIX_HEX_CHARS);
        if (maxLength == null || fake.length() + suffix.length() <= maxLength) {
            return fake + suffix;
        }
        if (maxLength <= suffix.length()) {
            return suffix.substring(1, Math.min(suffix.length(), maxLength + 1));
        }
        return fake.substring(0, maxLength - suffix.length()) + suffix;
    }

    /**
     * Phone numbers (#29d) : Datafaker draws area / prefix codes from short lists and gives only
     * a few million distinct numbers — 1.6 % of 200 000 values collided, rows lost on a UNIQUE
     * phone column. The format and the first digit are kept, every other digit is drawn from the
     * 64-bit seed. Still deterministic : the same value under the same secret gives the same
     * number, in every table.
     */
    static String redrawDigits(String phone, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        StringBuilder out = new StringBuilder(phone.length());
        boolean first = true;
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(first ? c : (char) ('0' + random.nextInt(10)));
                first = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Derives the HMAC seed and delegates to {@link #generateWithSeed}.
     *
     * @param original the normalized (lowercased) original field value
     * @param type     the semantic type
     * @return a fake string matching the semantic type
     */
    private String generate(String original, SemanticType type) {
        try {
            long seed = HmacSeeder.seedFrom(original, brumeProperties.hmacSecret(), brumeProperties.hmacAlgorithm());
            return generateWithSeed(seed, type);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC seeding failed — using fallback seed (hashCode)", e);
            // FIX 2 (partial) — fallback now delegates to generateWithSeed so the correct
            // semantic type is always used, even in the error path.
            return generateWithSeed(original.hashCode(), type);
        }
    }

    /**
     * Generates a fake value from an already-computed seed.
     *
     * <p>Extracted to a separate method so the normal path and the fallback path share
     * the same generation logic — the fallback no longer hard-codes EMAIL for all types.
     *
     * <p>Collision mitigations applied here:
     * <ul>
     *   <li><b>EMAIL</b>: a 6-char hex suffix derived from the seed is appended to the
     *       username, making the output space effectively 2⁶⁴ even though Faker's username
     *       dictionary is finite.</li>
     *   <li><b>IP_ADDRESS</b>: four octets are packed directly from the seed bits, bypassing
     *       Faker's limited IP pool entirely.</li>
     *   <li><b>IBAN</b>: spaces emitted by Datafaker are stripped.</li>
     *   <li><b>PHONE</b>: Datafaker's format is kept, its digits are redrawn from the seed
     *       (see {@link #redrawDigits}).</li>
     * </ul>
     *
     * @param seed the 64-bit seed derived from the original value
     * @param type the semantic type
     * @return a fake string matching the semantic type
     */
    private String generateWithSeed(long seed, SemanticType type) {
        Faker faker = new Faker(Locale.of(brumeProperties.fakerLocale()), new Random(seed));
        return switch (type) {
            // FIX 3 — EMAIL: append a 6-char hex suffix derived from the seed.
            // Faker's username dictionary is finite (~few thousand entries), so two different
            // seeds can produce the same username. The suffix makes the full address unique
            // across the seed space (2⁶⁴), preventing UNIQUE-constraint collisions on target.
            case EMAIL -> {
                String username = faker.internet().username();
                String domain   = faker.internet().domainName();
                String suffix   = String.format("%06x", seed & 0xFFFFFFL);
                yield username + "." + suffix + "@" + domain;
            }
            case FIRST_NAME -> faker.name().firstName();
            case LAST_NAME  -> faker.name().lastName();
            case PHONE      -> redrawDigits(faker.phoneNumber().phoneNumber(), seed);
            case ADDRESS    -> faker.address().fullAddress();
            // IBAN: Datafaker may include formatting spaces that exceed VARCHAR(34).
            case IBAN       -> faker.finance().iban().replaceAll("\\s+", "");
            // FIX 3 — IP_ADDRESS: pack seed bits directly into four octets.
            // Faker's IP pool is limited; deriving the address from the seed guarantees
            // that distinct seeds produce distinct IPs.
            // Octet ranges: first octet 1-223 (avoids 0.x and 224+ multicast/reserved),
            // last octet 1-254 (avoids .0 network and .255 broadcast).
            case IP_ADDRESS -> {
                int a = 1   + Math.floorMod(seed >> 24, 223);
                int b =       Math.floorMod(seed >> 16, 256);
                int c =       Math.floorMod(seed >> 8,  256);
                int d = 1   + Math.floorMod(seed,       254);
                yield a + "." + b + "." + c + "." + d;
            }
            // DATE : plausible adult DOB range anchored at a fixed epoch so output is
            // reproducible across runs (unlike LocalDate.now() which would drift daily).
            // Default range 1935 → 2008 covers 18-91 years old on 2026 scans. Overridable
            // per-column via minYear / maxYear in ColumnConfig (#45b) — see anonymizeDate.
            case DATE -> generateDateIsoString(seed, DEFAULT_DATE_MIN_YEAR, DEFAULT_DATE_MAX_YEAR);
            case JSONB -> throw new IllegalArgumentException(
                    "JSONB columns must be handled by JsonPathProcessor, not FakeStrategy");
        };
    }

    /**
     * Anonymizes a date value with a caller-provided year range (#45b).
     *
     * <p>Entry point used by {@link com.fungle.brume.anonymization.AnonymizationEngine} when a
     * column declares explicit {@code min_year} / {@code max_year} in {@code brume.yml} — mostly
     * for datasets whose DOBs don't fit the adult default (pediatric apps, student directories, ...).
     * {@code null} bounds fall back to the adult-DOB default anchor.
     *
     * <p>Determinism and dictionary semantics are identical to the main {@link #anonymize} path :
     * the dictionary bucket is keyed by {@code (semanticKey, normalized-value)} so two tables
     * sharing a linked column still produce the same shifted date.
     *
     * @param value       the original date value; may be {@code null}
     * @param semanticKey logical key for cross-table consistency
     * @param dict        the substitution dictionary
     * @param minYear     inclusive lower year bound ({@code null} ⇒ default 1935)
     * @param maxYear     exclusive upper year bound ({@code null} ⇒ default 2008)
     * @return a deterministic ISO date string in {@code [minYear-01-01, maxYear-01-01)}
     */
    public Object anonymizeDate(Object value, String semanticKey, SubstitutionDictionary dict,
                                 Integer minYear, Integer maxYear) {
        if (value == null) return null;
        int min = minYear != null ? minYear : DEFAULT_DATE_MIN_YEAR;
        int max = maxYear != null ? maxYear : DEFAULT_DATE_MAX_YEAR;
        String normalized = value.toString().toLowerCase();
        return dict.getOrCreate(semanticKey, normalized, () -> {
            try {
                long seed = HmacSeeder.seedFrom(normalized, brumeProperties.hmacSecret(), brumeProperties.hmacAlgorithm());
                return generateDateIsoString(seed, min, max);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                log.error("HMAC seeding failed for FAKE(DATE) — using fallback seed (hashCode)", e);
                return generateDateIsoString(normalized.hashCode(), min, max);
            }
        });
    }

    /**
     * Pure helper : maps a seed into {@code [minYear-01-01, maxYear-01-01)} using
     * {@link Math#floorMod} so the sign of the seed is irrelevant.
     */
    private static String generateDateIsoString(long seed, int minYear, int maxYear) {
        long start = LocalDate.of(minYear, 1, 1).toEpochDay();
        long end   = LocalDate.of(maxYear, 1, 1).toEpochDay();
        long epochDay = start + Math.floorMod(seed, end - start);
        return LocalDate.ofEpochDay(epochDay).toString();
    }

    /** Default lower year bound for {@link SemanticType#DATE} — 91 years old on 2026 scans. */
    public static final int DEFAULT_DATE_MIN_YEAR = 1935;
    /** Default upper year bound (exclusive) for {@link SemanticType#DATE} — 18 years old on 2026 scans. */
    public static final int DEFAULT_DATE_MAX_YEAR = 2008;
}

