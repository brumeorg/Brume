package com.fungle.brume.anonymization;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for deriving a deterministic long seed from a value and a secret using HMAC.
 *
 * <p>Migrated from {@code DataFaker.seedFrom()} in the MVP. The same (value, secret, algorithm)
 * triple always produces the same seed across JVM runs, ensuring deterministic anonymization.
 *
 * <p>This class is intentionally <strong>not</strong> a Spring bean — it is a pure utility
 * with only static methods and no state.
 */
public final class HmacSeeder {

    /** Prevent instantiation. */
    private HmacSeeder() {}

    /**
     * Derives a deterministic {@code long} seed by HMAC-hashing the given value with the secret.
     *
     * <p>The value is lowercased before hashing so that "Alice" and "alice" produce the same seed.
     * The first 8 bytes of the digest are interpreted as a big-endian {@code long}.
     *
     * @param value     the original field value to hash (lowercased internally)
     * @param secret    the HMAC secret key
     * @param algorithm the HMAC algorithm name (e.g. {@code "HmacSHA256"})
     * @return a deterministic long seed derived from the inputs
     * @throws NoSuchAlgorithmException if the algorithm is unavailable on this JVM
     * @throws InvalidKeyException      if the secret key is invalid for the algorithm
     */
    public static long seedFrom(String value, String secret, String algorithm)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(algorithm);
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        mac.init(new SecretKeySpec(secretBytes, algorithm));
        byte[] digest = mac.doFinal(value.toLowerCase().getBytes(StandardCharsets.UTF_8));
        // Use the first 8 bytes of the digest as a long seed
        return ByteBuffer.wrap(digest).getLong();
    }
}

