package com.fungle.brume.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson configuration — exposes a shared {@link ObjectMapper} bean.
 *
 * <p>Since this project uses only {@code spring-boot-starter} (no web starter),
 * Spring Boot does not auto-configure an {@link ObjectMapper} bean. This class
 * provides a mapper used by {@link com.fungle.brume.anonymization.JsonPathProcessor}
 * and {@link com.fungle.brume.report.ReportRenderer}.
 *
 * <p>{@link JavaTimeModule} is registered so that {@code java.time.Instant} (used in
 * {@link com.fungle.brume.report.ExecutionSummary}) serializes as an ISO-8601 string
 * rather than a numeric timestamp or causing a truncated JSON output.
 */
@Configuration
public class JacksonConfig {

    /**
     * Creates and exposes a Jackson {@link ObjectMapper} as a Spring bean.
     *
     * <p>Configured with:
     * <ul>
     *   <li>{@link JavaTimeModule} — enables ISO-8601 serialization of {@code java.time.*} types</li>
     *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} disabled — forces ISO strings</li>
     * </ul>
     *
     * @return a configured {@link ObjectMapper}
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

