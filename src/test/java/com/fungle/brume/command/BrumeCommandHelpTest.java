package com.fungle.brume.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #17h, GitHub #4 — {@code brume audit --help} was rejected as an unknown option, while the resulting
 * CONFIG_CLI_USAGE error told the user to run {@code brume audit --help}.
 */
class BrumeCommandHelpTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine cl = BrumeUsageErrorHandler.install(withoutAnsi(new CommandLine(new BrumeCommand(null, null, null, null))));
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(err, true));
        return cl.execute(args);
    }

    /**
     * Picocli's {@code Ansi.AUTO} turns colors on under Git Bash on Windows ({@code TERM=xterm}), even
     * into a {@link StringWriter} : the usage then starts with {@code Usage: \e[1mbrume}.
     */
    private static CommandLine withoutAnsi(CommandLine cl) {
        return cl.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
    }

    @ParameterizedTest
    @ValueSource(strings = {"execute", "plan", "dry-run", "init", "scan", "diag", "audit"})
    @DisplayName("#17h, GitHub #4 — every subcommand answers --help with its own usage, exit 0")
    void everySubcommandHasHelp(String subcommand) {
        int code = run(subcommand, "--help");

        assertThat(code).isZero();
        assertThat(out.toString()).startsWith("Usage: brume " + subcommand);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    @DisplayName("#17h, GitHub #4 — `brume audit -h` lists the audit options")
    void auditHelpListsItsOptions() {
        int code = run("audit", "-h");

        assertThat(code).isZero();
        assertThat(out.toString()).contains("--anonymity", "--quasi-id", "--strict", "--k-min",
                "--sample-rate", "--output-json", "--output-markdown");
    }

    @Test
    @DisplayName("#17h, GitHub #4 — `brume init jdbc --help` is answered by the init usage, which documents the sink mode")
    void initHelpDocumentsTheSinkMode() {
        int code = run("init", "jdbc", "--help");

        assertThat(code).isZero();
        assertThat(out.toString()).startsWith("Usage: brume init").contains("jdbc|dump");
    }

    @Test
    @DisplayName("#17h, GitHub #4 — the command a usage error points to actually works")
    void usageErrorPointsToAWorkingHelp() {
        ByteArrayOutputStream report = new ByteArrayOutputStream();
        CommandLine cl = withoutAnsi(new CommandLine(new BrumeCommand(null, null, null, null)));
        cl.setParameterExceptionHandler(new BrumeUsageErrorHandler(
                new BrumeExecutionExceptionHandler(new PrintStream(report, true, StandardCharsets.UTF_8))));
        assertThat(cl.execute("audit", "--report-format", "markdown")).isEqualTo(1);

        Matcher suggested = Pattern.compile("Run 'brume ([^']+)'").matcher(report.toString(StandardCharsets.UTF_8));
        assertThat(suggested.find()).as("usage error suggests a command").isTrue();

        assertThat(run(suggested.group(1).split(" "))).isZero();
        assertThat(out.toString()).startsWith("Usage: brume audit");
    }
}
