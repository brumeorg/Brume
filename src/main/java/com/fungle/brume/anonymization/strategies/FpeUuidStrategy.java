package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.SemanticType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Anonymization strategy for UUID columns using HMAC-based deterministic derivation.
 *
 * <p>The original UUID is hashed with HMAC (same secret and algorithm as {@code FakeStrategy}),
 * and the first 16 bytes of the digest are used to build a new UUID with version 4 and
 * variant 2 bits forced, making the output indistinguishable from a randomly-generated UUID.
 *
 * <p>Properties:
 * <ul>
 *   <li><b>Format-preserving</b>: UUID in → UUID out (returned as {@link UUID} — JDBC binds
 *       it directly to PostgreSQL {@code uuid} columns).</li>
 *   <li><b>Deterministic</b>: same input + same secret → same output across JVM runs.</li>
 *   <li><b>Non-reversible</b>: HMAC is a one-way function; recovering the original UUID
 *       requires knowing the secret.</li>
 *   <li><b>FK-safe</b>: FK columns pointing to a {@code FPE_UUID} primary key must also
 *       declare {@code FPE_UUID} — the same UUID mapping is applied everywhere.</li>
 *   <li><b>Case-insensitive</b>: the input is lowercased before hashing so that
 *       {@code "550E8400-..."} and {@code "550e8400-..."} produce the same fake UUID.</li>
 * </ul>
 *
 * <p>No additional dependencies — uses only the JDK's {@code javax.crypto.Mac} and
 * {@link UUID} APIs.
 */
@Component
public final class FpeUuidStrategy implements AnonymizationStrategy {

    private static final Logger log = LoggerFactory.getLogger(FpeUuidStrategy.class);

    private final BrumeProperties brumeProperties;

    /**
     * Creates a new {@code FpeUuidStrategy} backed by the given Brume configuration.
     *
     * @param brumeProperties Brume runtime configuration (provides HMAC secret and algorithm)
     */
    public FpeUuidStrategy(BrumeProperties brumeProperties) {
        this.brumeProperties = brumeProperties;
    }

    /**
     * Anonymizes a UUID value deterministically.
     *
     * <p>If {@code value} is {@code null}, returns {@code null}.
     * The input is normalized to lowercase before hashing, so UUID values that differ
     * only in hex-digit case produce the same fake UUID.
     *
     * @param value       the original UUID value (as a {@link String} or any
     *                    {@code toString()}-able type); may be {@code null}
     * @param type        the semantic type (ignored — FPE_UUID is type-agnostic)
     * @param semanticKey the semantic key (ignored — HMAC determinism is self-contained)
     * @param dict        the substitution dictionary (not consulted — HMAC provides
     *                    intrinsic determinism and consistency)
     * @return the anonymized UUID as a {@link UUID} instance so that JDBC can bind it
     *         directly to a {@code uuid} PostgreSQL column; {@code null} if input is {@code null}
     */
    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        if (value == null) {
            return null;
        }
        return anonymizeUuid(value.toString().toLowerCase());
    }

    /**
     * Derives a deterministic UUID from the input string using HMAC.
     *
     * <p>The first 16 bytes of the HMAC-SHA256 digest are reinterpreted as a UUID with
     * version 4 (bits 12–15 of byte 6 = {@code 0100}) and variant 2
     * (bits 6–7 of byte 8 = {@code 10}) bits forced, following RFC 4122.
     *
     * @param normalized the lowercased original value
     * @return a deterministic version-4 UUID derived from the HMAC digest
     */
    private UUID anonymizeUuid(String normalized) {
        try {
            Mac mac = Mac.getInstance(brumeProperties.hmacAlgorithm());
            byte[] secretBytes = brumeProperties.hmacSecret().getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(secretBytes, brumeProperties.hmacAlgorithm()));
            byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));

            // Force RFC 4122 version 4 (random UUID) bit pattern on the first 16 digest bytes:
            //   byte 6: clear bits 4-7, set bit 6 (version = 0100)
            //   byte 8: clear bits 6-7, set bit 7 (variant = 10xx)
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x40);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);

            ByteBuffer bb = ByteBuffer.wrap(digest, 0, 16);
            return new UUID(bb.getLong(), bb.getLong());

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC failed for UUID anonymization — using fallback (nameUUIDFromBytes)", e);
            // Fallback: UUID.nameUUIDFromBytes is deterministic but uses MD5 internally.
            // This path is theoretically unreachable on any standard JVM (HmacSHA256 is mandatory).
            return UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8));
        }
    }
}

