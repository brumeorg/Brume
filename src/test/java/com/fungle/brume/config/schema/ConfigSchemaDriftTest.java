package com.fungle.brume.config.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against silent drift between the {@code config.yaml} record model
 * ({@link com.fungle.brume.config.model.AnonymizerConfig} and friends) and the
 * checked-in {@code src/main/resources/config.schema.json} that ships with the JAR.
 *
 * <p>On a green run the regenerated schema is byte-identical to the checked-in file.
 * If a developer adds or removes a record component (or changes an enum) without
 * updating the schema, this test fails with an actionable message.
 *
 * <p>To regenerate the checked-in schema, run the test with the env var
 * {@code BRUME_REGEN_SCHEMA=1}: the test then overwrites the file and passes.
 */
class ConfigSchemaDriftTest {

    private static final Path SCHEMA_PATH = Path.of("src/main/resources/config.schema.json");

    @Test
    @DisplayName("checked-in config.schema.json matches the generator output (anti-drift)")
    void schemaFileMatchesGenerator() throws Exception {
        String generated = ConfigSchemaGenerator.generate();

        boolean regen = "1".equals(System.getenv("BRUME_REGEN_SCHEMA"));
        if (regen) {
            Files.writeString(SCHEMA_PATH, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(SCHEMA_PATH)
                .as("config.schema.json is missing — regenerate with BRUME_REGEN_SCHEMA=1 ./mvnw test")
                .exists();

        String onDisk = Files.readString(SCHEMA_PATH, StandardCharsets.UTF_8);

        assertThat(onDisk)
                .as("config.schema.json drifted from the record model — "
                        + "regenerate with BRUME_REGEN_SCHEMA=1 ./mvnw test "
                        + "and commit the updated file")
                .isEqualTo(generated);
    }
}
