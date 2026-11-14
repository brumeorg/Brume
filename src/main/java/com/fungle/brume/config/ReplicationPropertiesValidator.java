package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.util.JdbcUrlValidator;
import com.fungle.brume.writer.SinkType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates {@link ReplicationProperties} at application startup (audit § A5, ADR-0021).
 *
 * <p>Mirrors the {@link BrumePropertiesValidator} pattern : runs a
 * {@link PostConstruct} fail-fast check before any database connection is opened.
 *
 * <p>Rules enforced :
 * <ul>
 *   <li>{@code replication.source.url} is non-blank, well-formed, and contains
 *       only allow-listed pgJDBC parameters (no {@code socketFactory},
 *       {@code loggerFile}, etc.).</li>
 *   <li>Same for {@code replication.target.url} — but <strong>only when
 *       {@code brume.sink.type=JDBC}</strong>. In {@code DUMP} or {@code NULL}
 *       sink mode the target is unused, so the {@code replication.target} block
 *       may be omitted entirely (ADR-0028).</li>
 * </ul>
 */
@Component
public class ReplicationPropertiesValidator {

    private static final Logger log = LoggerFactory.getLogger(ReplicationPropertiesValidator.class);

    private final ReplicationProperties properties;
    private final BrumeProperties brumeProperties;

    public ReplicationPropertiesValidator(
            ReplicationProperties properties,
            BrumeProperties brumeProperties) {
        this.properties = properties;
        this.brumeProperties = brumeProperties;
    }

    @PostConstruct
    public void validate() {
        log.debug("Validating replication properties...");
        validatePoolSize();
        validateSourceUrl();
        rejectUnresolvedPlaceholder(properties.schema(), "replication.schema", "BRUME_SCHEMA");
        SinkType sinkType = brumeProperties.sink().type();
        if (sinkType == SinkType.JDBC) {
            validateTargetUrl();
        } else {
            log.info("Sink mode is {} — skipping replication.target validation "
                    + "(target is unused in this mode)", sinkType);
        }
        log.info("✓ Replication properties validated successfully");
    }

    private void validateSourceUrl() {
        ReplicationProperties.Source source = properties.source();
        if (source == null) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_REPLICATION_SOURCE_MISSING,
                    "replication.source must be set",
                    "Add a 'replication.source' block (url, username, password) to "
                            + "application.yaml. The source is the PostgreSQL instance Brume "
                            + "reads from.");
        }
        rejectUnresolvedPlaceholder(source.url(), "replication.source.url", "BRUME_SOURCE_HOST / BRUME_SOURCE_DB");
        rejectUnresolvedPlaceholder(source.username(), "replication.source.username", "BRUME_SOURCE_USER");
        JdbcUrlValidator.validate(source.url(), "replication.source.url");
        validateSslMode(source.url(), "replication.source.url", "BRUME_SOURCE_SSLMODE");
    }

    /**
     * Hikari rejects a pool size below 1 with a bare {@code IllegalArgumentException} at
     * bean creation : report it as a setting error instead (#17g).
     */
    private void validatePoolSize() {
        if (properties.poolSize() < 1) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_POOL_SIZE_INVALID,
                    "replication.pool-size must be at least 1 (got " + properties.poolSize() + ")",
                    "Set BRUME_PARALLELISM in .env.brume to the number of parallel connections "
                            + "(default 20).");
        }
    }

    /**
     * pgJDBC only reports an unknown {@code sslmode} when it connects, as a connection failure
     * (exit 2) : a script retrying network errors would retry a typo forever (#17g).
     */
    static void validateSslMode(String url, String property, String envKey) {
        if (url == null) return;
        java.util.regex.Matcher m = SSLMODE.matcher(url);
        if (!m.find()) return;
        String mode = m.group(1);
        if (!SSL_MODES.contains(mode)) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_SSLMODE_INVALID,
                    property + " has sslmode='" + mode + "', which PostgreSQL does not know",
                    "Set " + envKey + " to one of : " + String.join(", ", SSL_MODES) + ".");
        }
    }

    /**
     * Last barrier behind {@link RequiredSettings} (#17d) : Spring leaves an unset
     * {@code ${BRUME_*}} placeholder as its literal text.
     */
    static void rejectUnresolvedPlaceholder(String value, String property, String envKey) {
        if (value != null && value.contains("${")) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_ENV_VAR_MISSING,
                    property + " is not set : " + envKey + " is missing",
                    "Add " + envKey + " to .env.brume (the template created by 'brume init' lists "
                            + "every key) or export it in the environment.");
        }
    }

    private static final java.util.regex.Pattern SSLMODE =
            java.util.regex.Pattern.compile("[?&]sslmode=([^&]*)");
    private static final java.util.List<String> SSL_MODES =
            java.util.List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    private void validateTargetUrl() {
        ReplicationProperties.Target target = properties.target();
        if (target == null) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_REPLICATION_TARGET_MISSING,
                    "replication.target must be set when brume.sink.type=JDBC",
                    "Add a 'replication.target' block (url, username, password) to "
                            + "application.yaml — the target is the PostgreSQL instance Brume "
                            + "writes the anonymized rows to. Alternatively, set "
                            + "'brume.sink.type=DUMP' (write to a .sql/.sql.gz file) or run "
                            + "'brume plan' / 'brume dry-run' (no target needed).");
        }
        rejectUnresolvedPlaceholder(target.url(), "replication.target.url", "BRUME_TARGET_HOST / BRUME_TARGET_DB");
        rejectUnresolvedPlaceholder(target.username(), "replication.target.username", "BRUME_TARGET_USER");
        JdbcUrlValidator.validate(target.url(), "replication.target.url");
        validateSslMode(target.url(), "replication.target.url", "BRUME_TARGET_SSLMODE");
    }
}
