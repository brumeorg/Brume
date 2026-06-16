package com.fungle.brume.anonymization;

import com.fungle.brume.agent.ReplicationAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring context integration test for the anonymization layer.
 *
 * <p>This test verifies that the full Spring application context starts correctly
 * with all anonymization beans wired — in particular that {@code JacksonConfig}
 * exposes an {@code ObjectMapper} bean required by {@link JsonPathProcessor}.
 *
 * <p>It does <em>not</em> invoke any anonymization logic against real data; its sole
 * purpose is to catch missing-bean or wiring errors early (e.g. "No qualifying bean
 * of type ObjectMapper").
 *
 * <p><strong>Requires Docker infrastructure</strong> — run {@code docker-compose up -d} first:
 * <ul>
 *   <li>Source DB on {@code localhost:5432} (needed for HikariCP pool creation)</li>
 *   <li>Target DB on {@code localhost:5460} (needed for HikariCP pool creation)</li>
 * </ul>
 *
 * <p>{@link ReplicationAgent} is mocked to prevent the CommandLineRunner from executing
 * the full replication pipeline when the Spring context starts.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class AnonymizationEngineContextIT {

    /** Mocked to prevent CommandLineRunner from executing the full pipeline on context startup. */
    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    private AnonymizationEngine engine;

    @Autowired
    private JsonPathProcessor jsonPathProcessor;

    /**
     * Verifies that {@link AnonymizationEngine} is available as a Spring bean.
     *
     * <p>This fails if any of its dependencies ({@code StrategyResolver},
     * {@code SubstitutionDictionary}, {@code SemanticKeyResolver}, {@code JsonPathProcessor})
     * cannot be instantiated.
     */
    @Test
    @DisplayName("AnonymizationEngine bean is correctly wired in the Spring context")
    void anonymizationEngineShouldNotBeNull() {
        assertThat(engine)
                .as("AnonymizationEngine must be wired — check StrategyResolver and SubstitutionDictionary")
                .isNotNull();
    }

    /**
     * Verifies that {@link JsonPathProcessor} is available as a Spring bean.
     *
     * <p>This fails if {@code JacksonConfig} is absent — without it, {@code spring-boot-starter}
     * (no web starter) does NOT register an {@code ObjectMapper} bean, causing context startup
     * to fail with "No qualifying bean of type 'ObjectMapper'".
     */
    @Test
    @DisplayName("JsonPathProcessor bean is correctly wired — JacksonConfig must expose ObjectMapper")
    void jsonPathProcessorShouldNotBeNull() {
        assertThat(jsonPathProcessor)
                .as("JsonPathProcessor must be wired — ensure JacksonConfig declares @Bean ObjectMapper")
                .isNotNull();
    }
}

