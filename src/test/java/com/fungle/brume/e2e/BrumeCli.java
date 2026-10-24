package com.fungle.brume.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the brume binary as a real subprocess, the way a user does from a shell
 * (ADR-0049 Q1) : working directory = the project folder holding {@code .env.brume}
 * and {@code brume.yml}, no TTY on stdin, stdout and stderr captured separately.
 *
 * <p>Any {@code BRUME_*} variable inherited from the CI environment is removed so the
 * only configuration source is the {@code .env.brume} written by the journey.
 */
final class BrumeCli {

    private static final long TIMEOUT_SECONDS = 300;

    record Result(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exit=" + exitCode + "\n--- stdout ---\n" + stdout + "\n--- stderr ---\n" + stderr;
        }
    }

    private final Path binary;
    private final Path workDir;
    private final Map<String, String> extraEnv = new LinkedHashMap<>();

    BrumeCli(Path binary, Path workDir) {
        this.binary = binary;
        this.workDir = workDir;
    }

    /** Sets a variable of the process environment for the next runs (e.g. {@code LC_ALL}). */
    void putEnv(String key, String value) {
        extraEnv.put(key, value);
    }

    Result run(String... args) {
        List<String> cmd = new ArrayList<>();
        if (binary.toString().endsWith(".jar")) {
            cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            cmd.add("-jar");
        }
        cmd.add(binary.toString());
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
        pb.environment().keySet().removeIf(key -> key.startsWith("BRUME_"));
        pb.environment().putAll(extraEnv);
        try {
            Process process = pb.start();
            process.getOutputStream().close(); // no TTY, EOF on any prompt
            Drain out = Drain.start(process.getInputStream(), "brume-e2e-stdout");
            Drain err = Drain.start(process.getErrorStream(), "brume-e2e-stderr");
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("brume " + String.join(" ", args) + " did not exit within "
                        + TIMEOUT_SECONDS + "s.\n--- stdout ---\n" + out.text() + "\n--- stderr ---\n" + err.text());
            }
            return new Result(process.exitValue(), out.awaitText(), err.awaitText());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + cmd, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Reads a process stream on a daemon thread so a full pipe buffer never blocks the child. */
    private record Drain(Thread thread, ByteArrayOutputStream buffer) {

        static Drain start(InputStream in, String threadName) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                try (in) {
                    in.transferTo(buffer);
                } catch (IOException ignored) {
                    // process killed — whatever was read is kept for diagnostics
                }
            }, threadName);
            t.setDaemon(true);
            t.start();
            return new Drain(t, buffer);
        }

        String awaitText() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            return text();
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
