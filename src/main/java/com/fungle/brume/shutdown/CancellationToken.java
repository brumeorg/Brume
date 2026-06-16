package com.fungle.brume.shutdown;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide flag set by {@link CancellationRegistry#requestCancelAll()} when SIGTERM
 * fires. Consumed by pipeline check points via {@link #checkpoint()} (#24 / A22).
 *
 * <p>A check point is a one-line call inserted between unbounded iterations
 * (tables, chunks, FK descent levels) that throws {@link CancellationException} when the
 * flag is set. On the hot path (cancel never requested) it is a single
 * {@link AtomicBoolean#get()} — measured negligible vs the pipeline base cost.
 *
 * <p>Distinct from {@link CancellationRegistry} on purpose: the token answers
 * "should I keep going?" (cheap, called everywhere), the registry answers "what's holding
 * a blocking resource I must un-block?" (called once from the shutdown hook).
 */
@Component
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Marks the pipeline as cancelled. Idempotent — every call after the first is a no-op. */
    public void requestCancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws {@link CancellationException} if the flag is set, otherwise returns instantly.
     * Place at the start of long-running iterations (table loop, chunk loop, FK descent).
     */
    public void checkpoint() {
        if (cancelled.get()) {
            throw new CancellationException();
        }
    }
}
