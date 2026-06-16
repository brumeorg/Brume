package com.fungle.brume.anonymization;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility class for computing HMAC digests in hex format.
 *
 * <p>Provides a single reusable method {@link #hex(String, String, String)} to compute
 * HMAC digests with any algorithm supported by the JDK (HmacSHA256, HmacSHA512, HmacSHA1).
 *
 * <p>This class is used by {@link com.fungle.brume.anonymization.strategies.HashStrategy}
 * and {@link SubstitutionDictionary} to ensure consistent HMAC computation across the codebase.
 *
 * <p>Thread safety: all methods are static and stateless.
 */
public final class Hmacs {

    /**
     * Private constructor — this class only contains static utility methods.
     */
    private Hmacs() {}

    /**
     * Computes the HMAC of the given value using the specified secret and algorithm,
     * and returns the result as a lowercase hex string.
     *
     * @param secret    the HMAC secret key (UTF-8 encoded)
     * @param algorithm the HMAC algorithm name (e.g. "HmacSHA256", "HmacSHA512", "HmacSHA1")
     * @param value     the value to hash (UTF-8 encoded)
     * @return the HMAC digest as a lowercase hex string
     * @throws IllegalStateException if the algorithm is unavailable or the secret key is invalid
     */
    public static String hex(String secret, String algorithm, String value) {
        return HexFormat.of().formatHex(digest(secret, algorithm, value));
    }

    /**
     * Computes the HMAC of the given value and returns the first 8 bytes packed into a
     * {@code long} (big-endian). Suitable as a non-cryptographic dictionary key — collisions
     * are statistically rare enough for in-memory substitution maps (cf.
     * {@link com.fungle.brume.anonymization.SubstitutionDictionary}, B2 critère).
     *
     * <p>The key remains cryptographically derived (HMAC of the input under the configured
     * secret); only the index storage is truncated to 64 bits. Reversing this key still
     * requires breaking the underlying HMAC.
     *
     * @param secret    HMAC secret (UTF-8 encoded)
     * @param algorithm HMAC algorithm name (must produce ≥ 8 bytes — true for all standard HMAC variants)
     * @param value     value to hash (UTF-8 encoded)
     * @return the first 8 bytes of the digest as a big-endian {@code long}
     */
    /**
     * Returns a short, deterministic fingerprint suitable for log correlation.
     *
     * <p>Computes the HMAC of the value and truncates the lowercase hex output to its first
     * 8 characters. Same input + same secret → same fingerprint, so a value flowing through
     * multiple log statements can be traced without ever exposing the value itself.
     *
     * <p>Collision rate is 1 / 16⁸ (~4.3 × 10⁻¹⁰) — fine for log corrélation; do not use
     * this as a uniqueness key in any persistent or security-critical context.
     *
     * <p>Used by {@link com.fungle.brume.config.SecretMask} convention and by debug logs
     * across {@code anonymization} and {@code extraction} packages (cf. ADR-0025).
     *
     * @param secret    HMAC secret (UTF-8 encoded)
     * @param algorithm HMAC algorithm name
     * @param value     value to fingerprint (UTF-8 encoded); a {@code null} input yields the
     *                  literal string {@code "null"} so callers do not need a null guard
     * @return 8 lowercase hex characters
     */
    public static String fingerprint(String secret, String algorithm, String value) {
        String safe = value == null ? "null" : value;
        return hex(secret, algorithm, safe).substring(0, 8);
    }

    public static long longKey(String secret, String algorithm, String value) {
        byte[] digest = digest(secret, algorithm, value);
        if (digest.length < 8) {
            // Programming error: a Brume contributor wired an HMAC algorithm whose digest is
            // too short. Not an operator-facing condition — keep as IllegalStateException.
            throw new IllegalStateException(
                    "HMAC algorithm '" + algorithm + "' produced a digest of " + digest.length
                            + " bytes; longKey requires at least 8.");
        }
        return ByteBuffer.wrap(digest, 0, 8).getLong();
    }

    private static byte[] digest(String secret, String algorithm, String value) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    algorithm
            );
            mac.init(keySpec);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new com.fungle.brume.config.ConfigurationException(
                    com.fungle.brume.error.BrumeErrorCode.CONFIG_HMAC_INVALID,
                    "HMAC algorithm '" + algorithm + "' is unavailable on this JVM.",
                    "Pick a JDK-supported algorithm in brume.hmac-algorithm (HmacSHA256 default, "
                            + "HmacSHA512, HmacSHA1).",
                    e);
        } catch (InvalidKeyException e) {
            throw new com.fungle.brume.config.ConfigurationException(
                    com.fungle.brume.error.BrumeErrorCode.CONFIG_HMAC_INVALID,
                    "HMAC secret rejected by algorithm '" + algorithm + "'.",
                    "Verify brume.hmac-secret length (>= 16 UTF-8 bytes recommended).",
                    e);
        }
    }
}

