package com.fungle.brume.init;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Best-effort smoke test for {@code brume init <jdbc|dump>} executed through the
 * repackaged fat-jar — mirrors {@link com.fungle.brume.diag.PackagedJarSmokeIT}.
 *
 * <p>Catches the class of bugs where a subcommand is declared in picocli but the
 * {@code CommandLine.execute(...)} call doesn't pass the args after {@code init},
 * so routing silently falls back to the parent {@code call()}. That exact bug was
 * caught manually during ADR-0047 feature development ; this IT would have caught it
 * before it reached master.
 *
 * <p><b>Skip policy</b> (identical to PackagedJarSmokeIT) : if {@code target/brume-*.jar}
 * is absent or ambiguous, we skip with {@link Assumptions#assumeTrue(boolean, String)} —
 * a plain {@code ./mvnw test} run (no {@code package}) stays green.
 *
 * <p>To run locally :
 * <pre>{@code
 *   ./mvnw package -DskipTests
 *   ./mvnw test -Dtest=BrumeInitPackagedIT
 * }</pre>
 */
class BrumeInitPackagedIT {

    private static final Logger log = LoggerFactory.getLogger(BrumeInitPackagedIT.class);
    private static final Path TARGET_DIR = Paths.get("target");

    @Test
    @DisplayName("`brume init jdbc` on packaged jar writes .env.brume with BRUME_SINK_TYPE=JDBC")
    void initJdbcCreatesJdbcFlavouredEnvFile(@TempDir Path cwd) throws Exception {
        Path jar = locateUniqueRepackagedJar();
        Assumptions.assumeTrue(jar != null,
                "Skipped — repackaged jar not found in target/. Run `./mvnw package -DskipTests` first.");

        RunResult result = runInit(jar, cwd, "init", "jdbc");

        assertThat(result.exitCode)
                .as("`brume init jdbc` must exit 0. Output:\n%s", result.outputJoined())
                .isZero();
        assertThat(Files.exists(cwd.resolve(".env.brume")))
                .as(".env.brume must be created in cwd")
                .isTrue();
        String envContent = Files.readString(cwd.resolve(".env.brume"));
        assertThat(envContent)
                .as("JDBC template must carry BRUME_SINK_TYPE=JDBC + active TARGET block")
                .contains("BRUME_SINK_TYPE=JDBC")
                .contains("BRUME_TARGET_HOST=localhost");
        assertThat(Files.exists(cwd.resolve("brume.yml"))).isTrue();
    }

    @Test
    @DisplayName("`brume init dump` on packaged jar writes .env.brume with BRUME_SINK_TYPE=DUMP + commented TARGET")
    void initDumpCreatesDumpFlavouredEnvFile(@TempDir Path cwd) throws Exception {
        Path jar = locateUniqueRepackagedJar();
        Assumptions.assumeTrue(jar != null,
                "Skipped — repackaged jar not found in target/. Run `./mvnw package -DskipTests` first.");

        RunResult result = runInit(jar, cwd, "init", "dump");

        assertThat(result.exitCode)
                .as("`brume init dump` must exit 0. Output:\n%s", result.outputJoined())
                .isZero();
        String envContent = Files.readString(cwd.resolve(".env.brume"));
        assertThat(envContent)
                .as("DUMP template must carry BRUME_SINK_TYPE=DUMP and comment the TARGET block")
                .contains("BRUME_SINK_TYPE=DUMP")
                .contains("BRUME_SINK_OUTPUT_PATH=")
                .contains("# BRUME_TARGET_HOST=localhost");
    }

    @Test
    @DisplayName("`brume init foo` fails with picocli exit code 2 (unmatched argument)")
    void initWithUnknownSubcommandExitsTwo(@TempDir Path cwd) throws Exception {
        Path jar = locateUniqueRepackagedJar();
        Assumptions.assumeTrue(jar != null,
                "Skipped — repackaged jar not found in target/. Run `./mvnw package -DskipTests` first.");

        RunResult result = runInit(jar, cwd, "init", "foo");

        assertThat(result.exitCode)
                .as("unknown subcommand must return picocli's standard exit code 2. Output:\n%s",
                        result.outputJoined())
                .isEqualTo(2);
        assertThat(result.outputJoined())
                .as("usage output must mention the valid subcommands")
                .contains("jdbc")
                .contains("dump");
        assertThat(Files.exists(cwd.resolve(".env.brume")))
                .as("no file should be created on a failed invocation")
                .isFalse();
    }

    private record RunResult(int exitCode, List<String> output) {
        String outputJoined() { return String.join("\n", output); }
    }

    private static RunResult runInit(Path jar, Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());
        for (String a : args) cmd.add(a);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        List<String> output = drainAsync(process);

        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("Packaged jar did not exit within 60s. Output:\n"
                    + String.join("\n", output));
        }
        return new RunResult(process.exitValue(), output);
    }

    /**
     * Locates the most recently modified repackaged jar in {@code target/}. Unlike
     * {@code PackagedJarSmokeIT} which skips on ambiguity, we pick the newest mtime
     * when several versioned jars are present (common dev workflow where {@code mvn package}
     * runs leave {@code brume-1.3.jar}, {@code brume-1.4.jar}, … side-by-side). This keeps
     * the IT useful without forcing {@code mvn clean} between every run.
     */
    private static Path locateUniqueRepackagedJar() throws IOException {
        if (!Files.isDirectory(TARGET_DIR)) return null;
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(TARGET_DIR, "brume-*.jar")) {
            for (Path p : stream) {
                if (p.getFileName().toString().endsWith(".jar.original")) continue;
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return null;
        Path newest = candidates.get(0);
        for (Path p : candidates) {
            if (Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(newest)) > 0) {
                newest = p;
            }
        }
        if (candidates.size() > 1) {
            log.info("Multiple candidate jars found in target/; picking newest by mtime: {}", newest);
        }
        return newest;
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        String exe = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Paths.get(javaHome, "bin", exe).toString();
    }

    private static List<String> drainAsync(Process process) {
        List<String> out = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.add(line);
                }
            } catch (IOException e) {
                out.add("[drain error] " + e.getMessage());
            }
        }, "init-smoke-output-drain");
        t.setDaemon(true);
        t.start();
        return out;
    }
}
