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
        validateSourceUrl();
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
        JdbcUrlValidator.validate(source.url(), "replication.source.url");
    }

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
        JdbcUrlValidator.validate(target.url(), "replication.target.url");
    }
}
