package com.fungle.brume.error;

/**
 * An anonymization strategy could not be applied to a specific value at runtime — for
 * example, {@code FPE_ID} received a negative value or an integer outside its supported
 * range, or a JSON path resolution failed mid-traversal. Maps to exit code 6.
 *
 * <p>This is distinct from a config-level "strategy not compatible with column type"
 * (handled by {@link com.fungle.brume.config.SchemaConfigValidator} as a
 * {@link com.fungle.brume.config.ConfigurationException}): the {@code AnonymizationException}
 * fires only at runtime, on a specific data row, after the config has been validated.
 */
public class AnonymizationException extends BrumeException {

    public AnonymizationException(BrumeErrorCode code, String message, String suggestion, Throwable cause) {
        super(code, message, suggestion, cause);
    }

    public AnonymizationException(BrumeErrorCode code, String message, String suggestion) {
        super(code, message, suggestion, null);
    }

    public AnonymizationException(BrumeErrorCode code, String message, Throwable cause) {
        super(code, message, null, cause);
    }

    public AnonymizationException(BrumeErrorCode code, String message) {
        super(code, message, null, null);
    }
}
