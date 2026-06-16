package com.fungle.brume.error;

/**
 * Policy-cancel: a configured timeout fired and the run was aborted before completing.
 * Two flavours, both carried as this exception:
 *
 * <ul>
 *   <li>{@link BrumeErrorCode#RUN_TIMEOUT_STATEMENT} — a bounded source query
 *       ({@code COUNT(*)}, FK closure, schema introspection) exceeded
 *       {@code brume.timeouts.statement-seconds}. Surfaces through pgJDBC as a
 *       {@code QueryTimeoutException}, mapped here at the call-site.</li>
 *   <li>{@link BrumeErrorCode#RUN_TIMEOUT_TOTAL} — the full pipeline wall-clock
 *       exceeded {@code brume.timeouts.total-run-seconds}. Cancellation reuses the
 *       {@code #24 / A22} infrastructure ({@code CancellationRegistry.requestCancelAll()})
 *       to abort blocking JDBC calls and destroy tracked subprocesses.</li>
 * </ul>
 *
 * <p>Both map to exit code {@code 7} via {@code BrumeExecutionExceptionHandler} —
 * distinct from {@code 130} (SIGTERM, an external cancellation request, ADR-0032) and
 * from the {@code 1-6} grid (concrete pipeline failures, ADR-0026). Tracked under
 * #23 (A21) / ADR-0033.
 */
public class RunTimeoutException extends BrumeException {

    public RunTimeoutException(BrumeErrorCode code, String message, String suggestion) {
        super(code, message, suggestion, null);
    }

    public RunTimeoutException(BrumeErrorCode code, String message, String suggestion, Throwable cause) {
        super(code, message, suggestion, cause);
    }
}
