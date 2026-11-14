package com.fungle.brume;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.BrumeErrorCode;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * #17c (ADR-0061) — a duplicated key or a malformed line in {@code .env.brume} used to
 * print a raw dotenv-java stack trace.
 */
class DotEnvErrorsTest {

    @TempDir
    Path dir;

    private RuntimeException loadFailure(String content) throws IOException {
        Files.writeString(dir.resolve(".env.brume"), content);
        return catchThrowableOfType(RuntimeException.class, () -> Dotenv.configure()
                .directory(dir.toString()).filename(".env.brume").load());
    }

    @Test
    @DisplayName("#17c — duplicated key : names the key and both lines, never the values")
    void duplicatedKey() throws IOException {
        RuntimeException failure = loadFailure("BRUME_SINK_TYPE=JDBC\nBRUME_SCHEMA=public\nBRUME_SINK_TYPE=s3cr3t-dump\n");

        ConfigurationException ce = DotEnvErrors.translate(failure, dir.resolve(".env.brume"));

        assertThat(ce.code()).isEqualTo(BrumeErrorCode.CONFIG_ENV_FILE_INVALID);
        assertThat(ce.getMessage()).isEqualTo(".env.brume declares BRUME_SINK_TYPE more than once (lines 1, 3).");
        assertThat(ce.getMessage() + ce.suggestion()).doesNotContain("s3cr3t").doesNotContain("JDBC");
        assertThat(ce.getCause()).as("dotenv's message quotes the values").isNull();
    }

    @Test
    @DisplayName("#17c — malformed line : gives the line number, never its content (it may hold a secret)")
    void malformedLine() throws IOException {
        RuntimeException failure = loadFailure("BRUME_SCHEMA=public\nBRUME_HMAC_SECRET s3cr3t-value\n");

        ConfigurationException ce = DotEnvErrors.translate(failure, dir.resolve(".env.brume"));

        assertThat(ce.code()).isEqualTo(BrumeErrorCode.CONFIG_ENV_FILE_INVALID);
        assertThat(ce.getMessage()).isEqualTo(".env.brume has a malformed line (line 2).");
        assertThat(ce.getMessage() + ce.suggestion()).doesNotContain("s3cr3t");
        assertThat(ce.getCause()).isNull();
    }
}
