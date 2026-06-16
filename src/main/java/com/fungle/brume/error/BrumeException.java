package com.fungle.brume.error;

/**
 * Base class for every Brume error that should reach the operator with a clear,
 * actionable message instead of a raw stack trace.
 *
 * <p>Each instance carries:
 * <ul>
 *   <li>A {@link BrumeErrorCode} — stable identifier, surfaced in console output and exit-code mapping.</li>
 *   <li>A short {@code message} (inherited) — the immediate cause, one sentence.</li>
 *   <li>An optional {@code suggestion} — what the operator should change to recover.</li>
 *   <li>An optional {@code cause} — underlying exception, kept for {@code --show-stacktrace} mode.</li>
 * </ul>
 *
 * <p>{@code BrumeException} is {@code abstract}: callers always throw one of the five
 * concrete sub-classes ({@code ConfigurationException}, {@code ConnectionException},
 * {@code SchemaException}, {@code AnonymizationException}, {@code WriteException}).
 *
 * <p>Picocli's {@code BrumeExecutionExceptionHandler} intercepts these at the top of
 * {@code BrumeApplication.main()} and formats them into the {@code [CODE] message →
 * suggestion} layout documented in ADR-0026, then exits with the code mapped by
 * sub-class.
 *
 * <p>JDK exceptions ({@code IllegalArgumentException}, {@code IllegalStateException},
 * {@code NullPointerException}) are <strong>not</strong> caught by the handler — they
 * represent programming errors and surface with their stack trace as
 * "Unexpected error, please report".
 */
public abstract class BrumeException extends RuntimeException {

    private final BrumeErrorCode code;
    private final String suggestion;

    /**
     * Full constructor.
     *
     * @param code       descriptive error code (never {@code null})
     * @param message    one-sentence short description of the immediate cause
     * @param suggestion actionable hint for the operator; {@code null} when no specific suggestion applies
     * @param cause      underlying exception; {@code null} when the error originates in Brume itself
     */
    protected BrumeException(BrumeErrorCode code, String message, String suggestion, Throwable cause) {
        super(message, cause);
        if (code == null) {
            throw new IllegalArgumentException("BrumeException code must not be null");
        }
        this.code = code;
        this.suggestion = suggestion;
    }

    /** Convenience overload with no underlying cause. */
    protected BrumeException(BrumeErrorCode code, String message, String suggestion) {
        this(code, message, suggestion, null);
    }

    /** Convenience overload with no suggestion and no cause. */
    protected BrumeException(BrumeErrorCode code, String message) {
        this(code, message, null, null);
    }

    /** Convenience overload with no suggestion. */
    protected BrumeException(BrumeErrorCode code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    /** Returns the stable error code; never {@code null}. */
    public final BrumeErrorCode code() {
        return code;
    }

    /** Returns the actionable hint, or {@code null} when no specific suggestion was set. */
    public final String suggestion() {
        return suggestion;
    }
}
