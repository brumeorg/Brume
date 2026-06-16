package com.fungle.brume.config;

import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.SinkType;
import com.fungle.brume.plan.PlanMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link BrumeProperties#toString()} and {@link ReplicationProperties.Source#toString()} /
 * {@link ReplicationProperties.Target#toString()} mask their sensitive fields.
 *
 * <p>Without masking, an incidental {@code log.X("config: {}", props)} or stack trace would leak
 * the HMAC secret, the FPE key, or the database credentials into the log stream. ADR-0025
 * documents the masking convention.
 */
class SecretMaskingTest {

    private static final String KNOWN_HMAC_SECRET = "this-is-the-hmac-secret-1234";
    private static final String KNOWN_FPE_KEY = "0123456789abcdef0123456789abcdef";
    private static final String KNOWN_PASSWORD = "p@ssw0rd-leak-canary";
    private static final String KNOWN_USERNAME = "alice-leak-canary";
    private static final String KNOWN_URL = "jdbc:postgresql://prod-db.example.com:5432/postgres";

    @Test
    @DisplayName("BrumeProperties.toString masks hmacSecret and fpeKey")
    void brumePropertiesMaskSecrets() {
        BrumeProperties props = new BrumeProperties(
                "config.yaml",
                KNOWN_HMAC_SECRET,
                KNOWN_FPE_KEY,
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""),
                new BrumeProperties.SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new BrumeProperties.JdbcSinkProperties(CopyMode.PREFER)),
                new BrumeProperties.PlanProperties(PlanMode.EXACT)
        );

        String s = props.toString();

        assertThat(s).doesNotContain(KNOWN_HMAC_SECRET);
        assertThat(s).doesNotContain(KNOWN_FPE_KEY);
        assertThat(s).contains("hmacSecret=***").contains("fpeKey=***");
        // Sanity: non-sensitive fields are still readable for debug
        assertThat(s).contains("configPath=config.yaml");
        assertThat(s).contains("hmacAlgorithm=HmacSHA256");
    }

    @Test
    @DisplayName("ReplicationProperties.Source.toString masks url, username and password")
    void sourceMasksAllConnectionFields() {
        ReplicationProperties.Source source = new ReplicationProperties.Source(
                KNOWN_URL, KNOWN_USERNAME, KNOWN_PASSWORD);

        String s = source.toString();

        assertThat(s)
                .doesNotContain(KNOWN_URL)
                .doesNotContain(KNOWN_USERNAME)
                .doesNotContain(KNOWN_PASSWORD);
        assertThat(s).isEqualTo("Source[url=***, username=***, password=***]");
    }

    @Test
    @DisplayName("ReplicationProperties.Target.toString masks url, username and password")
    void targetMasksAllConnectionFields() {
        ReplicationProperties.Target target = new ReplicationProperties.Target(
                KNOWN_URL, KNOWN_USERNAME, KNOWN_PASSWORD);

        String s = target.toString();

        assertThat(s)
                .doesNotContain(KNOWN_URL)
                .doesNotContain(KNOWN_USERNAME)
                .doesNotContain(KNOWN_PASSWORD);
        assertThat(s).isEqualTo("Target[url=***, username=***, password=***]");
    }

    @Test
    @DisplayName("ReplicationProperties.toString delegates to Source/Target masked toString")
    void replicationPropertiesEnclosingDoesNotLeakNested() {
        // The enclosing record uses the auto-generated toString, but it composes the nested
        // Source/Target via their toString — so the secrets remain masked transitively.
        ReplicationProperties.Source source = new ReplicationProperties.Source(
                KNOWN_URL, KNOWN_USERNAME, KNOWN_PASSWORD);
        ReplicationProperties.Target target = new ReplicationProperties.Target(
                KNOWN_URL, KNOWN_USERNAME, KNOWN_PASSWORD);
        ReplicationProperties props = new ReplicationProperties(
                "test_brume", "pg_dump", 300,
                ReplicationProperties.DdlErrorMode.STRICT, 20, 3,
                source, target);

        String s = props.toString();

        assertThat(s)
                .doesNotContain(KNOWN_URL)
                .doesNotContain(KNOWN_USERNAME)
                .doesNotContain(KNOWN_PASSWORD);
    }
}
