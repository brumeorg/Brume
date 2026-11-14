package com.fungle.brume.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.error.BrumeErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reads and deserializes the Brume anonymization configuration file ({@code config.yaml}).
 *
 * <p>The file path is resolved from {@link BrumeProperties#configPath()}, which is bound
 * to the {@code brume.config-path} property in {@code application.yaml}.
 *
 * <p>Deserialization rules:
 * <ul>
 *   <li>YAML snake_case keys map to camelCase record components
 *       (e.g. {@code fk_depth} → {@code fkDepth})</li>
 *   <li>Strategy and SemanticType enum values are case-insensitive
 *       (e.g. {@code fake}, {@code FAKE}, {@code Fake} all parse correctly)</li>
 *   <li>Unknown YAML keys are ignored for forward compatibility, with a
 *       {@code [CONFIG_UNKNOWN_KEY]} warning naming the key and its line (#17g) : a typo
 *       such as {@code fk_dpeth} used to vanish without a trace</li>
 * </ul>
 *
 * <p>This class does not validate the parsed config — call
 * {@link ConfigValidator#validate(AnonymizerConfig)} afterwards.
 */
@Component
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private final BrumeProperties brumeProperties;
    private final YAMLMapper yamlMapper;

    /**
     * Constructs a ConfigLoader and initialises the Jackson YAML mapper.
     *
     * @param brumeProperties Brume runtime configuration (provides the config file path)
     */
    public ConfigLoader(BrumeProperties brumeProperties) {
        this.brumeProperties = brumeProperties;
        this.yamlMapper = YAMLMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addHandler(new UnknownKeyWarner())
                .addModule(new ParameterNamesModule())
                .build();
    }

    /**
     * Loads and deserializes the {@code config.yaml} file into an {@link AnonymizerConfig}.
     *
     * @return the parsed anonymizer configuration
     * @throws ConfigurationException if the file cannot be read or contains malformed YAML
     */
    public AnonymizerConfig load() {
        String configPath = brumeProperties.configPath();
        Path path = Paths.get(configPath);
        log.info("Loading anonymizer config from: {}", path.toAbsolutePath());

        if (!Files.exists(path)) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_FILE_NOT_FOUND,
                    "Config file not found: " + path.toAbsolutePath(),
                    "Set 'brume.config-path' in application.yaml to a path that exists, or "
                            + "create the YAML file at the location above. Use 'brume plan --help' "
                            + "to see the expected schema.");
        }

        try {
            AnonymizerConfig config = yamlMapper.readValue(path.toFile(), AnonymizerConfig.class);
            log.info("Config loaded: {} table(s) to extract, {} table(s) with anonymization rules",
                    config.extraction().tables().size(),
                    config.anonymization().tables().size());
            return config;
        } catch (com.fasterxml.jackson.databind.exc.InvalidFormatException e) {
            // #17g : the raw Jackson message named Java classes and the "reference chain".
            Class<?> type = e.getTargetType();
            String allowed = type != null && type.isEnum()
                    ? " Allowed values : " + java.util.Arrays.stream(type.getEnumConstants())
                            .map(Object::toString).collect(java.util.stream.Collectors.joining(", ")) + "."
                    : "";
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_FILE_PARSE_ERROR,
                    "Invalid value '" + e.getValue() + "' for " + yamlPath(e) + " in " + path.toAbsolutePath()
                            + lineSuffix(e) + ".",
                    "Fix the value in brume.yml." + allowed,
                    e);
        } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_FILE_PARSE_ERROR,
                    "Failed to parse config file: " + path.toAbsolutePath() + lineSuffix(e)
                            + (e.getPath().isEmpty() ? "" : ", at " + yamlPath(e)) + " — " + e.getOriginalMessage(),
                    "Re-check this entry against brume.example.yml (expected keys, lists vs maps, value types).",
                    e);
        } catch (IOException e) {
            String reason = e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                    ? jpe.getOriginalMessage() : e.getMessage();
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_FILE_PARSE_ERROR,
                    "Failed to parse config file: " + path.toAbsolutePath() + " — " + reason,
                    "Re-check the YAML syntax (indentation, quoting, list/map nesting) against "
                            + "the README §config.yaml example. Common causes: tabs instead of "
                            + "spaces, unquoted strings starting with reserved YAML tokens "
                            + "('on', 'off', '~'), or a missing top-level 'extraction' / "
                            + "'anonymization' section.",
                    e);
        }
    }

    /** {@code anonymization.tables[0].columns[1].strategy}, the path a user reads in brume.yml. */
    private static String yamlPath(com.fasterxml.jackson.databind.JsonMappingException e) {
        StringBuilder sb = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE.translate(ref.getFieldName()));
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? "the document" : sb.toString();
    }

    private static String lineSuffix(com.fasterxml.jackson.core.JsonProcessingException e) {
        return e.getLocation() == null || e.getLocation().getLineNr() < 1 ? ""
                : " (line " + e.getLocation().getLineNr() + ")";
    }

    /** Logs and skips a key the config model does not know (#17g). */
    static final class UnknownKeyWarner extends com.fasterxml.jackson.databind.deser.DeserializationProblemHandler {
        @Override
        public boolean handleUnknownProperty(com.fasterxml.jackson.databind.DeserializationContext ctxt,
                                             com.fasterxml.jackson.core.JsonParser p,
                                             com.fasterxml.jackson.databind.JsonDeserializer<?> deserializer,
                                             Object beanOrClass, String propertyName) throws IOException {
            String parent = p.getParsingContext().getParent() == null ? null
                    : p.getParsingContext().getParent().getCurrentName();
            log.warn("[CONFIG_UNKNOWN_KEY] brume.yml{} : unknown key '{}'{} — ignored. "
                            + "Check its spelling against brume.example.yml.",
                    line(p), propertyName,
                    parent == null ? "" : " under '" + parent + "'");
            p.skipChildren();
            return true;
        }

        private static String line(com.fasterxml.jackson.core.JsonParser p) {
            int line = p.currentTokenLocation().getLineNr();
            if (line < 1) line = p.currentLocation().getLineNr();
            // Records buffer their properties : a nested key may carry no location.
            return line < 1 ? "" : " line " + line;
        }
    }
}

