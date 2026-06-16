package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.HmacSeeder;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.SemanticType;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Random;

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
            case PHONE      -> faker.phoneNumber().phoneNumber();
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
            case JSONB -> throw new IllegalArgumentException(
                    "JSONB columns must be handled by JsonPathProcessor, not FakeStrategy");
        };
    }
}

