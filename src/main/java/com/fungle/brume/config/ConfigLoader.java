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
 *   <li>Unknown YAML keys are silently ignored for forward compatibility</li>
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
        } catch (IOException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_FILE_PARSE_ERROR,
                    "Failed to parse config file: " + path.toAbsolutePath() + " — " + e.getMessage(),
                    "Re-check the YAML syntax (indentation, quoting, list/map nesting) against "
                            + "the README §config.yaml example. Common causes: tabs instead of "
                            + "spaces, unquoted strings starting with reserved YAML tokens "
                            + "('on', 'off', '~'), or a missing top-level 'extraction' / "
                            + "'anonymization' section.",
                    e);
        }
    }
}

