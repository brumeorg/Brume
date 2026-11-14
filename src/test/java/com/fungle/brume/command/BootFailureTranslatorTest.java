package com.fungle.brume.command;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.error.BrumeErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * #17c (ADR-0061) — a failure of the Spring context boot used to reach the operator as a
 * Spring stack trace or an "APPLICATION FAILED TO START" banner.
 */
class BootFailureTranslatorTest {

    private final BootFailureTranslator translator =
            new BootFailureTranslator(key -> "BRUME_PIPELINE_MODE".equals(key) ? "FOO" : null);

    @Test
    @DisplayName("#17c — a validator's ConfigurationException wrapped by Spring bean creation is unwrapped")
    void unwrapsBrumeExceptionFromBeanCreation() {
        ConfigurationException cause = new ConfigurationException(BrumeErrorCode.CONFIG_HMAC_SECRET_TOO_SHORT,
                "brume.hmac-secret must be at least 16 UTF-8 bytes (got 5)", "Generate a longer secret.");
        Throwable boot = new BeanCreationException("brumePropertiesValidator", "Invocation of init method failed",
                new BeanCreationException("outer", "wrapped", cause));

        assertThat(translator.translate(boot)).isSameAs(cause);
    }

    @Test
    @DisplayName("#17c — a rejected enum names the setting, the env variable, the value and the allowed values")
    void bindFailureOnEnumBecomesConfigPropertyInvalid() {
        Throwable bind = catchThrowable(() -> new Binder(new MapConfigurationPropertySource(
                Map.of("brume.pipeline-mode", "${BRUME_PIPELINE_MODE:STREAMING}")))
                .bind("brume.pipeline-mode", PipelineMode.class));

        Throwable translated = translator.translate(new IllegalStateException("boot", bind));

        assertThat(translated).isInstanceOf(ConfigurationException.class);
        ConfigurationException ce = (ConfigurationException) translated;
        assertThat(ce.code()).isEqualTo(BrumeErrorCode.CONFIG_PROPERTY_INVALID);
        assertThat(ce.getMessage())
                .isEqualTo("Invalid value 'FOO' for brume.pipeline-mode (set by BRUME_PIPELINE_MODE).");
        assertThat(ce.suggestion()).contains("BATCH").contains("STREAMING").contains("BRUME_PIPELINE_MODE");
    }

    @Test
    void bindFailureOnNumberAsksForAWholeNumber() {
        Throwable bind = catchThrowable(() -> new Binder(new MapConfigurationPropertySource(
                Map.of("replication.pool-size", "abc")))
                .bind("replication.pool-size", Integer.class));

        ConfigurationException ce = (ConfigurationException) translator.translate(bind);

        assertThat(ce.getMessage()).isEqualTo("Invalid value 'abc' for replication.pool-size.");
        assertThat(ce.suggestion()).startsWith("Expected a whole number.");
    }

    @Test
    @DisplayName("anything else stays as is and is reported as [UNEXPECTED], stack trace included")
    void unknownFailureIsKept() {
        IllegalStateException bug = new IllegalStateException("bug");

        assertThat(translator.translate(bug)).isSameAs(bug);
    }
}
