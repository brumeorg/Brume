package com.fungle.brume.diag;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Best-effort smoke test for the repackaged fat-jar (#75 A32, Couche 2).
 *
 * <p>Spawns {@code java -jar target/brume-*.jar diag} as a real subprocess and asserts
 * the boot returns exit code 0. This is the only test in the suite that exercises the
 * Spring-Boot-repackaged jar (with {@code BOOT-INF/}, {@code META-INF/spring.factories},
 * etc.) end-to-end — it catches the class of bugs that {@code ApplicationContextWiringIT}
 * (#74) cannot see because it runs on Maven {@code target/classes} directly.
 *
 * <p><b>Skip policy</b> (Q3 reco B from feature-cycle decision tables, 2026-05-12):
 * <ul>
 *   <li>If {@code target/brume-*.jar} does not exist (typical {@code mvn test} run
 *       without {@code package}), the test is skipped with a clear warning.</li>
 *   <li>If multiple candidate jars are found, the test is also skipped — we don't
 *       want to silently pick the wrong one.</li>
 * </ul>
 *
 * <p>This best-effort posture is the trade-off accepted in feature-cycle Q3: full
 * cross-OS robustness (failsafe binding, deadlock-free pipes, Windows/Linux quoting,
 * timestamp-based freshness checks) is deferred to a future hardening ticket
 * (T-A32e). What this IT delivers today is good enough to catch the
 * "{@code @Autowired} missing on a public ctor" class of bugs reported as #23i.
 *
 * <p>To run locally:
 * <pre>{@code
 *   ./mvnw package -DskipTests
 *   ./mvnw test -Dtest=PackagedJarSmokeIT
 * }</pre>
 */
class PackagedJarSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(PackagedJarSmokeIT.class);
    private static final Path TARGET_DIR = Paths.get("target");

    @Test
    @DisplayName("packaged jar boots and exits 0 with `diag` (#75 A32 regression guard)")
    void packagedJarDiagExitsZero() throws Exception {
        Path jar = locateUniqueRepackagedJar();
        Assumptions.assumeTrue(jar != null,
                "Skipped — repackaged jar not found in target/. Run `./mvnw package -DskipTests` first.");

        log.info("Smoke testing packaged jar: {}", jar);

        ProcessBuilder pb = new ProcessBuilder(
                javaExecutable(),
                "-Dbrume.hmac-secret=smoke-test-16bytes",
                "-Dbrume.fpe-key=smoke-fpe-key-xx",
                "-Dbrume.config-path=src/test/resources/test-config.yaml",
                "-Dreplication.source.url=jdbc:postgresql://127.0.0.1:9999/none",
                "-Dreplication.source.username=test",
                "-Dreplication.source.password=test",
                "-Dreplication.target.url=jdbc:postgresql://127.0.0.1:9999/none",
                "-Dreplication.target.username=test",
                "-Dreplication.target.password=test",
                "-Dreplication.schema=test_schema",
                "-jar", jar.toString(),
                "diag"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        // Drain output continuously to prevent the OS pipe buffer from filling up
        // and deadlocking the subprocess. Captured for diagnostics on failure.
        List<String> output = drainAsync(process);

        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            org.junit.jupiter.api.Assertions.fail(
                    "Packaged jar did not exit within 60s. Output so far:\n" + String.join("\n", output));
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            org.junit.jupiter.api.Assertions.fail(
                    "Packaged jar exited with code " + exitCode + ". Output:\n" + String.join("\n", output));
        }

        // Sanity check that the output actually looks like our diag report,
        // not some other command that accidentally returned 0.
        String joined = String.join("\n", output);
        assertThat(joined)
                .as("diag output must contain the report header")
                .contains("diagnostic");
        assertThat(joined)
                .as("diag output must confirm no DB contact")
                .containsAnyOf("No DB contacted: yes", "\"noDbContacted\":true", "\"noDbContacted\" : true");
    }

    /**
     * Locates the single repackaged Spring-Boot jar in {@code target/}. The
     * {@code spring-boot-maven-plugin} {@code repackage} goal renames the original
     * artifact to {@code *.jar.original} and writes the fat-jar to {@code *.jar},
     * so a glob on {@code *.jar} normally matches exactly one file. If 0 or >1 are
     * found, return {@code null} and let the caller skip the test.
     */
    private static Path locateUniqueRepackagedJar() throws IOException {
        if (!Files.isDirectory(TARGET_DIR)) return null;
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(TARGET_DIR, "brume-*.jar")) {
            for (Path p : stream) {
                // Skip the original (pre-repackage) artifact if it's still around.
                if (p.getFileName().toString().endsWith(".jar.original")) continue;
                candidates.add(p);
            }
        }
        if (candidates.size() != 1) {
            if (candidates.size() > 1) {
                log.warn("Multiple candidate jars found in target/; skipping. Found: {}", candidates);
            }
            return null;
        }
        return candidates.get(0);
    }

    /**
     * Resolves the path to the {@code java} executable currently running the JVM, so the
     * spawned subprocess uses the same JDK as the test (avoids picking up a stale
     * {@code java} on {@code PATH} that might not even be JDK 25).
     */
    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        String exe = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Paths.get(javaHome, "bin", exe).toString();
    }

    /**
     * Drains the subprocess stdout (merged with stderr via {@code redirectErrorStream})
     * on a daemon thread so the pipe buffer can never fill up and deadlock the child
     * process. Returns the list it appends to — caller can inspect it after {@code waitFor}.
     */
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
        }, "packaged-jar-output-drain");
        t.setDaemon(true);
        t.start();
        return out;
    }
}
