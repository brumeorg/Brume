package com.fungle.brume.shutdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class CancellationRegistryTest {

    @org.junit.jupiter.api.AfterEach
    void forgetSimulatedSignal() {
        CancellationRegistry.resetCancellationRequested();
    }

    @Test
    @DisplayName("requestCancelAll — sets the token, aborts every registered Connection, destroys every Process")
    void requestCancelAll_propagatesToAllResources() throws SQLException {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);

        Connection c1 = mock(Connection.class);
        Connection c2 = mock(Connection.class);
        Process p1 = mock(Process.class);

        registry.register(c1);
        registry.register(c2);
        registry.register(p1);

        registry.requestCancelAll();

        assertThat(token.isCancelled()).isTrue();
        verify(c1).abort(any());
        verify(c2).abort(any());
        verify(p1).destroyForcibly();
    }

    @Test
    @DisplayName("requestCancelAll — swallows resource errors (best-effort)")
    void requestCancelAll_swallowsResourceErrors() throws SQLException {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);

        Connection c1 = mock(Connection.class);
        doThrow(new SQLException("abort failed")).when(c1).abort(any());
        Connection c2 = mock(Connection.class);

        registry.register(c1);
        registry.register(c2);

        // Must not propagate; c2.abort() is still invoked despite c1 failing.
        registry.requestCancelAll();
        verify(c1).abort(any());
        verify(c2).abort(any());
    }

    @Test
    @DisplayName("deregister — connection no longer aborted on cancel")
    void deregister_excludesFromCancel() throws SQLException {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);

        Connection c1 = mock(Connection.class);
        registry.register(c1);
        registry.deregister(c1);

        registry.requestCancelAll();

        verify(c1, times(0)).abort(any());
    }

    @Test
    @DisplayName("counts reflect registry state")
    void counts_reflectState() {
        CancellationRegistry registry = new CancellationRegistry(new CancellationToken());
        assertThat(registry.activeConnectionCount()).isZero();
        assertThat(registry.activeSubprocessCount()).isZero();

        Connection c = mock(Connection.class);
        Process p = mock(Process.class);
        registry.register(c);
        registry.register(p);

        assertThat(registry.activeConnectionCount()).isEqualTo(1);
        assertThat(registry.activeSubprocessCount()).isEqualTo(1);

        registry.deregister(c);
        registry.deregister(p);
        assertThat(registry.activeConnectionCount()).isZero();
        assertThat(registry.activeSubprocessCount()).isZero();
    }

    @Test
    @DisplayName("#24a — the hook after a finished run (normal System.exit) cancels nothing and reports no SIGTERM")
    void onJvmShutdown_afterCompletion_isANoOp() {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);
        Process process = mock(Process.class);
        registry.register(process);

        registry.markCompleted();
        registry.onJvmShutdown();

        assertThat(token.isCancelled()).isFalse();
        verify(process, never()).destroyForcibly();
    }

    @Test
    @DisplayName("#24a — the hook during a run (signal) still cancels everything")
    void onJvmShutdown_duringRun_cancels() {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);
        Process process = mock(Process.class);
        registry.register(process);

        registry.onJvmShutdown(java.time.Duration.ofMillis(50));

        assertThat(token.isCancelled()).isTrue();
        verify(process).destroyForcibly();
    }

    @Test
    @DisplayName("#24d — after a signal the hook waits for the main thread to report and close the sinks")
    void onJvmShutdown_waitsForTheMainThread() throws InterruptedException {
        CancellationRegistry registry = new CancellationRegistry(new CancellationToken());
        Thread main = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            registry.markCompleted();
        });
        main.start();

        long start = System.nanoTime();
        registry.onJvmShutdown(java.time.Duration.ofSeconds(5));
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(waitedMs).as("returns once the main thread is done, not after the grace").isBetween(250L, 3000L);
        main.join();
    }

    @Test
    void graceStaysBelowDockerStopTimeout() {
        assertThat(CancellationRegistry.MAIN_THREAD_GRACE).isLessThan(java.time.Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("#24d — after a signal, an error caused by the aborted connection is reported as RUN_CANCELLED")
    void errorAfterSignalIsReportedAsCancellation() {
        new CancellationRegistry(new CancellationToken()).onJvmShutdown(java.time.Duration.ofMillis(1));
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previous = System.err;
        System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            new com.fungle.brume.command.BrumeExecutionExceptionHandler().report(
                    new com.fungle.brume.error.ConnectionException(
                            com.fungle.brume.error.BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE,
                            "An I/O error occurred while sending to the backend.", "Check the network."));
        } finally {
            System.setErr(previous);
        }

        assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("[RUN_CANCELLED]")
                .doesNotContain("CONNECTION_SOURCE_UNREACHABLE");
    }
}
