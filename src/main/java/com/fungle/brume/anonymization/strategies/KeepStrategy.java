package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.model.SemanticType;
import org.springframework.stereotype.Component;

/**
 * Anonymization strategy that returns the original value unchanged.
 *
 * <p>Use this for columns that do not contain sensitive data but are explicitly declared in
 * {@code config.yaml} to document the intent (e.g. a product code, a country ISO code).
 *
 * <p>Handles {@code null} input gracefully — returns {@code null} unchanged.
 */
@Component
public final class KeepStrategy implements AnonymizationStrategy {

    /**
     * Returns the original value unchanged.
     *
     * @param value       the original field value; may be {@code null}
     * @param type        the semantic type (ignored)
     * @param semanticKey the semantic key (ignored)
     * @param dict        the substitution dictionary (not consulted)
     * @return the original {@code value}, including {@code null}
     */
    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        return value;
    }
}

