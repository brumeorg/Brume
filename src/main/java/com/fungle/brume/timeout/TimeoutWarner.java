package com.fungle.brume.timeout;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules a WARN log line halfway through a bounded query's allowed wall-clock, so an
 * operator running interactively sees friction building before the statement timeout
 * actually fires (#23 / A21 — proactive WARN at 50%, ADR-0033).
 *
 * <p>Single-thread daemon executor — one timer task per bounded query, cancelled on the
 * normal return path via the returned {@link Handle}. Low overhead: scheduling and
 * cancellation are amortised against query cost (queries below a few ms wouldn't observe
 * the WARN anyway because the cancel races the fire).
 *
 * <p>Logs to the dedicated {@code brume.timeout} category so operators can isolate the
 * WARN stream from generic Brume logs (e.g. via Logback level config or a sidecar).
 */
@Component
public class TimeoutWarner {

    private static final Logger log = LoggerFactory.getLogger("brume.timeout");

    private final ScheduledExecutorService scheduler;

    public TimeoutWarner() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "brume-timeout-warner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedules a single WARN to fire at half the configured timeout. The returned
     * {@link Handle} cancels the scheduled task — call it via try-with-resources at the
     * end of the bounded work so the WARN never fires for fast-completing queries.
     *
     * @param operationName  human-readable identifier of the bounded operation (shows up in the log line)
     * @param timeoutSeconds the configured statement timeout for this operation
     * @return an {@link AutoCloseable} handle that cancels the pending WARN
     */
    public Handle scheduleHalfwayWarning(String operationName, int timeoutSeconds) {
        if (timeoutSeconds <= 1) {
            return Handle.NOOP;
        }
        long delayMs = timeoutSeconds * 500L;
        long start = System.currentTimeMillis();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            long elapsedSec = (System.currentTimeMillis() - start) / 1000;
            log.warn("Statement '{}' at {}s, configured limit {}s",
                    operationName, elapsedSec, timeoutSeconds);
        }, delayMs, TimeUnit.MILLISECONDS);
        return new Handle(future);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Cancellation handle returned by {@link #scheduleHalfwayWarning}; idempotent on close. */
    public static final class Handle implements AutoCloseable {

        static final Handle NOOP = new Handle(null);

        private final ScheduledFuture<?> future;

        Handle(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public void close() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
