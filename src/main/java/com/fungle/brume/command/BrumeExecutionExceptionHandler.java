package com.fungle.brume.command;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.AnonymizationException;
import com.fungle.brume.error.ConnectionException;
import com.fungle.brume.error.BrumeException;
import com.fungle.brume.error.MaxTargetRowsExceededException;
import com.fungle.brume.error.RunTimeoutException;
import com.fungle.brume.error.SchemaException;
import com.fungle.brume.error.WriteException;
import com.fungle.brume.output.OutputMode;
import com.fungle.brume.shutdown.CancellationException;
import com.fungle.brume.shutdown.CancellationRegistry;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

import java.io.PrintStream;
import java.io.StringWriter;

/**
 * picocli exception handler that turns {@link BrumeException} into actionable console output
 * with a category-specific exit code, and lets every other {@link Throwable} surface with its
 * stack trace as an "unexpected error" report.
 *
 * <p>Output layout for a {@link BrumeException}:
 *
 * <pre>{@code
 * [CONFIG_INVALID_TABLE_NAME] Table 'userz' does not exist in source schema.
 *   → Did you mean 'users'? Edit anonymization.tables to match a real table.
 *   → Exit code: 1
 * }</pre>
 *
 * <p>Stack trace is suppressed for {@link BrumeException} by default — operators get a clean
 * message instead of dozens of frames. Setting the system property {@code brume.show-stacktrace=true}
 * (e.g. via the {@code --show-stacktrace} CLI flag parsed pre-boot in {@code BrumeApplication.main()})
 * appends the full stack trace to the output for debugging.
 *
 * <p>Exit-code mapping (ADR-0026):
 * <ul>
 *   <li>0 — success</li>
 *   <li>1 — {@link ConfigurationException} (excluding the more specific {@link MaxTargetRowsExceededException})</li>
 *   <li>2 — {@link ConnectionException}</li>
 *   <li>3 — {@link MaxTargetRowsExceededException}</li>
 *   <li>4 — {@link SchemaException}</li>
 *   <li>5 — {@link WriteException}</li>
 *   <li>6 — {@link AnonymizationException}</li>
 *   <li>7 — {@link RunTimeoutException} (statement or total-run timeout fired, ADR-0033)</li>
 *   <li>127 — any other {@link BrumeException} subclass added in the future without a mapping, or any non-Brume {@link Throwable}</li>
 * </ul>
 */
public class BrumeExecutionExceptionHandler implements IExecutionExceptionHandler {

    private static final String STACK_TRACE_PROPERTY = "brume.show-stacktrace";

    private static final String CANCELLED_SUGGESTION = "Run stopped by a termination signal. Tables already "
            + "written are kept ; with brume.checkpoint.enabled=true the next execute resumes after them.";

    private final PrintStream err;

    public BrumeExecutionExceptionHandler() {
        this(System.err);
    }

    /** Package-visible for unit testing — lets a {@code ByteArrayOutputStream} sink the output. */
    BrumeExecutionExceptionHandler(PrintStream err) {
        this.err = err;
    }

    @Override
    public int handleExecutionException(Exception ex, CommandLine commandLine, ParseResult parseResult) {
        return report(ex);
    }

