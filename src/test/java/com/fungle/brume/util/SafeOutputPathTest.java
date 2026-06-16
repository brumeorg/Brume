package com.fungle.brume.util;

import com.fungle.brume.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SafeOutputPath} (audit § A4, ADR-0020).
 *
 * <p>Cases are run against an explicit {@code baseDir} (a JUnit temp dir) so the assertions
 * are independent of the JVM's actual working directory.
 */
class SafeOutputPathTest {

    private static final char NUL = (char) 0;

    @TempDir
    Path baseDir;

    @Test
    void validate_acceptsRelativePathUnderBase() {
        Path resolved = SafeOutputPath.validate("dump.sql", "brume.sink.output-path", baseDir);

        assertThat(resolved).isEqualTo(baseDir.resolve("dump.sql"));
    }

    @Test
    void validate_acceptsRelativePathInSubdirectory() {
        Path resolved = SafeOutputPath.validate("reports/exec.html", "brume.report.html-file", baseDir);

        assertThat(resolved).isEqualTo(baseDir.resolve("reports/exec.html"));
    }

    @Test
    void validate_acceptsAbsolutePathUnderBase() {
        Path absoluteUnderBase = baseDir.resolve("dump.sql").toAbsolutePath();

        Path resolved = SafeOutputPath.validate(
                absoluteUnderBase.toString(), "brume.sink.output-path", baseDir);

        assertThat(resolved).isEqualTo(absoluteUnderBase.normalize());
    }

    @Test
    void validate_rejectsAbsolutePathOutsideBase() {
        String outsidePath = System.getProperty("os.name").toLowerCase().contains("win")
                ? "C:\\ProgramData\\brume-evil.sql"
                : "/etc/brume-evil.sql";

        assertThatThrownBy(() ->
                SafeOutputPath.validate(outsidePath, "brume.sink.output-path", baseDir))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.output-path")
                .hasMessageContaining("path traversal rejected");
    }

    @Test
    void validate_rejectsDotDotEscapeAboveBase() {
        assertThatThrownBy(() ->
                SafeOutputPath.validate("../../host-secret", "brume.sink.output-path", baseDir))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("path traversal rejected");
    }

    @Test
    void validate_acceptsDotDotThatStaysUnderBase() {
        Path resolved = SafeOutputPath.validate("a/../b.sql", "brume.sink.output-path", baseDir);

        assertThat(resolved).isEqualTo(baseDir.resolve("b.sql"));
    }

    @Test
    void validate_rejectsNulByteInPath() {
        String withNul = "dump" + NUL + ".sql";

        assertThatThrownBy(() ->
                SafeOutputPath.validate(withNul, "brume.sink.output-path", baseDir))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("NUL byte");
    }

    @Test
    void validate_rejectsNullPath() {
        assertThatThrownBy(() ->
                SafeOutputPath.validate(null, "brume.sink.output-path", baseDir))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void validate_rejectsBlankPath() {
        assertThatThrownBy(() ->
                SafeOutputPath.validate("   ", "brume.sink.output-path", baseDir))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void validate_publicOverloadUsesJvmCwdAsBase() {
        Path resolved = SafeOutputPath.validate(
                "target/should-be-under-cwd.sql", "brume.report.json-file");

        Path cwd = Path.of(".").toAbsolutePath().normalize();
        assertThat(resolved.startsWith(cwd))
                .as("public overload should anchor on JVM cwd '%s' but got '%s'", cwd, resolved)
                .isTrue();
    }
}
