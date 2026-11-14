package com.fungle.brume.command;

import com.fungle.brume.output.OutputMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #17f (ADR-0061) — picocli reported a bad command line with exit code 2, the code ADR-0026
 * reserves for connection failures, followed by the whole usage.
 */
class BrumeUsageErrorHandlerTest {

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @AfterEach
    void resetMode() {
        System.clearProperty(OutputMode.SYSTEM_PROPERTY);
    }

    private int run(String... args) {
        CommandLine cl = new CommandLine(new BrumeCommand(null, null, null, null));
        cl.setParameterExceptionHandler(new BrumeUsageErrorHandler(
                new BrumeExecutionExceptionHandler(new PrintStream(err, true, StandardCharsets.UTF_8))));
        return cl.execute(args);
    }

    @Test
    @DisplayName("#17f — unknown option : CONFIG_CLI_USAGE, exit 1, points to --help")
    void unknownOption() {
        int code = run("plan", "--nope");

        String out = err.toString(StandardCharsets.UTF_8);
        assertThat(code).isEqualTo(1);
        assertThat(out).contains("[CONFIG_CLI_USAGE] Unknown option: '--nope'")
                .contains("Run 'brume plan --help'")
                .contains("→ Exit code: 1")
                .doesNotContain("Usage:");
    }

    @Test
    void mistypedSubcommandGetsASuggestion() {
        int code = run("excute");

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Did you mean 'execute'?");
    }

    @Test
    void jsonModeEmitsTheJsonError() {
        System.setProperty(OutputMode.SYSTEM_PROPERTY, "JSON");

        int code = run("audit", "--anonymity", "--k-min", "abc");

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .startsWith("{\"error\":{\"code\":\"CONFIG_CLI_USAGE\"")
                .contains("\"exitCode\":1");
    }
}
