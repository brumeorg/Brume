package com.fungle.brume.init;

import java.io.Console;
import java.io.PrintStream;
import java.util.Locale;

public interface InitPrompter {

    enum Answer { YES, NO, NO_TTY }

    Answer ask(String question, boolean defaultYes);

    void info(String message);

    void warn(String message);

    static InitPrompter forConsole(Console console, PrintStream out, PrintStream err) {
        return console == null ? new NonInteractivePrompter(out, err) : new ConsolePrompter(console, out, err);
    }

    final class ConsolePrompter implements InitPrompter {

        private final Console console;
        private final PrintStream out;
        private final PrintStream err;

        ConsolePrompter(Console console, PrintStream out, PrintStream err) {
            this.console = console;
            this.out = out;
            this.err = err;
        }

        @Override
        public Answer ask(String question, boolean defaultYes) {
            String suffix = defaultYes ? " [Y/n] " : " [y/N] ";
            String raw = console.readLine(question + suffix);
            if (raw == null) return defaultYes ? Answer.YES : Answer.NO;
            String reply = raw.trim().toLowerCase(Locale.ROOT);
            if (reply.isEmpty()) return defaultYes ? Answer.YES : Answer.NO;
            return switch (reply) {
                case "y", "yes", "o", "oui" -> Answer.YES;
                case "n", "no", "non" -> Answer.NO;
                default -> defaultYes ? Answer.YES : Answer.NO;
            };
        }

        @Override
        public void info(String message) {
            out.println(message);
        }

        @Override
        public void warn(String message) {
            err.println(message);
        }
    }

    final class NonInteractivePrompter implements InitPrompter {

        private final PrintStream out;
        private final PrintStream err;

        NonInteractivePrompter(PrintStream out, PrintStream err) {
            this.out = out;
            this.err = err;
        }

        @Override
        public Answer ask(String question, boolean defaultYes) {
            return Answer.NO_TTY;
        }

        @Override
        public void info(String message) {
            out.println(message);
        }

        @Override
        public void warn(String message) {
            err.println(message);
        }
    }
}
