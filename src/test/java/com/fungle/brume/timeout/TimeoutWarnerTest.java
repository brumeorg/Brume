package com.fungle.brume.timeout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimeoutWarner} (#23 / A21, ADR-0033) — covers (a) the WARN fires
 * around the 50% mark, (b) {@link TimeoutWarner.Handle#close()} cancels the WARN before
 * it fires, (c) the no-op handle short-circuits for sub-second timeouts.
 */
class TimeoutWarnerTest {

    private TimeoutWarner warner;
    private ListAppender<ILoggingEvent> appender;
    private Logger timeoutLogger;

    @BeforeEach
    void setUp() {
        warner = new TimeoutWarner();
        appender = new ListAppender<>();
        appender.start();
        timeoutLogger = (Logger) LoggerFactory.getLogger("brume.timeout");
        timeoutLogger.addAppender(appender);
        timeoutLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        timeoutLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("WARN fires around the 50% mark when the handle is not closed")
    void warn_fires_at_halfway() throws InterruptedException {
        // 2s timeout → WARN at ~1s. Poll up to 1.5s for the WARN to materialise.
        TimeoutWarner.Handle handle = warner.scheduleHalfwayWarning("op-A", 2);
        long deadline = System.currentTimeMillis() + 1_500;
        while (appender.list.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        handle.close();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("op-A")
                .contains("configured limit 2s");
    }

    @Test
    @DisplayName("close() cancels the WARN before it fires (fast path)")
    void close_cancels_before_fire() throws InterruptedException {
        try (TimeoutWarner.Handle h = warner.scheduleHalfwayWarning("op-fast", 10)) {
            // Close immediately; WARN was scheduled for ~5s away.
        }
        Thread.sleep(200);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("timeout <= 1s returns the NOOP handle — no scheduling, no WARN")
    void short_timeout_is_noop() throws InterruptedException {
        try (TimeoutWarner.Handle h = warner.scheduleHalfwayWarning("op-tiny", 1)) {
            assertThat(h).isSameAs(TimeoutWarner.Handle.NOOP);
        }
        Thread.sleep(200);
        assertThat(appender.list).isEmpty();
    }
}
