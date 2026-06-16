package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.model.SemanticType;
import org.springframework.stereotype.Component;

/**
 * Anonymization strategy that always returns {@code null}.
 *
 * <p>Use this for columns that must be fully suppressed in the target environment
 * (e.g. internal notes, sensitive metadata). The column must be nullable in the target schema.
 *
 * <p>Handles {@code null} input gracefully — returns {@code null} regardless of input.
 */
@Component
public final class NullifyStrategy implements AnonymizationStrategy {

    /**
     * Returns {@code null} regardless of the input value.
     *
     * @param value       the original field value (ignored)
     * @param type        the semantic type (ignored)
     * @param semanticKey the semantic key (ignored)
     * @param dict        the substitution dictionary (not consulted)
     * @return always {@code null}
     */
    @Override
    public Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict) {
        return null;
    }
}

