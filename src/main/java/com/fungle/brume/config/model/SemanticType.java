package com.fungle.brume.config.model;

/**
 * Semantic type of a column value, used by anonymization strategies to generate
 * contextually appropriate fake data.
 *
 * <p>The semantic type drives:
 * <ul>
 *   <li>{@link Strategy#FAKE} — which Faker provider to call (e.g. {@code internet().emailAddress()})</li>
 *   <li>{@link Strategy#MASK} — which masking pattern to apply (e.g. keep first 3 digits for PHONE)</li>
 * </ul>
 *
 * <p>No logic lives in this enum — all transformation logic is in the corresponding
 * strategy implementation classes under {@code anonymization/strategies/}.
 */
public enum SemanticType {

    /** Email address — e.g. {@code alice@example.com}. */
    EMAIL,

    /** First name — e.g. {@code Alice}. */
    FIRST_NAME,

    /** Last name — e.g. {@code Dupont}. */
    LAST_NAME,

    /** Phone number — e.g. {@code +33 6 12 34 56 78}. */
    PHONE,

    /** Postal address — e.g. {@code 12 rue de la Paix, Paris}. */
    ADDRESS,

    /** IBAN bank account number — e.g. {@code FR76 3000 6000 0112 3456 7890 189}. */
    IBAN,

    /** IPv4 address — e.g. {@code 192.168.1.42}. */
    IP_ADDRESS,

    /**
     * JSON column — delegates to {@link com.fungle.brume.anonymization.JsonPathProcessor}
     * using the {@code json_paths} configuration to anonymize individual nested fields.
     * Must be combined with a non-empty {@code jsonPaths} list in {@link ColumnConfig}.
     */
    JSONB
}

