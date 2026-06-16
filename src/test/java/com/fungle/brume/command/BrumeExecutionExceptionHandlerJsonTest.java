package com.fungle.brume.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.error.ConnectionException;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.output.OutputMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JSON branch of {@link BrumeExecutionExceptionHandler} when
 * {@code brume.output.mode=JSON}. The output must be a single line of valid JSON
 * on stderr, shaped as {@code {"error":{...},"exitCode":N}}.
 */
class BrumeExecutionExceptionHandlerJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void enableJson() {
        System.setProperty(OutputMode.SYSTEM_PROPERTY, "JSON");
    }

    @AfterEach
    void disableJson() {
        System.clearProperty(OutputMode.SYSTEM_PROPERTY);
        System.clearProperty("brume.show-stacktrace");
    }

    @Test
    @DisplayName("BrumeException → single-line JSON with code, message, suggestion and exitCode")
    void brumeExceptionEmitsJson() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        int code = handler.handleExecutionException(
                new ConnectionException(BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE,
                        "cannot connect to source DB",
                        "Check replication.source.url and credentials.",
                        null),
                null, null);

        assertThat(code).isEqualTo(2);
        String out = buf.toString(StandardCharsets.UTF_8).trim();
        assertThat(out.lines().count()).as("must be a single line").isEqualTo(1);

        JsonNode node = mapper.readTree(out);
        assertThat(node.get("exitCode").asInt()).isEqualTo(2);
        assertThat(node.get("error").get("code").asText()).isEqualTo("CONNECTION_SOURCE_UNREACHABLE");
        assertThat(node.get("error").get("message").asText()).isEqualTo("cannot connect to source DB");
        assertThat(node.get("error").get("suggestion").asText())
                .isEqualTo("Check replication.source.url and credentials.");
        assertThat(node.get("error").get("stack")).isNull();
    }

    @Test
    @DisplayName("BrumeException + brume.show-stacktrace=true → includes stack field")
    void includesStackWhenFlagSet() throws Exception {
        System.setProperty("brume.show-stacktrace", "true");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        handler.handleExecutionException(
                new ConnectionException(BrumeErrorCode.CONNECTION_TARGET_UNREACHABLE,
                        "cannot connect to target DB",
                        "Check replication.target.url and credentials.",
                        new RuntimeException("underlying cause")),
                null, null);

        JsonNode node = mapper.readTree(buf.toString(StandardCharsets.UTF_8).trim());
        assertThat(node.get("error").get("stack").asText())
                .contains("ConnectionException")
                .contains("underlying cause");
    }

    @Test
    @DisplayName("Non-Brume exception → UNEXPECTED code, exit 127, stack included")
    void unexpectedExceptionEmitsJson() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        int code = handler.handleExecutionException(
                new NullPointerException("npe in pipeline"), null, null);

        assertThat(code).isEqualTo(127);
        String out = buf.toString(StandardCharsets.UTF_8).trim();
        assertThat(out.lines().count()).as("must be a single line").isEqualTo(1);

        JsonNode node = mapper.readTree(out);
        assertThat(node.get("exitCode").asInt()).isEqualTo(127);
        assertThat(node.get("error").get("code").asText()).isEqualTo("UNEXPECTED");
        assertThat(node.get("error").get("message").asText())
                .contains("NullPointerException")
                .contains("npe in pipeline");
        assertThat(node.get("error").get("stack").asText()).contains("NullPointerException");
    }

    @Test
    @DisplayName("BrumeException without suggestion → 'suggestion' field omitted from JSON")
    void omitsSuggestionWhenNull() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BrumeExecutionExceptionHandler handler = new BrumeExecutionExceptionHandler(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        handler.handleExecutionException(
                new ConnectionException(BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE,
                        "connection failed",
                        null,
                        null),
                null, null);

        JsonNode node = mapper.readTree(buf.toString(StandardCharsets.UTF_8).trim());
        assertThat(node.get("error").get("suggestion")).isNull();
    }
}
