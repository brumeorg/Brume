package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.model.SemanticType;
import org.springframework.stereotype.Component;

/**
 * Anonymization strategy that partially masks the value, keeping some recognisable characters.
 *
 * <p>The masking pattern depends on the {@link SemanticType}:
 * <ul>
 *   <li>{@code PHONE} — keeps the first 3 characters and the last 2, replaces the rest with {@code *}.
 *       Example: {@code "0612345678"} → {@code "061*****78"}</li>
 *   <li>{@code IP_ADDRESS} — keeps the first two octets, replaces the last two with {@code *.*}.
 *       Example: {@code "192.168.1.42"} → {@code "192.168.*.*"}</li>
 *   <li>All others — keeps the first character, appends {@code "***"}.
 *       Example: {@code "alice@example.com"} → {@code "a***"}</li>
 * </ul>
 *
 * <p>Handles {@code null} input gracefully — returns {@code null}.
 */
@Component
public final class MaskStrategy implements AnonymizationStrategy {

    /**
     * Returns a partially masked version of {@code value} according to {@code type}.
     *
     * @param value       the original field value; may be {@code null}
     * @param type        the semantic type determining the masking pattern
     * @param semanticKey the semantic key (ignored)
     * @param dict        the substitution dictionary (not consulted)
     * @return a masked string, or {@code null} if input is {@code null}
     */
    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        if (value == null) {
            return null;
        }
        String str = value.toString();
        if (str.isEmpty()) {
            return str;
        }
        return switch (type) {
            case PHONE      -> maskPhone(str);
            case IP_ADDRESS -> maskIpAddress(str);
            default         -> maskDefault(str);
        };
    }

    /**
     * Masks a phone number, keeping the first 3 and last 2 characters.
     *
     * @param phone the original phone string
     * @return the masked phone string
     */
    private String maskPhone(String phone) {
        if (phone.length() <= 5) {
            return "*".repeat(phone.length());
        }
        int keepStart = 3;
        int keepEnd = 2;
        int maskLen = phone.length() - keepStart - keepEnd;
        return phone.substring(0, keepStart)
                + "*".repeat(Math.max(maskLen, 1))
                + phone.substring(phone.length() - keepEnd);
    }

    /**
     * Masks an IP address, keeping the first two octets.
     *
     * @param ip the original IP address string
     * @return the masked IP address (e.g. {@code "192.168.*.*"})
     */
    private String maskIpAddress(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return "*.*.*.*";
    }

    /**
     * Applies the default masking: keeps the first character, appends {@code "***"}.
     *
     * @param value the original value string
     * @return the masked string
     */
    private String maskDefault(String value) {
        return value.charAt(0) + "***";
    }
}

