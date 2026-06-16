package com.fungle.brume.config.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the checked-in {@code config.schema.json} against representative YAML
 * payloads — one valid, two invalid — to make sure the schema flags the failures
 * editors care about (typo enums, unknown fields).
 */
class ConfigSchemaValidationTest {

    private static JsonSchema schema;
    private static YAMLMapper yamlMapper;

    @BeforeAll
    static void loadSchema() throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        String schemaJson = Files.readString(Path.of("src/main/resources/config.schema.json"));
        schema = factory.getSchema(schemaJson);
        yamlMapper = new YAMLMapper();
    }

    @Test
    @DisplayName("the reference test-config.yaml validates against the schema")
    void referenceConfigIsValid() throws Exception {
        JsonNode yaml = yamlMapper.readTree(Path.of("src/test/resources/test-config.yaml").toFile());

        Set<ValidationMessage> errors = schema.validate(yaml);

        assertThat(errors)
                .as("reference YAML should be schema-valid, but got: %s", errors)
                .isEmpty();
    }

    @Test
    @DisplayName("a YAML with a typo'd Strategy enum value is rejected")
    void typoEnumIsRejected() throws Exception {
        String yamlSource = """
                extraction:
                  fk_depth: 2
                  tables:
                    - table: orders
                anonymization:
                  tables:
                    - table: users
                      columns:
                        - name: email
                          strategy: FAKKE
                          type: EMAIL
                """;

        JsonNode yaml = yamlMapper.readTree(yamlSource);
        Set<ValidationMessage> errors = schema.validate(yaml);

        assertThat(errors).isNotEmpty();
        assertThat(errors.toString())
                .as("error should point at the strategy field and reference the enum")
                .contains("strategy")
                .contains("FAKE");
    }

    @Test
    @DisplayName("a YAML with an unknown field is rejected")
    void unknownFieldIsRejected() throws Exception {
        String yamlSource = """
                extraction:
                  fk_depth: 2
                  tables:
                    - table: orders
                anonymization:
                  tables:
                    - table: users
                      columns:
                        - name: email
                          strategy: FAKE
                          type: EMAIL
                          unknown_field: oops
                """;

        JsonNode yaml = yamlMapper.readTree(yamlSource);
        Set<ValidationMessage> errors = schema.validate(yaml);

        assertThat(errors).isNotEmpty();
        assertThat(errors.toString())
                .as("should mention the unknown field")
                .contains("unknown_field");
    }

    @Test
    @DisplayName("the loose ObjectMapper YAML input is not enough — paths must be enforced")
    void typoAtRootIsRejected() throws Exception {
        String yamlSource = """
                extration:
                  fk_depth: 2
                """;

        JsonNode yaml = yamlMapper.readTree(yamlSource);
        Set<ValidationMessage> errors = schema.validate(yaml);

        assertThat(errors)
                .as("a misspelled top-level key should be rejected (additionalProperties: false)")
                .isNotEmpty();
        assertThat(errors.toString()).contains("extration");
    }

    @Test
    @DisplayName("ObjectMapper round-trip works when fed plain JSON, not just YAML")
    void plainJsonAlsoValidates() throws Exception {
        String json = """
                {
                  "extraction": { "fk_depth": 2, "tables": [{"table": "orders"}] },
                  "anonymization": {
                    "tables": [{"table": "users", "columns": [{"name": "id", "strategy": "FPE_ID"}]}]
                  }
                }
                """;

        JsonNode tree = new ObjectMapper().readTree(json);
        Set<ValidationMessage> errors = schema.validate(tree);

        assertThat(errors).isEmpty();
    }
}
