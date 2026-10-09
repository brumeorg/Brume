package com.fungle.brume.init;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BrumeInitCommandTest {

    @Test
    @DisplayName("isInitInvocation true when 'init' is in the arguments")
    void detectsInit() {
        assertThat(BrumeInitCommand.isInitInvocation(List.of("init"))).isTrue();
        assertThat(BrumeInitCommand.isInitInvocation(List.of("init", "jdbc"))).isTrue();
        assertThat(BrumeInitCommand.isInitInvocation(List.of("init", "dump"))).isTrue();
        assertThat(BrumeInitCommand.isInitInvocation(List.of("--json", "init"))).isTrue();
        assertThat(BrumeInitCommand.isInitInvocation(List.of("plan"))).isFalse();
        assertThat(BrumeInitCommand.isInitInvocation(List.of())).isFalse();
    }

    @Test
    @DisplayName("JDBC mode : creates brume.yml and .env.brume from the jdbc template")
    void createsJdbcMode(@TempDir Path tmp) throws IOException {
        FakePrompter prompter = new FakePrompter(List.of("", ""));
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDumpFound17(),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.JDBC)).isEqualTo(0);
        assertThat(Files.exists(tmp.resolve("brume.yml"))).isTrue();
        assertThat(Files.exists(tmp.resolve(".env.brume"))).isTrue();
        assertThat(Files.readString(tmp.resolve(".env.brume")))
                .contains("BRUME_SINK_TYPE=JDBC")
                .contains("BRUME_TARGET_HOST=localhost");
    }

    @Test
    @DisplayName("DUMP mode : .env.brume carries BRUME_SINK_TYPE=DUMP and comments the target block")
    void createsDumpMode(@TempDir Path tmp) throws IOException {
        FakePrompter prompter = new FakePrompter(List.of("", ""));
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDumpFound17(),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.DUMP)).isEqualTo(0);
        String env = Files.readString(tmp.resolve(".env.brume"));
        assertThat(env).contains("BRUME_SINK_TYPE=DUMP");
        assertThat(env).contains("BRUME_SINK_OUTPUT_PATH=");
        assertThat(env).contains("# BRUME_TARGET_HOST=localhost");
    }

    @Test
    @DisplayName("call() with no subcommand delegates to the mode chosen by the prompter")
    void callDelegatesToPrompterMode(@TempDir Path tmp) throws IOException {
        FakePrompter prompter = new FakePrompter(List.of("", ""));
        prompter.chosenMode = InitPrompter.Mode.DUMP;
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDumpFound17(),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.call()).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve(".env.brume"))).contains("BRUME_SINK_TYPE=DUMP");
    }

    @Test
    @DisplayName("skips creation when user answers no")
    void skipsWhenUserSaysNo(@TempDir Path tmp) {
        FakePrompter prompter = new FakePrompter(List.of("n", "n"));
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDumpFound17(),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.JDBC)).isEqualTo(0);
        assertThat(Files.exists(tmp.resolve("brume.yml"))).isFalse();
        assertThat(Files.exists(tmp.resolve(".env.brume"))).isFalse();
    }

    @Test
    @DisplayName("keeps existing files when overwrite prompt default (empty answer)")
    void keepsExistingByDefault(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("brume.yml"), "existing yml");
        Files.writeString(tmp.resolve(".env.brume"), "existing env");
        FakePrompter prompter = new FakePrompter(List.of("", ""));
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDumpFound17(),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.JDBC)).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("brume.yml"))).isEqualTo("existing yml");
        assertThat(Files.readString(tmp.resolve(".env.brume"))).isEqualTo("existing env");
    }

    @Test
    @DisplayName("overwrites existing files when user confirms")
    void overwritesWhenConfirmed(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("brume.yml"), "existing yml");
        Files.writeString(tmp.resolve(".env.brume"), "existing env");
        FakePrompter prompter = new FakePrompter(List.of("y", "y"));
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDumpFound17(),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.JDBC)).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("brume.yml"))).contains("extraction:");
        assertThat(Files.readString(tmp.resolve(".env.brume"))).contains("BRUME_HMAC_SECRET");
    }

    @Test
    @DisplayName("non-interactive shell : creates missing, skips existing")
    void nonInteractiveFallback(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve(".env.brume"), "existing env");
        FakePrompter prompter = new FakePrompter(List.of());
        prompter.forceNoTty = true;

        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDumpFound17(),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.JDBC)).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("brume.yml"))).contains("extraction:");
        assertThat(Files.readString(tmp.resolve(".env.brume"))).isEqualTo("existing env");
    }

    @Test
    @DisplayName("warns loudly but returns 0 when pg_dump is missing")
    void warnsWhenPgDumpMissing(@TempDir Path tmp) {
        FakePrompter prompter = new FakePrompter(List.of("", ""));
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDump(new PgDumpChecker.Result(false, Optional.empty(), Optional.empty())),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.JDBC)).isEqualTo(0);
        assertThat(prompter.warnings).anyMatch(m -> m.contains("pg_dump not found"));
        assertThat(prompter.warnings).anyMatch(m -> m.contains("init continues"));
        assertThat(Files.exists(tmp.resolve("brume.yml"))).isTrue();
    }

    @Test
    @DisplayName("warns when pg_dump version is below 17")
    void warnsWhenPgDumpTooOld(@TempDir Path tmp) {
        FakePrompter prompter = new FakePrompter(List.of("", ""));
        BrumeInitCommand cmd = new BrumeInitCommand(
                fakePgDump(new PgDumpChecker.Result(true, Optional.of(15),
                        Optional.of("pg_dump (PostgreSQL) 15.2"))),
                new OsDetector(),
                new InitFileWriter(tmp),
                prompter);

        assertThat(cmd.runInit(InitPrompter.Mode.JDBC)).isEqualTo(0);
        assertThat(prompter.warnings).anyMatch(m -> m.contains("pg_dump 15 detected"));
    }

    private static PgDumpChecker fakePgDumpFound17() {
        return fakePgDump(new PgDumpChecker.Result(true, Optional.of(17),
                Optional.of("pg_dump (PostgreSQL) 17.0")));
    }

    private static PgDumpChecker fakePgDump(PgDumpChecker.Result result) {
        return new PgDumpChecker() {
            @Override
            public Result probe() {
                return result;
            }
        };
    }

    private static final class FakePrompter implements InitPrompter {

        private final List<String> answers;
        private int cursor;
        private boolean forceNoTty;
        Mode chosenMode = Mode.JDBC;
        final List<String> messages = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        FakePrompter(List<String> answers) {
            this.answers = new ArrayList<>(answers);
        }

        @Override
        public Answer ask(String question, boolean defaultYes) {
            if (forceNoTty) return Answer.NO_TTY;
            if (cursor >= answers.size()) return defaultYes ? Answer.YES : Answer.NO;
            String raw = answers.get(cursor++);
            if (raw == null || raw.isBlank()) return defaultYes ? Answer.YES : Answer.NO;
            String r = raw.trim().toLowerCase();
            return switch (r) {
                case "y", "yes" -> Answer.YES;
                case "n", "no" -> Answer.NO;
                default -> defaultYes ? Answer.YES : Answer.NO;
            };
        }

        @Override
        public Mode chooseMode() {
            return chosenMode;
        }

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }
}
