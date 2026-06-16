package com.fungle.brume.error;

import com.fungle.brume.config.ConfigurationException;

/**
 * The pre-flight plan estimates a total row count above {@code brume.max-target-rows}.
 * Maps to exit code 3 (specifically distinguished from the generic ConfigurationException
 * exit code 1, so operators can script "did we exceed the guardrail?" without parsing the
 * message).
 *
 * <p>Subclass of {@link ConfigurationException} because the guardrail is a config-level
 * decision the operator owns (tighten filters or raise the limit). Catching
 * {@link ConfigurationException} still catches this — the specific class is only consulted
 * by the exit-code handler.
 */
public class MaxTargetRowsExceededException extends ConfigurationException {

    public MaxTargetRowsExceededException(String message, String suggestion) {
        super(BrumeErrorCode.CONFIG_MAX_TARGET_ROWS_EXCEEDED, message, suggestion);
    }
}