    /**
     * Prints {@code ex} in the format of this handler and returns its exit code. Shared by
     * picocli (failures of a subcommand) and {@code BrumeApplication.main} (failures before
     * picocli runs : pre-boot checks, Spring context boot — #17c, ADR-0061), so every error
     * reaches the operator in the same {@code [CODE] message → suggestion → Exit code} shape,
     * in TEXT or JSON mode.
     */
    public int report(Throwable ex) {
        boolean json = OutputMode.current() == OutputMode.JSON;
        // #24 / A22 — SIGTERM cancel is a special exit code path: 130 (POSIX SIGINT-style),
        // outside the BrumeException grid (ADR-0026). Short message, no stack, no suggestion.
        Throwable cancelCause = findCancellation(ex);
        if (cancelCause == null && CancellationRegistry.cancellationRequested()) {
            // #24d : the signal aborted a connection mid-query ; the SQLException the main
            // thread got (wrapped as a filter, connection or write error) is not the cause.
            cancelCause = new CancellationException("Brume run cancelled by a termination signal.");
        }
        if (cancelCause != null) {
            if (json) {
                // A signal sets the exit code itself (ADR-0063) : 143 for SIGTERM (docker stop,
                // Kubernetes, CI cancel — the automated case JSON serves), 130 for SIGINT.
                StringBuilder sb = new StringBuilder("{\"error\":{\"code\":\"RUN_CANCELLED\",");
                appendJsonString(sb, "message", cancelCause.getMessage() == null ? "" : cancelCause.getMessage());
                sb.append(',');
                appendJsonString(sb, "suggestion", CANCELLED_SUGGESTION);
                err.println(sb.append("},\"exitCode\":143}"));
            } else {
                err.println();
                err.println("[RUN_CANCELLED] " + cancelCause.getMessage());
                err.println("  → " + CANCELLED_SUGGESTION);
                err.println("  → Exit code: 143 (SIGTERM) / 130 (SIGINT)");
            }
            // Without a signal (in-process cancellation) this value is the exit code ; after a
            // signal the JVM is already shutting down and exits with 128 + signal number.
            return 130;
        }
        if (ex instanceof BrumeException me) {
            int exitCode = exitCodeFor(me);
            if (json) {
                printBrumeExceptionJson(me, exitCode);
            } else {
                printBrumeException(me, exitCode);
            }
            return exitCode;
        }
        // Non-Brume: print "unexpected error" + always include the stack trace.
        if (json) {
            printUnexpectedJson(ex);
        } else {
            err.println();
            err.println("[UNEXPECTED] " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            err.println("  → This is likely a Brume bug. Please report it with the stack trace below.");
            err.println("  → Exit code: 127");
            ex.printStackTrace(err);
        }
        return 127;
    }

    /**
     * Walks {@code ex} and its causes for a {@link CancellationException}. SIGTERM may
     * surface wrapped in a {@code SQLException} from {@code Connection.abort()} or a
     * picocli runtime — we accept it nested. Returns the first match or {@code null}.
     */
    private static Throwable findCancellation(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof CancellationException) return cur;
            if (cur == cur.getCause()) break;
        }
        return null;
    }

    /**
     * Computes the exit code for a {@link BrumeException}. Order matters: the most-specific
     * subclass must be checked first (e.g. {@link MaxTargetRowsExceededException} before
     * {@link ConfigurationException}).
     */
    static int exitCodeFor(BrumeException ex) {
        if (ex instanceof MaxTargetRowsExceededException) return 3;
        if (ex instanceof ConfigurationException) return 1;
        if (ex instanceof ConnectionException) return 2;
        if (ex instanceof SchemaException) return 4;
        if (ex instanceof WriteException) return 5;
        if (ex instanceof AnonymizationException) return 6;
        if (ex instanceof RunTimeoutException) return 7;
        return 127;
    }

    private void printBrumeException(BrumeException ex, int exitCode) {
        err.println();
        err.println("[" + ex.code() + "] " + ex.getMessage());
        if (ex.suggestion() != null && !ex.suggestion().isBlank()) {
            err.println("  → " + ex.suggestion());
        }
        err.println("  → Exit code: " + exitCode);
        if (Boolean.parseBoolean(System.getProperty(STACK_TRACE_PROPERTY))) {
            err.println();
            err.println("--- stack trace (brume.show-stacktrace=true) ---");
            ex.printStackTrace(err);
        }
    }

    /**
     * JSON variant of {@link #printBrumeException} — emits a single line of JSON on stderr.
     * Shape: {@code {"error":{"code","message","suggestion","stack?"},"exitCode":N}}.
     * No Jackson dep — manual escape keeps this handler usable from any context (matches
     * {@link com.fungle.brume.output.BrumeJsonEncoder}).
     */
    private void printBrumeExceptionJson(BrumeException ex, int exitCode) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"error\":{");
        appendJsonString(sb, "code", ex.code() == null ? "" : ex.code().name());
        sb.append(',');
        appendJsonString(sb, "message", ex.getMessage() == null ? "" : ex.getMessage());
        if (ex.suggestion() != null && !ex.suggestion().isBlank()) {
            sb.append(',');
            appendJsonString(sb, "suggestion", ex.suggestion());
        }
        if (Boolean.parseBoolean(System.getProperty(STACK_TRACE_PROPERTY))) {
            sb.append(',');
            appendJsonString(sb, "stack", stackToString(ex));
        }
        sb.append("},\"exitCode\":").append(exitCode).append('}');
        err.println(sb);
    }

    private void printUnexpectedJson(Throwable ex) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"error\":{");
        appendJsonString(sb, "code", "UNEXPECTED");
        sb.append(',');
        appendJsonString(sb, "message",
                ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
        sb.append(',');
        appendJsonString(sb, "suggestion", "This is likely a Brume bug. Report it with the stack trace.");
        sb.append(',');
        appendJsonString(sb, "stack", stackToString(ex));
        sb.append("},\"exitCode\":127}");
        err.println(sb);
    }

    private static String stackToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static void appendJsonString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append('"').append(':').append('"');
        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
