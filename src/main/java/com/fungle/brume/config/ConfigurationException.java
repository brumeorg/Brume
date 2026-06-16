package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.BrumeException;

/**
 * Thrown when the Brume configuration is invalid.
 *
 * <p>This is a fail-fast exception: it is raised by {@link ConfigValidator},
 * {@link SchemaConfigValidator} and the various property-binding validators before
 * any database connection is opened, so the operator sees a clear error message
 * immediately rather than a cryptic failure mid-replication.
 *
 * <p>Examples of invalid configurations:
 * <ul>
 *   <li>{@link com.fungle.brume.config.model.Strategy#FAKE} without a {@code SemanticType}</li>
 *   <li>{@link com.fungle.brume.config.model.SemanticType#JSONB} without {@code jsonPaths}</li>
 *   <li>An empty {@code extraction.tables} list</li>
 *   <li>A reference to a table or column that does not exist in the source schema</li>
 * </ul>
 *
 * <p>Reparented under {@link BrumeException} for the differentiated exit-code mapping
 * (#17, ADR-0026). Following #17b / ADR-0027, the only entry points are the two
 * structured constructors below: both require a non-null {@link BrumeErrorCode} and a
 * non-blank {@code suggestion}. The {@code requireNonBlankSuggestion} check makes the
 * A10 contract ("every operator error surfaces an actionable hint") an invariant of
 * construction — there is no longer any way to throw a {@code ConfigurationException}
 * with a {@code null} or empty suggestion.
 *
 * <p>The two legacy single-message constructors and the {@code CONFIG_GENERIC} catch-all
 * code were removed in the #17b seal commit.
 */
public class ConfigurationException extends BrumeException {

    /**
     * Structured constructor — the only form available since #17b / ADR-0027.
     *
     * @param code       descriptive error code (never {@code null})
     * @param message    one-sentence short cause
     * @param suggestion actionable hint for the operator; must be non-null and non-blank
     * @throws IllegalArgumentException if {@code suggestion} is {@code null} or blank
     */
    public ConfigurationException(BrumeErrorCode code, String message, String suggestion) {
        super(code, message, requireNonBlankSuggestion(suggestion));
    }

    /** Structured constructor with a wrapped cause. Same {@code suggestion} contract. */
    public ConfigurationException(BrumeErrorCode code, String message, String suggestion, Throwable cause) {
        super(code, message, requireNonBlankSuggestion(suggestion), cause);
    }

    private static String requireNonBlankSuggestion(String suggestion) {
        if (suggestion == null || suggestion.isBlank()) {
            throw new IllegalArgumentException(
                    "ConfigurationException requires a non-blank suggestion — every config error "
                            + "must surface an actionable hint (cf. #17b / ADR-0027). If no specific "
                            + "suggestion truly applies, pick the closest CONFIG_* code and write "
                            + "the next operator action explicitly.");
        }
        return suggestion;
    }
}
