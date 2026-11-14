package com.fungle.brume.shutdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Tracks active cancellable resources (open JDBC {@link Connection} instances and active
 * native subprocesses) and orchestrates a best-effort cancel-all when the JVM shutdown
 * hook fires (#24 / A22).
 *
 * <p>Without this registry, a volatile {@link CancellationToken} flag is invisible to
 * <strong>blocking</strong> operations like {@code CopyManager.copyIn(...)} (objection #2
 * du challenger) or {@code Process.waitFor(...)} on the pg_dump subprocess
 * (objection #3) — they only respond to {@code Connection.cancel()} or
 * {@code Process.destroyForcibly()}. The registry holds the references so the hook can
 * reach them without traversing the bean graph.
 *
 * <p>Thread-safe via synchronized sets (low contention — registration is one event per
 * connection / subprocess, the hot path is the cancel-all from a single thread).
 *
 * <p>Use:
 * <pre>
 *     Connection c = ds.getConnection();
 *     registry.register(c);
 *     try { ... } finally { registry.deregister(c); c.close(); }
 * </pre>
 */
@Component
public class CancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(CancellationRegistry.class);

    /** Identity-based: same Connection wrapper may equals() another via Hikari proxy quirks. */
    private final Set<Connection> connections =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Set<Process> subprocesses =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private final CancellationToken token;

    /**
     * How long the hook waits, after a signal, for the main thread to report the
     * cancellation and close the sinks (#24d, ADR-0063). Below the 10 s grace period of
     * {@code docker stop} before its SIGKILL.
     */
    static final java.time.Duration MAIN_THREAD_GRACE = java.time.Duration.ofSeconds(8);

    /**
     * Set by the shutdown hook ({@link #onJvmShutdown()}) on a signal. Read by the error reporter, outside Spring : once
     * the JDBC connections and pg_dump are aborted, whatever exception the main thread hits
     * (I/O error, connection lost…) is a consequence of the signal (#24d).
     */
    private static volatile boolean cancellationRequested;

    public static boolean cancellationRequested() {
        return cancellationRequested;
    }

    /** Tests run in one JVM : a simulated signal must not leak into the next test class. */
    static void resetCancellationRequested() {
        cancellationRequested = false;
    }

    /** Counted down once the subcommand returned : the JVM then exits on purpose, not on a signal. */
    private final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);

    public CancellationRegistry(CancellationToken token) {
        this.token = token;
    }

    /** Marks the run as finished : a later {@link #onJvmShutdown()} is the normal exit. */
    public void markCompleted() {
        completed.countDown();
    }

    /**
     * Body of the JVM shutdown hook. A hook runs on every JVM exit, including the
     * {@code System.exit} after a finished run : before #24a that path logged
     * "SIGTERM received — cancelling 0 JDBC connection(s)" at the end of every command.
     *
     * <p>On a signal, the run is still in progress : cancel everything, then give the main
     * thread {@link #MAIN_THREAD_GRACE} to print {@code [RUN_CANCELLED]} and close the sinks
     * (gzip / zstd trailer of a dump). Before #24d the hook returned at once and the JVM
     * halted while the main thread was still unwinding : no message, possibly a truncated
     * dump. The exit code is the JVM's : 143 on SIGTERM, 130 on SIGINT (ADR-0063).
     */
    public void onJvmShutdown() {
        onJvmShutdown(MAIN_THREAD_GRACE);
    }

    void onJvmShutdown(java.time.Duration grace) {
        if (completed.getCount() == 0) return;
        cancellationRequested = true;
        log.info("Termination signal received (SIGTERM / SIGINT) — stopping the run");
        requestCancelAll();
        try {
            if (!completed.await(grace.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.warn("Run still unwinding {} s after the termination signal — exiting anyway",
                        grace.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void register(Connection connection) {
        if (connection != null) connections.add(connection);
    }

    public void deregister(Connection connection) {
        if (connection != null) connections.remove(connection);
    }

    public void register(Process process) {
        if (process != null) subprocesses.add(process);
    }

    public void deregister(Process process) {
        if (process != null) subprocesses.remove(process);
    }

    /**
     * Sets the cancel flag and cancels every currently-registered cancellable. Idempotent —
     * safe to call multiple times (subsequent calls are no-ops once token is set).
     *
     * <p>Errors are swallowed by design: this runs from a JVM shutdown hook, propagating
     * would mask the upstream cause and the JVM is going down anyway. Each best-effort
     * failure is logged at WARN so the operator can investigate post-mortem.
     */
    public void requestCancelAll() {
        token.requestCancel();
        log.info("Cancelling {} JDBC connection(s) and {} subprocess(es)",
                connections.size(), subprocesses.size());

        // Iterate over snapshots to avoid ConcurrentModificationException during cancel.
        Connection[] connSnap;
        synchronized (connections) {
            connSnap = connections.toArray(new Connection[0]);
        }
        for (Connection c : connSnap) {
            try {
                c.abort(Runnable::run);
            } catch (SQLException | RuntimeException e) {
                log.warn("Failed to abort JDBC connection during cancel: {}", e.getMessage());
            }
        }

        Process[] procSnap;
        synchronized (subprocesses) {
            procSnap = subprocesses.toArray(new Process[0]);
        }
        for (Process p : procSnap) {
            try {
                p.destroyForcibly();
            } catch (RuntimeException e) {
                log.warn("Failed to destroy subprocess during cancel: {}", e.getMessage());
            }
        }
    }

    int activeConnectionCount() {
        return connections.size();
    }

    int activeSubprocessCount() {
        return subprocesses.size();
    }
}
