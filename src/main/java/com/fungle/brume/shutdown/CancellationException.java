package com.fungle.brume.shutdown;

/**
 * Signals that the pipeline was interrupted by a SIGTERM / SIGINT (#24 / A22).
 *
 * <p>Deliberately does <strong>not</strong> extend {@code BrumeException}: SIGTERM is not
 * a Brume error category but an operator-driven stop. It exits via code {@code 130}
 * (POSIX {@code 128 + SIGINT(2)}) which is outside the {@code BrumeErrorCode} grid
 * (ADR-0026: 0,1,2,3,4,5,6,127). The picocli execution exception handler intercepts it
 * as a special case rather than running it through {@code exitCodeFor(BrumeException)}.
 *
 * <p>Thrown by {@link CancellationToken#checkpoint()} at the next pipeline check point
 * after {@link CancellationRegistry#requestCancelAll()} has been called from the shutdown
 * hook installed in {@code BrumeApplication.main()}.
 */
public class CancellationException extends RuntimeException {

    public CancellationException() {
        super("Pipeline cancelled by SIGTERM / SIGINT");
    }

    public CancellationException(String message) {
        super(message);
    }
}
