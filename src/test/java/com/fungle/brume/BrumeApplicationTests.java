package com.fungle.brume;

import com.fungle.brume.agent.ReplicationAgent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Spring Boot application context integration test.
 *
 * <p>Verifies that the full application context starts without errors when both databases
 * are available. Requires Docker infrastructure — run {@code docker-compose up -d} before
 * executing this test.
 *
 * <p>{@link ReplicationAgent} is mocked to prevent the CommandLineRunner from executing
 * the full replication pipeline when the Spring context starts during the test.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class BrumeApplicationTests {

    /** Mocked to prevent CommandLineRunner from executing the full pipeline on context startup. */
    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Test
    void contextLoads() {
        // Verifies that the full Spring application context starts without errors.
        // Needs source DB on localhost:5432 and target DB on localhost:5460.
    }

}
