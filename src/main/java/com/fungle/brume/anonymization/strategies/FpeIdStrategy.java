package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.SemanticType;
import org.bouncycastle.crypto.fpe.FPEFF1Engine;
import org.bouncycastle.crypto.params.FPEParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Anonymization strategy for numeric ID columns using Format-Preserving Encryption (FPE/FF1).
 *
 * <p>FF1 (NIST SP 800-38G) preserves the numeric format of the original value:
 * a numeric ID in, a numeric ID out. The output length may differ slightly due to
 * the 18-digit padding required by the FF1 minimum domain constraint.
 *
 * <p><strong>Supported range:</strong> [0, 999,999,999,999,999,999] (18 digits, inclusive).
 * Values outside this range or negative IDs will cause an {@link IllegalArgumentException}
 * to be thrown. This is intentional — truncation or modulo creates collisions and breaks
 * FK integrity.
 *
 * <p>The FPE key is derived from {@code brumeProperties.fpeKey()}, which must be exactly
 * 16, 24, or 32 UTF-8 bytes (AES-128/192/256) as validated by
 * {@link com.fungle.brume.config.BrumePropertiesValidator}.
 *
 * <p>This strategy is deterministic — the same input always produces the same encrypted output
 * for the same FPE key. FK integrity is preserved automatically: all FK columns pointing to an
 * encrypted PK are also encrypted with the same key (propagation rule).
 *
 * <p><strong>Thread safety:</strong> a new {@link FPEFF1Engine} instance is created per call to
 * avoid state mutation issues with concurrent virtual threads.
 */
@Component
public final class FpeIdStrategy implements AnonymizationStrategy {

    private static final Logger log = LoggerFactory.getLogger(FpeIdStrategy.class);

    /**
     * FF1 minimum domain requires at least 1,000,000 values (radix^minLen >= 10^6).
     * A PAD_LENGTH of 18 covers values up to 999,999,999,999,999,999 (18 quintillion),
     * which is sufficient for any realistic BIGINT primary key.
     */
    private static final int PAD_LENGTH = 18;

    /**
     * Maximum input value supported by FPE_ID (10^18 - 1).
     * Values exceeding this limit must fail explicitly; truncation is not acceptable.
     */
    private static final long MAX_INPUT = 999_999_999_999_999_999L; // 10^18 - 1

    /** Pre-computed FPE key bytes (16, 24, or 32 bytes for AES-128/192/256). */
    private final byte[] fpeKey;

    /**
     * Creates a new {@code FpeIdStrategy} backed by the given Brume configuration.
     *
     * <p>The FPE key is extracted once during construction; its length is guaranteed to be
     * valid (16, 24, or 32 bytes) by {@link com.fungle.brume.config.BrumePropertiesValidator}.
     *
     * @param brumeProperties Brume runtime configuration (provides the FPE key)
     */
    public FpeIdStrategy(BrumeProperties brumeProperties) {
        this.fpeKey = brumeProperties.fpeKey().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encrypts a numeric ID using FF1/FPE, preserving its numeric character.
     *
     * <p>If {@code value} is {@code null}, returns {@code null}.
     * The input is parsed as a {@code long}, validated to be in the range [0, {@value #MAX_INPUT}],
     * padded to {@value #PAD_LENGTH} digits, encrypted, and returned as a {@code Long}.
     *
     * @param value       the original numeric ID as a string or any {@code toString()}-able type;
     *                    may be {@code null}
     * @param type        the semantic type (ignored — FPE is type-agnostic for numeric values)
     * @param semanticKey the semantic key (ignored — FPE provides intrinsic determinism)
     * @param dict        the substitution dictionary (not consulted — FPE is self-consistent)
     * @return the encrypted numeric ID as a {@code Long}, or {@code null} if input is {@code null}
     * @throws IllegalArgumentException if the value cannot be parsed as a long integer,
     *         is negative, or exceeds {@value #MAX_INPUT}
     */
    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        if (value == null) return null;
        return anonymizeIntFPE(value.toString());
    }

    private Long anonymizeIntFPE(String value) {
        long original;
        try {
            original = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new com.fungle.brume.error.AnonymizationException(
                    com.fungle.brume.error.BrumeErrorCode.ANON_FPE_ID_OUT_OF_RANGE,
                    "FPE_ID expects a numeric value but got '" + value + "'.",
                    "Apply FPE_ID only on integer-typed columns, or switch to HASH for varchar IDs.",
                    e);
        }

        if (original < 0) {
            throw new com.fungle.brume.error.AnonymizationException(
                    com.fungle.brume.error.BrumeErrorCode.ANON_FPE_ID_OUT_OF_RANGE,
                    "FPE_ID does not support negative values (got " + original + ").",
                    "Source row carries a negative ID; pre-filter such rows or use a different strategy.");
        }
        if (original > MAX_INPUT) {
            throw new com.fungle.brume.error.AnonymizationException(
                    com.fungle.brume.error.BrumeErrorCode.ANON_FPE_ID_OUT_OF_RANGE,
                    "FPE_ID value " + original + " exceeds the supported range (max " + MAX_INPUT + ").",
                    "Source row carries an ID beyond 10^18; use HASH or split the column with custom logic.");
        }

        if (original <= Integer.MAX_VALUE) {
            return encryptBinary31(original);
        } else {
            return encryptDecimal18(original);
        }
    }

    /**
     * FF1 en base 2 sur 31 bits — domaine [0, 2^31-1].
     * Garantit que le résultat tient dans un INTEGER PostgreSQL.
     * Un BIGINT avec valeur ≤ INT_MAX passera ici et donnera le même résultat
     * qu'un INTEGER de même valeur — cohérence FK préservée.
     */
    private long encryptBinary31(long original) {
        // Représentation sur 31 bits
        byte[] bits = new byte[31];
        for (int i = 30; i >= 0; i--) {
            bits[i] = (byte) ((original >> (30 - i)) & 1);
        }

        FPEParameters params = new FPEParameters(
                new KeyParameter(fpeKey),
                2,           // radix binaire
                new byte[0]  // tweak vide
        );

        FPEFF1Engine engine = new FPEFF1Engine();
        engine.init(true, params);

        byte[] encrypted = new byte[31];
        engine.processBlock(bits, 0, 31, encrypted, 0);

        // Reconstruction depuis les bits
        long result = 0;
        for (int i = 0; i < 31; i++) {
            result = (result << 1) | (encrypted[i] & 1);
        }
        return result;
    }

    /**
     * FF1 en base 10 sur 18 chiffres — domaine [0, 10^18-1].
     * Pour les BIGINT dont la valeur dépasse INTEGER_MAX.
     */
    private long encryptDecimal18(long original) {
        String input = String.format("%018d", original);

        byte[] digits = new byte[18];
        for (int i = 0; i < 18; i++) {
            digits[i] = (byte) (input.charAt(i) - '0');
        }

        FPEParameters params = new FPEParameters(
                new KeyParameter(fpeKey),
                10,
                new byte[0]
        );

        FPEFF1Engine engine = new FPEFF1Engine();
        engine.init(true, params);

        byte[] encrypted = new byte[18];
        engine.processBlock(digits, 0, 18, encrypted, 0);

        StringBuilder sb = new StringBuilder();
        for (byte d : encrypted) sb.append(d);
        return Long.parseLong(sb.toString());
    }
}

