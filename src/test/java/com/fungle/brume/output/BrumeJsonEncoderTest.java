package com.fungle.brume.output;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BrumeJsonEncoderTest {

    private final BrumeJsonEncoder encoder = new BrumeJsonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Encodes an info event to a single line of valid JSON with the documented field shape")
    void encodesInfoEventToValidJson() throws Exception {
        byte[] encoded = encoder.encode(newEvent(Level.INFO, "test.logger", "hello world", null));

        String line = new String(encoded, StandardCharsets.UTF_8);
        assertThat(line).endsWith("\n");

        JsonNode node = mapper.readTree(line);
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("logger").asText()).isEqualTo("test.logger");
        assertThat(node.get("msg").asText()).isEqualTo("hello world");
        assertThat(node.get("thread").asText()).isEqualTo("test-thread");
        assertThat(node.get("ts").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\..*Z");
        assertThat(node.get("throwable")).isNull();
    }

    @Test
    @DisplayName("Escapes quotes, backslashes, control characters")
    void escapesDifficultCharacters() throws Exception {
        byte[] encoded = encoder.encode(newEvent(Level.WARN, "x",
                "tab\there\nnewline \"quoted\" \\back", null));

        JsonNode node = mapper.readTree(new String(encoded, StandardCharsets.UTF_8));
        assertThat(node.get("msg").asText()).isEqualTo("tab\there\nnewline \"quoted\" \\back");
    }

    @Test
    @DisplayName("Serializes throwable with class, message, stack frames separated by \\n")
    void serializesThrowableProxy() throws Exception {
        IllegalStateException ex = new IllegalStateException("boom");
        byte[] encoded = encoder.encode(newEvent(Level.ERROR, "x", "failure", ex));

        JsonNode node = mapper.readTree(new String(encoded, StandardCharsets.UTF_8));
        JsonNode tp = node.get("throwable");
        assertThat(tp).isNotNull();
        assertThat(tp.get("class").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(tp.get("message").asText()).isEqualTo("boom");
        assertThat(tp.get("stack").asText())
                .startsWith("java.lang.IllegalStateException: boom")
                .contains("at ");
    }

    @Test
    @DisplayName("Serializes nested cause as 'Caused by: ...' separator inside stack")
    void serializesNestedCause() throws Exception {
        RuntimeException root = new RuntimeException("root");
        IllegalStateException wrapped = new IllegalStateException("wrapped", root);
        byte[] encoded = encoder.encode(newEvent(Level.ERROR, "x", "failure", wrapped));

        JsonNode node = mapper.readTree(new String(encoded, StandardCharsets.UTF_8));
        String stack = node.get("throwable").get("stack").asText();
        assertThat(stack).contains("Caused by: java.lang.RuntimeException: root");
    }

    private static LoggingEvent newEvent(Level level, String logger, String msg, Throwable t) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(new LoggerContext());
        event.setLevel(level);
        event.setMessage(msg);
        event.setLoggerName(logger);
        event.setThreadName("test-thread");
        event.setTimeStamp(System.currentTimeMillis());
        if (t != null) {
            event.setThrowableProxy(new ThrowableProxy(t));
        }
        return event;
    }
}
