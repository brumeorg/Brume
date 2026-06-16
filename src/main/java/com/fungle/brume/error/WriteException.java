package com.fungle.brume.error;

/**
 * The write phase failed: target INSERT/COPY batch exceeded its tolerated error rate,
 * the substitution dictionary overflowed, or the {@code SqlFileSink} could not open,
 * write to, or close its output file. Maps to exit code 5.
 *
 * <p>{@link com.fungle.brume.anonymization.SubstitutionDictionaryOverflowException} and
 * {@link com.fungle.brume.writer.BatchErrorThresholdExceededException} extend this class
 * for the specific overflow / threshold scenarios; both retain their original constructors
 * for backward compatibility with their existing call sites.
 */
public class WriteException extends BrumeException {

    public WriteException(BrumeErrorCode code, String message, String suggestion, Throwable cause) {
        super(code, message, suggestion, cause);
    }

    public WriteException(BrumeErrorCode code, String message, String suggestion) {
        super(code, message, suggestion, null);
    }

    public WriteException(BrumeErrorCode code, String message, Throwable cause) {
        super(code, message, null, cause);
    }

    public WriteException(BrumeErrorCode code, String message) {
        super(code, message, null, null);
    }
}
