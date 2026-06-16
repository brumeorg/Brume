package com.fungle.brume.command;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.AnonymizationException;
import com.fungle.brume.error.ConnectionException;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.MaxTargetRowsExceededException;
import com.fungle.brume.error.RunTimeoutException;
import com.fungle.brume.error.SchemaException;
import com.fungle.brume.error.WriteException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BrumeExecutionExceptionHandler}.
 *
 * <p>Covers (a) the exit-code mapping per {@link com.fungle.brume.error.BrumeException}
 * sub-class, (b) the console output layout (code, message, suggestion, exit-code line),
 * (c) the {@code brume.show-stacktrace} toggle, and (d) the fallback for non-Brume
 * exceptions (always stack trace + exit code 127).
 */
class BrumeExecutionExceptionHandlerTest {

    private ByteArrayOutputStream buffer;
    private PrintStream err;

    @BeforeEach
    void setUp() {
        buffer = new ByteArrayOutputStream();
        err = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.clearProperty("brume.show-stacktrace");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("brume.show-stacktrace");
    }

    // -------------------------------------------------------------------------
    // Exit-code mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ConfigurationException → exit code 1")
    void configException_exit1() {
        int code = BrumeExecutionExceptionHandler.exitCodeFor(
                new ConfigurationException(BrumeErrorCode.CONFIG_HMAC_INVALID, "x", "y"));
        assertThat(code).isEqualTo(1);
    }

    @Test
    @DisplayName("MaxTargetRowsExceededException → exit code 3 (more specific than ConfigurationException)")
    void maxRowsException_exit3() {
        int code = BrumeExecutionExceptionHandler.exitCodeFor(
                new MaxTargetRowsExceededException("plan exceeded", "tighten"));
        assertThat(code).isEqualTo(3);
    }

    @Test
    @DisplayName("ConnectionException → exit code 2")
    void connectionException_exit2() {
        int code = BrumeExecutionExceptionHandler.exitCodeFor(
                new ConnectionException(BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE, "down", "check host"));
        assertThat(code).isEqualTo(2);
    }

    @Test
    @DisplayName("SchemaException → exit code 4")
    void schemaException_exit4() {
        int code = BrumeExecutionExceptionHandler.exitCodeFor(
                new SchemaException(BrumeErrorCode.SCHEMA_PGDUMP_FAILED, "fail"));
        assertThat(code).isEqualTo(4);
    }

    @Test
    @DisplayName("WriteException → exit code 5")
    void writeException_exit5() {
        int code = BrumeExecutionExceptionHandler.exitCodeFor(
                new WriteException(BrumeErrorCode.WRITE_DUMP_IO, "fail"));
        assertThat(code).isEqualTo(5);
    }

    @Test
    @DisplayName("AnonymizationException → exit code 6")
    void anonException_exit6() {
        int code = BrumeExecutionExceptionHandler.exitCodeFor(
                new AnonymizationException(BrumeErrorCode.ANON_FPE_ID_OUT_OF_RANGE, "fail"));
        assertThat(code).isEqualTo(6);
    }

    @Test
    @DisplayName("RunTimeoutException → exit code 7 (statement or total run, ADR-0033)")
    void runTimeoutException_exit7() {
        int statementExit = BrumeExecutionExceptionHandler.exitCodeFor(
                new RunTimeoutException(BrumeErrorCode.RUN_TIMEOUT_STATEMENT, "statement", "raise"));
        int totalExit = BrumeExecutionExceptionHandler.exitCodeFor(
                new RunTimeoutException(BrumeErrorCode.RUN_TIMEOUT_TOTAL, "total", "raise"));
        assertThat(statementExit).isEqualTo(7);
        assertThat(totalExit).isEqualTo(7);
    }

    // -------------------------------------------------------------------------
    // Output layout
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("BrumeException output: [CODE] message + suggestion + exit code, no stack trace by default")
    void prettyPrintsBrumeException() {
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(err);
        ConfigurationException ex = new ConfigurationException(
                BrumeErrorCode.CONFIG_HMAC_INVALID,
                "HMAC algorithm 'HmacXYZ' is unavailable on this JVM.",
                "Pick a JDK-supported algorithm in brume.hmac-algorithm.");

        int exitCode = handler.handleExecutionException(ex, null, null);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(exitCode).isEqualTo(1);
        assertThat(output)
                .contains("[CONFIG_HMAC_INVALID]")
                .contains("HMAC algorithm 'HmacXYZ' is unavailable")
                .contains("→ Pick a JDK-supported algorithm")
                .contains("→ Exit code: 1");
        assertThat(output).doesNotContain("at com.fungle.brume"); // no stack trace lines
    }

    @Test
    @DisplayName("BrumeException output: omits the suggestion line when it's null")
    void omitsNullSuggestion() {
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(err);
        SchemaException ex = new SchemaException(BrumeErrorCode.SCHEMA_PGDUMP_TIMEOUT, "timeout 300s");

        handler.handleExecutionException(ex, null, null);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("[SCHEMA_PGDUMP_TIMEOUT]").contains("timeout 300s");
        // Only the "Exit code" arrow, no suggestion arrow.
        long arrows = output.lines().filter(l -> l.contains("→")).count();
        assertThat(arrows).isEqualTo(1);
    }

    @Test
    @DisplayName("brume.show-stacktrace=true appends the stack trace under the message")
    void showStacktraceFlagAddsTrace() {
        System.setProperty("brume.show-stacktrace", "true");
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(err);
        ConfigurationException ex = new ConfigurationException(
                BrumeErrorCode.CONFIG_HMAC_INVALID, "invalid config", "fix the hmac config");

        handler.handleExecutionException(ex, null, null);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("[CONFIG_HMAC_INVALID]")
                .contains("--- stack trace (brume.show-stacktrace=true) ---")
                .contains("at com.fungle.brume"); // at least one stack frame
    }

    @Test
    @DisplayName("non-Brume exceptions: 'unexpected' marker, full stack trace, exit code 127")
    void unexpectedExceptionPrintsStackTrace() {
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(err);
        Exception jdk = new IllegalStateException("a programming bug");

        int exitCode = handler.handleExecutionException(jdk, null, null);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(exitCode).isEqualTo(127);
        assertThat(output)
                .contains("[UNEXPECTED] IllegalStateException: a programming bug")
                .contains("This is likely a Brume bug")
                .contains("→ Exit code: 127")
                .contains("at com.fungle.brume");
    }
}
