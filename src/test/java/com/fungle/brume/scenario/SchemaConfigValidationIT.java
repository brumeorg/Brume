package com.fungle.brume.scenario;

import com.fungle.brume.BrumeApplication;
import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * J7 — schema-aware config validation rejects unknown tables/columns at boot.
 *
 * <p>User journey: a user makes a typo (or rolls a config from a different schema) and
 * tries to run Brume. Without this validator, the typo would surface only mid-pipeline as
 * a confusing "relation does not exist" SQL error. With it, boot fails fast with the
 * Levenshtein suggestion pointing to the right name.
 *
 * <p>This complements {@code SchemaConfigValidatorTest} (unit) by exercising the full
 * pipeline wiring: {@code BrumeApplication.main} → {@code ReplicationAgent.run} →
 * {@code SchemaConfigValidator.validate}, so a regression in the wiring (validator not
 * invoked, or invoked too early before the schema is loaded) would surface here.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 */
class SchemaConfigValidationIT {

    private static final Map<String, Object> PROPS = Map.of(
            "brume.config-path", "src/test/resources/test-config-bad-table.yaml",
            "brume.sink.type", "JDBC",
            "replication.source.url", "jdbc:postgresql://localhost:5432/postgres",
            "replication.target.url", "jdbc:postgresql://localhost:5460/postgres",
            "replication.schema", "test_brume",
            "replication.pgdump-path", "docker exec -e PGPASSWORD=postgres brume-source pg_dump",
            "replication.pool-size", "3"
    );

    @Test
    @DisplayName("Boot fails fast when the config references an unknown table, with a 'did you mean' hint")
    void rejectsUnknownTableWithSuggestion() {
        try (ConfigurableApplicationContext ctx = bootstrap()) {
            assertThatThrownBy(() -> ctx.getBean(ReplicationAgent.class).run(CommandEnum.EXECUTE))
                    .isInstanceOfSatisfying(ConfigurationException.class, ex -> {
                        // Post-#17b (ADR-0027): the 'did you mean' hint lives in the structured
                        // suggestion field, not in the exception message.
                        assertThat(ex.getMessage())
                                .as("error message must name the offending table")
                                .contains("Unknown table 'userz'");
                        assertThat(ex.suggestion())
                                .as("suggestion must surface the Levenshtein candidate so users can fix the typo")
                                .contains("Did you mean 'users'");
                    });
        }
    }

    private static ConfigurableApplicationContext bootstrap() {
        Map<String, Object> props = new LinkedHashMap<>(PROPS);
        String[] args = props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(BrumeApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run(args);
    }
}
