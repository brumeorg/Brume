package com.fungle.brume.util;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.util.JdbcUrlValidator.ParsedJdbcUrl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JdbcUrlValidator} (audit § A5, ADR-0021).
 *
 * <p>Two distinct concerns under test :
 * <ol>
 *   <li>Robust parsing — the historical naive split broke on query strings
 *       (regression #15c bug 1).</li>
 *   <li>Parameter allowlist — pgJDBC params that load classes or write files
 *       must be rejected (regression #15c bug 2).</li>
 * </ol>
 */
class JdbcUrlValidatorTest {

    @Test
    void validate_acceptsCanonicalUrl() {
        ParsedJdbcUrl parsed = JdbcUrlValidator.validate(
                "jdbc:postgresql://localhost:5432/mydb", "replication.source.url");

        assertThat(parsed.host()).isEqualTo("localhost");
        assertThat(parsed.port()).isEqualTo(5432);
        assertThat(parsed.database()).isEqualTo("mydb");
        assertThat(parsed.sslMode()).isNull();
    }

    @Test
    void validate_extractsSslModeWhenPresent() {
        ParsedJdbcUrl parsed = JdbcUrlValidator.validate(
                "jdbc:postgresql://db.example.com:6543/prod?sslmode=require",
                "replication.source.url");

        assertThat(parsed.host()).isEqualTo("db.example.com");
        assertThat(parsed.port()).isEqualTo(6543);
        assertThat(parsed.database()).isEqualTo("prod");
        assertThat(parsed.sslMode()).isEqualTo("require");
    }

    @Test
    void validate_acceptsMultipleAllowedParams() {
        // sslmode + applicationName are both in ALLOWED_PARAMS
        ParsedJdbcUrl parsed = JdbcUrlValidator.validate(
                "jdbc:postgresql://h/db?sslmode=require&applicationName=brume",
                "replication.source.url");

        assertThat(parsed.database()).isEqualTo("db");
        assertThat(parsed.sslMode()).isEqualTo("require");
    }

    @Test
    void validate_rejectsSocketFactoryParam() {
        // socketFactory is the canonical pgJDBC RCE pivot
        assertThatThrownBy(() ->
                JdbcUrlValidator.validate(
                        "jdbc:postgresql://h/db?socketFactory=evil.Class",
                        "replication.source.url"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("socketFactory")
                .hasMessageContaining("rejected parameter")
                .hasMessageContaining("replication.source.url");
    }

    @Test
    void validate_rejectsLoggerFileParam() {
        assertThatThrownBy(() ->
                JdbcUrlValidator.validate(
                        "jdbc:postgresql://h/db?loggerFile=/etc/cron.daily/poison",
                        "replication.target.url"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("loggerFile")
                .hasMessageContaining("rejected parameter");
    }

    @Test
    void validate_rejectsSslFactoryParam() {
        assertThatThrownBy(() ->
                JdbcUrlValidator.validate(
                        "jdbc:postgresql://h/db?sslfactory=evil.Class",
                        "replication.source.url"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("sslfactory");
    }

    /**
     * Regression guard for the original {@code parseJdbcUrl} bug (audit § A5, bug 1) :
     * the URL {@code jdbc:postgresql://h/mydb?sslmode=require} used to produce
     * {@code database = "mydb?sslmode=require"} via a naive {@code String.split("/")},
     * and that bogus name was forwarded verbatim to {@code pg_dump -d}. After the fix,
     * the bare DB name is {@code "mydb"} and {@code sslmode} is extracted separately.
     */
    @Test
    void validate_extractsBareDatabaseNameWhenQueryParamsPresent() {
        ParsedJdbcUrl parsed = JdbcUrlValidator.validate(
                "jdbc:postgresql://h/mydb?sslmode=require", "replication.source.url");

        assertThat(parsed.database())
                .as("DB name must NOT contain the '?sslmode=require' suffix (regression #15c)")
                .isEqualTo("mydb");
        assertThat(parsed.sslMode()).isEqualTo("require");
    }

    @Test
    void validate_rejectsWrongPrefix() {
        assertThatThrownBy(() ->
                JdbcUrlValidator.validate(
                        "jdbc:mysql://h/db", "replication.source.url"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("must start with 'jdbc:postgresql:'");
    }

    @Test
    void validate_rejectsNullUrl() {
        assertThatThrownBy(() ->
                JdbcUrlValidator.validate(null, "replication.source.url"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void validate_rejectsBlankUrl() {
        assertThatThrownBy(() ->
                JdbcUrlValidator.validate("   ", "replication.target.url"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void validate_rejectsUrlWithoutDatabase() {
        // Valid pgJDBC prefix but no /dbname segment
        assertThatThrownBy(() ->
                JdbcUrlValidator.validate(
                        "jdbc:postgresql://localhost:5432/", "replication.source.url"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("no database name");
    }

    @Test
    void validate_messageListsAllowedParamsForOperator() {
        // Ensure the rejection surfaces what *is* allowed, so the operator can
        // self-serve when they see a rejection. Per #17b / ADR-0027 Q1.A the
        // actionable detail lives in suggestion(), not in the short message.
        try {
            JdbcUrlValidator.validate(
                    "jdbc:postgresql://h/db?socketFactory=x", "replication.source.url");
        } catch (ConfigurationException e) {
            assertThat(e.suggestion())
                    .contains("Allowed:")
                    .contains("sslmode")
                    .contains("applicationName");
        }
    }
}
