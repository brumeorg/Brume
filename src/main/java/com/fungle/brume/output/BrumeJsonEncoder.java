package com.fungle.brume.output;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Minimal JSON encoder for Logback events — used by {@code logback-spring.xml} when
 * {@link OutputMode#JSON} is active (ADR-0030).
 *
 * <p>Emits one JSON object per line with a fixed shape:
 * <pre>{@code
 * {"ts":"2026-05-11T14:23:01.123Z","level":"INFO","logger":"com.fungle.brume.X","msg":"...","thread":"main"}
 * }</pre>
 *
 * <p>When the event has a throwable, the encoder appends {@code "throwable":{"class","message","stack"}}
 * with a single-string {@code stack} field containing the full stack trace separated by {@code \n}.
 *
 * <p>The shape is intentionally small (no MDC, no caller, no markers) and stable so external log
 * consumers can rely on the field names. If a richer ECS-shaped format is needed later, swap this
 * encoder for {@code logstash-logback-encoder} via dep injection — no code change elsewhere.
 *
 * <h2>Implementation notes</h2>
 * <ul>
 *   <li>No Jackson dep — manual escape keeps this encoder usable before/during Spring boot.</li>
 *   <li>String fields are escaped per RFC 8259 ({@code \"}, {@code \\}, control chars below {@code 0x20}).</li>
 *   <li>Each event ends with a single {@code \n} — required for line-delimited JSON consumers (jq, fluentd).</li>
 * </ul>
 */
public class BrumeJsonEncoder extends EncoderBase<ILoggingEvent> {

    @Override
    public byte[] headerBytes() {
        return new byte[0];
    }

    @Override
    public byte[] footerBytes() {
        return new byte[0];
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendString(sb, "ts", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        sb.append(',');
        appendString(sb, "level", event.getLevel().toString());
        sb.append(',');
        appendString(sb, "logger", event.getLoggerName());
        sb.append(',');
        appendString(sb, "msg", event.getFormattedMessage());
        sb.append(',');
        appendString(sb, "thread", event.getThreadName());

        IThrowableProxy tp = event.getThrowableProxy();
        if (tp != null) {
            sb.append(",\"throwable\":{");
            appendString(sb, "class", tp.getClassName());
            sb.append(',');
            appendString(sb, "message", tp.getMessage() == null ? "" : tp.getMessage());
            sb.append(',');
            sb.append("\"stack\":\"");
            appendStackEscaped(sb, tp);
            sb.append('"');
            sb.append('}');
        }

        sb.append('}').append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append('"').append(':').append('"');
        appendEscaped(sb, value);
        sb.append('"');
    }

    private static void appendEscaped(StringBuilder sb, String value) {
        if (value == null) return;
        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    private static void appendStackEscaped(StringBuilder sb, IThrowableProxy tp) {
        IThrowableProxy current = tp;
        boolean firstFrame = true;
        while (current != null) {
            if (!firstFrame) {
                sb.append("\\nCaused by: ");
            } else {
                firstFrame = false;
            }
            appendEscaped(sb, current.getClassName());
            String msg = current.getMessage();
            if (msg != null) {
                sb.append(": ");
                appendEscaped(sb, msg);
            }
            for (StackTraceElementProxy frame : current.getStackTraceElementProxyArray()) {
                sb.append("\\n\\tat ");
                appendEscaped(sb, frame.getSTEAsString());
            }
            current = current.getCause();
        }
    }
}
