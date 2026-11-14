package com.fungle.brume.command;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.BrumeErrorCode;
import picocli.CommandLine;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.UnmatchedArgumentException;

import java.util.List;

/**
 * Reports a command line that does not parse (unknown subcommand or option, missing or
 * mistyped value) as {@link BrumeErrorCode#CONFIG_CLI_USAGE}, exit code 1 (#17f, ADR-0061).
 *
 * <p>picocli's default prints its message plus the whole usage and exits 2, which ADR-0026
 * reserves for connection failures : a script retrying on 2 would loop on a typo. The output
 * now has the shape of every other Brume error, in TEXT and JSON mode, and points to
 * {@code --help} instead of dumping it.
 */
public final class BrumeUsageErrorHandler implements IParameterExceptionHandler {

    private final BrumeExecutionExceptionHandler reporter;

    public BrumeUsageErrorHandler() {
        this(new BrumeExecutionExceptionHandler());
    }

    BrumeUsageErrorHandler(BrumeExecutionExceptionHandler reporter) {
        this.reporter = reporter;
    }

    /** Installs this handler on {@code commandLine} and returns it, for chaining. */
    public static CommandLine install(CommandLine commandLine) {
        return commandLine.setParameterExceptionHandler(new BrumeUsageErrorHandler());
    }

    @Override
    public int handleParseException(ParameterException ex, String[] args) {
        return reporter.report(toConfigurationException(ex));
    }

    static ConfigurationException toConfigurationException(ParameterException ex) {
        String command = ex.getCommandLine() == null ? "brume" : ex.getCommandLine().getCommandSpec().qualifiedName();
        if (!command.startsWith("brume")) command = "brume " + command; // `init` runs as its own root
        StringBuilder suggestion = new StringBuilder();
        if (ex instanceof UnmatchedArgumentException unmatched) {
            List<String> candidates = unmatched.getSuggestions();
            if (candidates != null && !candidates.isEmpty()) {
                suggestion.append("Did you mean ").append(String.join(" or ", candidates.stream()
                        .limit(3).map(c -> "'" + c + "'").toList())).append("? ");
            } else if (ex.getCommandLine() != null && !ex.getCommandLine().getSubcommands().isEmpty()) {
                suggestion.append("Available commands : ")
                        .append(String.join(", ", ex.getCommandLine().getSubcommands().keySet())).append(". ");
            }
        }
        suggestion.append("Run '").append(command).append(" --help' for the list of commands and options.");
        return new ConfigurationException(BrumeErrorCode.CONFIG_CLI_USAGE, ex.getMessage(), suggestion.toString(), ex);
    }
}
