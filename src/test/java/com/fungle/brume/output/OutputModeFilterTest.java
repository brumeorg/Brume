package com.fungle.brume.output;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputModeFilterTest {

    @AfterEach
    void clearMode() {
        System.clearProperty(OutputMode.SYSTEM_PROPERTY);
    }

    @Test
    @DisplayName("activeWhen=TEXT — NEUTRAL when no mode set (defaults to TEXT)")
    void neutralWhenModeMatchesDefaultText() {
        OutputModeFilter filter = new OutputModeFilter();
        filter.setActiveWhen("TEXT");

        assertThat(filter.decide(newEvent())).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("activeWhen=JSON — DENY when mode is TEXT")
    void denyWhenModeMismatch() {
        OutputModeFilter filter = new OutputModeFilter();
        filter.setActiveWhen("JSON");

        assertThat(filter.decide(newEvent())).isEqualTo(FilterReply.DENY);
    }

    @Test
    @DisplayName("activeWhen=JSON — NEUTRAL when JSON mode is active via system property")
    void neutralWhenSystemPropertySwitchesMode() {
        System.setProperty(OutputMode.SYSTEM_PROPERTY, "JSON");
        OutputModeFilter filter = new OutputModeFilter();
        filter.setActiveWhen("JSON");

        assertThat(filter.decide(newEvent())).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("activeWhen is case-insensitive")
    void activeWhenAcceptsLowerCase() {
        OutputModeFilter filter = new OutputModeFilter();
        filter.setActiveWhen("text");

        assertThat(filter.decide(newEvent())).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("unknown activeWhen falls back to TEXT (with warn)")
    void unknownValueFallsBack() {
        OutputModeFilter filter = new OutputModeFilter();
        filter.setActiveWhen("garbage");

        assertThat(filter.decide(newEvent())).isEqualTo(FilterReply.NEUTRAL);
    }

    private static LoggingEvent newEvent() {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(new LoggerContext());
        event.setLevel(Level.INFO);
        event.setMessage("test");
        event.setLoggerName("test.logger");
        event.setThreadName("test-thread");
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }
}
