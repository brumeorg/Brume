package com.fungle.brume.config.model;

import java.util.Optional;

/**
 * Anonymization strategy to apply to a column.
 *
 * <p>Each strategy defines how a real value is transformed:
 * <ul>
 *   <li>{@link #FAKE} — replace with deterministic fake data seeded by HMAC</li>
 *   <li>{@link #MASK} — partial masking preserving structure (e.g. first digits of phone)</li>
 *   <li>{@link #HASH} — SHA-256 one-way hash (non-reversible)</li>
 *   <li>{@link #NULLIFY} — replace with {@code null}</li>
 *   <li>{@link #KEEP} — copy the real value unchanged</li>
 *   <li>{@link #FPE_ID} — Format-Preserving Encryption for numeric IDs (FF1/BouncyCastle)</li>
 *   <li>{@link #FPE_UUID} — Format-Preserving UUID via HMAC-SHA256</li>
 * </ul>
 *
 * <p>No logic lives in this enum — all transformation logic is in the corresponding
 * strategy implementation classes under {@code anonymization/strategies/}.
 *
 * <p>Each value carries a short French description exposed via {@link #description()},
 * used by the HTML report (see {@code report-ui/v1-integration-plan.md} §5.Q2).
 */
public enum Strategy {

    /** Replace with deterministic fake data (requires a non-null {@link SemanticType}). */
    FAKE("Valeurs synthétiques tirées du dictionnaire de substitution à la locale configurée."),

    /** Partial masking preserving the value structure. */
    MASK("Remplacement par pattern ; préserve la forme du texte libre."),

    /** SHA-256 one-way hash — value cannot be recovered without brute force. */
    HASH("SHA-256 keyé avec le secret HMAC. Déterministe entre tables jointes — les FK restent valides."),

    /** Replace with {@code null}. */
    NULLIFY("Force NULL sur des colonnes PII optionnelles. Surface d'anonymat ramenée à zéro."),

    /** Copy the real value unchanged into the target database. */
    KEEP("Passthrough — colonne non sensible, joinable, ou déjà anonyme."),

    /**
     * Format-Preserving Encryption for numeric IDs using FF1 (BouncyCastle).
     * The encrypted value has the same digit length as the original.
     * FK columns pointing to an FPE_ID primary key are automatically propagated.
     */
    FPE_ID("Format-preserving encryption sur les identifiants numériques — sortie isomorphe à l'entrée."),

    /**
     * Deterministic UUID anonymization using HMAC-SHA256.
     * UUID in → UUID out (format-preserving). The output is a version-4-shaped UUID
     * derived from HMAC bytes — non-reversible without the secret.
     * FK columns pointing to an FPE_UUID primary key are automatically propagated.
     */
    FPE_UUID("FPE appliqué à des UUID — préserve le format 8-4-4-4-12 et l'unicité.");

    private static final String UNKNOWN_DESCRIPTION = "Stratégie personnalisée";

    private final String description;

    Strategy(String description) {
        this.description = description;
    }

    /**
     * Returns a short French description of the strategy, suitable for surfacing in the
     * HTML report next to the column count.
     *
     * @return a single sentence that explains what the strategy does
     */
    public String description() {
        return description;
    }

    /**
     * Tolerant lookup that maps a strategy name (as stored in
     * {@link com.fungle.brume.report.StrategyUsage#strategy()}) to its description, with a
     * safe fallback for names that do not correspond to a value of this enum.
     *
     * <p>The report layer cannot blindly call {@link #valueOf(String)} because
     * {@code StrategyUsage} stores the strategy as a raw {@code String} — tests and future
     * code may legitimately pass a custom label.
     *
     * @param name the raw strategy name, may be {@code null}
     * @return the matching description, or {@code "Stratégie personnalisée"} for unknown names
     */
    public static String descriptionOf(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN_DESCRIPTION;
        }
        try {
            return Strategy.valueOf(name).description();
        } catch (IllegalArgumentException e) {
            return UNKNOWN_DESCRIPTION;
        }
    }

    /**
     * Like {@link #descriptionOf(String)} but returns an {@link Optional} so callers can
     * distinguish a known enum value from a custom label.
     *
     * @param name the raw strategy name, may be {@code null}
     * @return the matching {@link Strategy}, or {@link Optional#empty()} for unknown names
     */
    public static Optional<Strategy> tryParse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Strategy.valueOf(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
