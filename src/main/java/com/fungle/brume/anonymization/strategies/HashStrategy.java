package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.Hmacs;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.SemanticType;
import org.springframework.stereotype.Component;

/**
 * Anonymization strategy that replaces the value with a one-way HMAC digest.
 *
 * <p>The HMAC is computed using {@code brume.hmac-algorithm} (default: HmacSHA256) with
 * {@code brume.hmac-secret} as the key. The output is a lowercase hex string whose length
 * depends on the algorithm:
 * <ul>
 *   <li>HmacSHA256 → 64 hex characters (32 bytes)</li>
 *   <li>HmacSHA512 → 128 hex characters (64 bytes)</li>
 *   <li>HmacSHA1 → 40 hex characters (20 bytes)</li>
 * </ul>
 *
 * <p>This strategy is <strong>not reversible</strong> — the original value cannot be recovered.
 * Use it for values that need to be consistent within the dataset (same input → same hash)
 * but must never reveal the original.
 *
 * <p><strong>Security note:</strong> the original value is never logged.
 *
 * <p><strong>Breaking change from V0:</strong> older versions used a naïve
 * {@code SHA-256(secret + value)} construction; V1+ uses proper HMAC per RFC 2104.
 */
@Component
public final class HashStrategy implements AnonymizationStrategy {

    private final BrumeProperties brumeProperties;

    /**
     * Creates a new {@code HashStrategy} using the given Brume configuration.
     *
     * @param brumeProperties Brume runtime configuration (provides the HMAC secret and algorithm)
     */
    public HashStrategy(BrumeProperties brumeProperties) {
        this.brumeProperties = brumeProperties;
    }

    /**
     * Returns the HMAC digest of {@code value} as a lowercase hex string.
     *
     * <p>If {@code value} is {@code null}, returns {@code null}.
     *
     * @param value       the original field value; may be {@code null}
     * @param type        the semantic type (ignored — HMAC is type-agnostic)
     * @param semanticKey the semantic key (ignored)
     * @param dict        the substitution dictionary (not consulted — HMAC is self-consistent)
     * @return a lowercase hex HMAC digest (length depends on algorithm), or {@code null} if input is {@code null}
     */
    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        if (value == null) {
            return null;
        }
        return Hmacs.hex(
                brumeProperties.hmacSecret(),
                brumeProperties.hmacAlgorithm(),
                value.toString()
        );
    }
}

