package com.fungle.brume.output;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Logback filter that gates an appender on the current {@link OutputMode}. Configured per
 * appender in {@code logback-spring.xml}:
 *
 * <pre>{@code
 *   <filter class="com.fungle.brume.output.OutputModeFilter">
 *       <activeWhen>JSON</activeWhen>
 *   </filter>
 * }</pre>
 *
 * <p>Returns {@link FilterReply#NEUTRAL} when the configured mode matches {@link OutputMode#current()},
 * letting subsequent filters (or none) decide, and {@link FilterReply#DENY} otherwise. This means the
 * appender is effectively a no-op when its {@code activeWhen} doesn't match.
 *
 * <p>Reading {@link OutputMode#current()} at each event lets the system property be set pre-boot in
 * {@code BrumeApplication.applyOutputModeFlags()} and have effect immediately (logback configures
 * before Spring binds {@code BrumeProperties}).
 */
public class OutputModeFilter extends Filter<ILoggingEvent> {

    private OutputMode activeWhen = OutputMode.TEXT;

    public void setActiveWhen(String value) {
        if (value == null || value.isBlank()) {
            this.activeWhen = OutputMode.TEXT;
            return;
        }
        try {
            this.activeWhen = OutputMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            addWarn("Unknown OutputMode '" + value + "', defaulting to TEXT");
            this.activeWhen = OutputMode.TEXT;
        }
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return OutputMode.current() == activeWhen ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
