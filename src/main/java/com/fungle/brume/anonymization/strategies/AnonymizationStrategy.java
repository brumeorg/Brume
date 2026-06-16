package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.model.SemanticType;

/**
 * Strategy interface for anonymizing a single field value.
 *
 * <p>This is a <strong>sealed interface</strong> — only the permitted implementations
 * may exist, giving the compiler exhaustiveness guarantees in switch expressions.
 *
 * <p>Implementations must be deterministic: given the same inputs and the same Brume
 * configuration (secrets), {@code anonymize()} must always return the same output.
 *
 * <p>Nullability contract:
 * <ul>
 *   <li>Implementations must handle a {@code null} input gracefully (return {@code null}
 *       or a safe default) unless the Javadoc of the specific implementation states otherwise.
 *   <li>{@link NullifyStrategy} always returns {@code null} regardless of input.
 * </ul>
 */
public sealed interface AnonymizationStrategy
        permits FakeStrategy, FpeIdStrategy, FpeUuidStrategy, MaskStrategy, HashStrategy, NullifyStrategy, KeepStrategy {

    /**
     * Anonymizes the given value according to this strategy.
     *
     * @param value       the original field value; may be {@code null} for nullable columns
     * @param type        the semantic type of the column (e.g. EMAIL, PHONE) — used to generate
     *                    contextually appropriate fake data
     * @param semanticKey a logical key grouping linked columns across tables — used to look up
     *                    or store the anonymized value in the {@link SubstitutionDictionary}
     * @param dict        the cross-table substitution dictionary ensuring consistency
     * @return the anonymized value, or {@code null} for {@link NullifyStrategy}
     */
    Object anonymize(Object value, SemanticType type, String semanticKey, SubstitutionDictionary dict);
}

