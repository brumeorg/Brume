package com.fungle.brume.replicator;

import com.fungle.brume.error.SchemaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for #23 / audit § B1 / ticket T06.
 *
 * <p>The previous implementation read {@code pg_dump}'s output on the calling thread <em>before</em>
 * {@code process.waitFor(timeout)} was reached. A subprocess that hung without writing output
 * (network freeze, auth pending) blocked the reader indefinitely, the timeout never fired, and
 * Brume hung on the very first pipeline step. The fix moves the reader to a background thread
 * so {@code waitFor(timeout, ...)} can fire concurrently.
 *
 * <p>This test spawns a real subprocess that sleeps far longer than the configured timeout and
 * verifies that the helper kills it within a few seconds and surfaces an actionable error
 * message. Cross-platform via {@code os.name} switch (cmd ping on Windows, sleep on Unix).
 */
class SchemaReplicatorTimeoutTest {

    @Test
    @DisplayName("readOutputWithTimeout kills a hanging subprocess within the timeout window")
    void readOutputWithTimeout_killsHangingSubprocessAndThrows() throws Exception {
        ProcessBuilder pb = hangingSubprocess(30);  // sleep 30s
        pb.redirectErrorStream(true);

        Process process = pb.start();
        long start = System.currentTimeMillis();

        try {
            // Since #17 / ADR-0026, the helper throws a SchemaException with a structured
            // (message, suggestion) split: the actionable hint moved from the raw message
            // into BrumeException.suggestion(). The regression guard checks both pieces.
            assertThatThrownBy(() -> SchemaReplicator.readOutputWithTimeout(process, 1))
                    .as("a 30s subprocess with a 1s timeout must be killed and produce a "
                            + "SchemaException — regression #23 / audit § B1")
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("pg_dump timed out after 1 seconds")
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.throwable(SchemaException.class))
                    .extracting(SchemaException::suggestion).asString()
                    .contains("replication.pgdump-timeout-seconds");

            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed)
                    .as("timeout must fire promptly — the bug was an indefinite hang")
                    .isLessThan(10_000L);

            // destroyForcibly is asynchronous on some platforms (notably Windows): give the
            // OS a few seconds to actually reap the subprocess before asserting it is dead.
            boolean reaped = process.waitFor(5, TimeUnit.SECONDS);
            assertThat(reaped)
                    .as("destroyForcibly must reap the subprocess within 5s")
                    .isTrue();
            assertThat(process.isAlive())
                    .as("subprocess must be dead after waitFor returned true")
                    .isFalse();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    @DisplayName("readOutputWithTimeout returns the merged output when the subprocess exits in time")
    void readOutputWithTimeout_returnsOutputOnHappyPath() throws Exception {
        ProcessBuilder pb = quickEchoSubprocess("hello-from-test");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = SchemaReplicator.readOutputWithTimeout(process, 30);

        assertThat(output)
                .as("happy path : the subprocess output must be returned verbatim "
                        + "(modulo trailing newline / OS-specific decorations)")
                .contains("hello-from-test");
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    @DisplayName("readOutputWithTimeout does not raise on a fast-exiting subprocess with non-zero exit")
    void readOutputWithTimeout_doesNotRaiseOnNonZeroExit() throws Exception {
        // The helper's contract is to return the output and not interpret the exit code.
        // The caller (dumpSchema) is responsible for checking process.exitValue(). This test
        // pins that contract — i.e. the helper is purely about the *time* dimension.
        ProcessBuilder pb = isWindows()
                ? new ProcessBuilder("cmd", "/c", "exit 1")
                : new ProcessBuilder("sh", "-c", "exit 1");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        assertThatCode(() -> SchemaReplicator.readOutputWithTimeout(process, 30))
                .as("the helper does not raise on non-zero exit codes — that is the caller's job")
                .doesNotThrowAnyException();
        assertThat(process.exitValue()).isEqualTo(1);
    }

    private static ProcessBuilder hangingSubprocess(int seconds) {
        if (isWindows()) {
            // ping with -n N produces (N-1) seconds of sleep without writing useful stdout
            // until each ping completes. -n 30 = ~29s of subprocess that does not exit.
            return new ProcessBuilder("cmd", "/c", "ping", "-n", String.valueOf(seconds + 1), "127.0.0.1");
        }
        return new ProcessBuilder("sleep", String.valueOf(seconds));
    }

    private static ProcessBuilder quickEchoSubprocess(String message) {
        if (isWindows()) {
            return new ProcessBuilder("cmd", "/c", "echo", message);
        }
        return new ProcessBuilder("sh", "-c", "echo " + message);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
