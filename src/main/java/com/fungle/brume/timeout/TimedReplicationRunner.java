package com.fungle.brume.timeout;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.RunTimeoutException;
import com.fungle.brume.shutdown.CancellationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps {@link ReplicationAgent#run(CommandEnum)} with a hard total-run wall-clock cap
 * (#23 / A21, ADR-0033). On timeout fire, reuses the {@code #24 / A22} cancellation
 * infrastructure ({@link CancellationRegistry#requestCancelAll()}) to abort blocking JDBC
 * calls and destroy tracked subprocesses, then surfaces a {@link RunTimeoutException}
 * for the {@code BrumeExecutionExceptionHandler} to format with exit code 7.
 *
 * <p>When {@code brume.timeouts.total-run-seconds = 0} (default), this runner is a
 * transparent pass-through to the agent — no executor allocation, no overhead.
 *
 * <p>The pipeline is submitted to a single-thread <strong>daemon</strong> executor so the
 * JVM can exit cleanly even if the pipeline thread itself remains blocked in a
 * non-interruptible operation after the cancel-all. The grace period gives well-behaved
 * blocking calls (those that respond to {@code Connection.abort()} / {@code Process.destroyForcibly()})
 * a chance to unwind cleanly before we throw; {@code Runtime.halt(7)} is the last resort
 * if even that fails (operator would otherwise observe a hung CI job indefinitely).
 */
@Component
public class TimedReplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimedReplicationRunner.class);
    private static final int GRACE_SECONDS = 5;

    private final ReplicationAgent agent;
    private final CancellationRegistry registry;
    private final int totalRunSeconds;

    public TimedReplicationRunner(ReplicationAgent agent,
                                  CancellationRegistry registry,
                                  BrumeProperties brumeProperties) {
        this.agent = agent;
        this.registry = registry;
        this.totalRunSeconds = brumeProperties.timeouts().totalRunSeconds();
        log.info("TimedReplicationRunner configured with total-run-seconds={} (0=disabled)",
                this.totalRunSeconds);
    }

    public void run(CommandEnum command) throws IOException, SQLException, InterruptedException {
        if (totalRunSeconds <= 0) {
            agent.run(command);
            return;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "brume-pipeline");
            thread.setDaemon(true);
            return thread;
        });
        Future<Void> future = executor.submit(() -> {
            agent.run(command);
            return null;
        });
        try {
            future.get(totalRunSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            handleTimeout(future);
            throw new RunTimeoutException(
                    BrumeErrorCode.RUN_TIMEOUT_TOTAL,
                    "Run exceeded brume.timeouts.total-run-seconds=" + totalRunSeconds + "s.",
                    "Raise brume.timeouts.total-run-seconds, or split the workload (see README §Timeouts).",
                    te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof IOException ioe) throw ioe;
            if (cause instanceof SQLException sqle) throw sqle;
            if (cause instanceof InterruptedException ie) throw ie;
            if (cause instanceof Error err) throw err;
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Unexpected Throwable from pipeline task: " + cause.getMessage(), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private void handleTimeout(Future<Void> future) {
        log.warn("Total run timeout fired ({}s) — cancelling pipeline and aborting registered resources",
                totalRunSeconds);
        future.cancel(true);
        registry.requestCancelAll();
        try {
            future.get(GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException stillHung) {
            log.error("Pipeline did not unwind within {}s after cancel — forcing JVM halt(7) "
                    + "to avoid a zombie CI process", GRACE_SECONDS);
            Runtime.getRuntime().halt(7);
        } catch (InterruptedException | ExecutionException | java.util.concurrent.CancellationException expected) {
            // Cancellation propagated to the pipeline thread — fall through to throw below.
        }
    }
}
