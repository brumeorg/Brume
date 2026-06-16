package com.fungle.brume.config;

import com.fungle.brume.config.BrumeProperties.JdbcSinkProperties;
import com.fungle.brume.config.BrumeProperties.PlanProperties;
import com.fungle.brume.config.BrumeProperties.ReportProperties;
import com.fungle.brume.config.BrumeProperties.SinkProperties;
import com.fungle.brume.config.BrumeProperties.SubstitutionDictProperties;
import com.fungle.brume.config.ReplicationProperties.DdlErrorMode;
import com.fungle.brume.config.ReplicationProperties.Source;
import com.fungle.brume.config.ReplicationProperties.Target;
import com.fungle.brume.plan.PlanMode;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.SinkType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring tests for {@link ReplicationPropertiesValidator} (audit § A5, ADR-0021;
 * sink-aware target validation, #6b / ADR-0028).
 *
 * <p>Verifies the source URL is always validated and the target URL is validated
 * only when {@code brume.sink.type=JDBC}. Detailed URL validation rules are covered
 * by {@link com.fungle.brume.util.JdbcUrlValidatorTest}.
 */
class ReplicationPropertiesValidatorTest {

    private static final String SAFE_URL = "jdbc:postgresql://localhost:5432/mydb";

    @Test
    void validate_succeeds_whenBothUrlsAreSafe_andSinkIsJdbc() {
        ReplicationProperties repl = props(SAFE_URL, new Target(SAFE_URL, "u", "p"));

        assertThatCode(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.JDBC)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsDangerousParamInSourceUrl() {
        String evil = "jdbc:postgresql://h/db?socketFactory=evil.Class";
        ReplicationProperties repl = props(evil, new Target(SAFE_URL, "u", "p"));

        assertThatThrownBy(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.JDBC)).validate())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("replication.source.url")
                .hasMessageContaining("socketFactory");
    }

    @Test
    void validate_rejectsDangerousParamInTargetUrl_whenSinkIsJdbc() {
        String evil = "jdbc:postgresql://h/db?loggerFile=/etc/poison";
        ReplicationProperties repl = props(SAFE_URL, new Target(evil, "u", "p"));

        assertThatThrownBy(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.JDBC)).validate())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("replication.target.url")
                .hasMessageContaining("loggerFile");
    }

    @Test
    void validate_rejectsBlankSourceUrl() {
        ReplicationProperties repl = props("", new Target(SAFE_URL, "u", "p"));

        assertThatThrownBy(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.JDBC)).validate())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("replication.source.url")
                .hasMessageContaining("must not be blank");
    }

    @Test
    void validate_rejectsBlankTargetUrl_whenSinkIsJdbc() {
        ReplicationProperties repl = props(SAFE_URL, new Target("", "u", "p"));

        assertThatThrownBy(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.JDBC)).validate())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("replication.target.url")
                .hasMessageContaining("must not be blank");
    }

    @Test
    void validate_rejectsMissingTargetBlock_whenSinkIsJdbc() {
        ReplicationProperties repl = props(SAFE_URL, null);

        assertThatThrownBy(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.JDBC)).validate())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("replication.target")
                .hasMessageContaining("brume.sink.type=JDBC");
    }

    @Test
    void validate_skipsTargetValidation_whenSinkIsDump_andTargetBlockIsNull() {
        ReplicationProperties repl = props(SAFE_URL, null);

        assertThatCode(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.DUMP)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validate_skipsTargetValidation_whenSinkIsNull_andTargetBlockIsNull() {
        ReplicationProperties repl = props(SAFE_URL, null);

        assertThatCode(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.NULL)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validate_skipsTargetValidation_whenSinkIsDump_evenIfTargetUrlIsBogus() {
        // Sanity: when sink=DUMP, target is unused — even a deliberately invalid URL
        // must not block boot. Documents that we don't "defense-in-depth" check
        // unused target config (audit § A5 only matters for the URL actually opened).
        ReplicationProperties repl = props(
                SAFE_URL,
                new Target("jdbc:postgresql://h/db?socketFactory=evil.Class", "u", "p"));

        assertThatCode(() -> new ReplicationPropertiesValidator(repl, brume(SinkType.DUMP)).validate())
                .doesNotThrowAnyException();
    }

    private ReplicationProperties props(String sourceUrl, Target target) {
        return new ReplicationProperties(
                "test_brume",
                "pg_dump",
                300,
                DdlErrorMode.STRICT,
                20,
                3,
                new Source(sourceUrl, "user", "pwd"),
                target,
                false
        );
    }

    private BrumeProperties brume(SinkType sinkType) {
        return new BrumeProperties(
                "brume.yml",
                "test-secret",
                "test-fpekey0123",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", ""),
                new SinkProperties(sinkType, "dumps/out.sql", CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT),
                false
        );
    }
}
