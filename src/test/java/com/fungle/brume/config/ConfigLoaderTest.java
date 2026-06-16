package com.fungle.brume.config;

import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ConfigLoader}.
 *
 * <p>Uses {@code src/test/resources/test-config.yaml} as the config file.
 * No Spring context is loaded — ConfigLoader is instantiated directly.
 */
class ConfigLoaderTest {

    private ConfigLoader configLoader;

    @BeforeEach
    void setUp() {
        // Resolve the test config relative to the project root (Maven working directory)
        String testConfigPath = Paths.get("src", "test", "resources", "test-config.yaml").toString();
        BrumeProperties props = new BrumeProperties(testConfigPath, null, null, "HmacSHA256", "fr",  0.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        configLoader = new ConfigLoader(props);
    }

    @Test
    void load_parsesExtractionConfig() {
        AnonymizerConfig config = configLoader.load();

        assertThat(config.extraction()).isNotNull();
        assertThat(config.extraction().fkDepth()).isEqualTo(2);
        assertThat(config.extraction().fetchSize()).isEqualTo(50);
        assertThat(config.extraction().batchSize()).isEqualTo(250);
        assertThat(config.extraction().tables()).hasSize(2);
        assertThat(config.extraction().tables().get(0).table()).isEqualTo("orders");
        assertThat(config.extraction().tables().get(0).filter()).isEqualTo("created_at >= '2025-01-01'");
        assertThat(config.extraction().tables().get(1).table()).isEqualTo("audit_logs");
    }

    @Test
    void load_parsesLinkedColumns() {
        AnonymizerConfig config = configLoader.load();

        assertThat(config.anonymization().linkedColumns()).hasSize(1);
        assertThat(config.anonymization().linkedColumns().get(0).semanticKey()).isEqualTo("user_email");
        assertThat(config.anonymization().linkedColumns().get(0).columns()).hasSize(2);
        assertThat(config.anonymization().linkedColumns().get(0).columns().get(0).table()).isEqualTo("users");
        assertThat(config.anonymization().linkedColumns().get(0).columns().get(0).column()).isEqualTo("email");
    }

    @Test
    void load_parsesAnonymizationTables() {
        AnonymizerConfig config = configLoader.load();

        assertThat(config.anonymization().tables()).hasSize(2);
        // users table
        var usersTable = config.anonymization().tables().get(0);
        assertThat(usersTable.table()).isEqualTo("users");
        assertThat(usersTable.columns()).hasSize(5);
    }

    @Test
    void load_parsesStrategiesAndTypes() {
        AnonymizerConfig config = configLoader.load();

        var usersColumns = config.anonymization().tables().get(0).columns();
        // id -> FPE_ID
        assertThat(usersColumns.get(0).strategy()).isEqualTo(Strategy.FPE_ID);
        assertThat(usersColumns.get(0).type()).isNull();
        // email -> FAKE / EMAIL
        assertThat(usersColumns.get(1).strategy()).isEqualTo(Strategy.FAKE);
        assertThat(usersColumns.get(1).type()).isEqualTo(SemanticType.EMAIL);
        // phone -> MASK / PHONE
        assertThat(usersColumns.get(2).strategy()).isEqualTo(Strategy.MASK);
        assertThat(usersColumns.get(2).type()).isEqualTo(SemanticType.PHONE);
        // manager_id -> NULLIFY
        assertThat(usersColumns.get(3).strategy()).isEqualTo(Strategy.NULLIFY);
    }

    @Test
    void load_parsesJsonPaths() {
        AnonymizerConfig config = configLoader.load();

        var usersColumns = config.anonymization().tables().get(0).columns();
        var addressJson = usersColumns.get(4); // address_json
        assertThat(addressJson.type()).isEqualTo(SemanticType.JSONB);
        assertThat(addressJson.jsonPaths()).hasSize(1);
        assertThat(addressJson.jsonPaths().get(0).path()).isEqualTo("$.street");
        assertThat(addressJson.jsonPaths().get(0).type()).isEqualTo(SemanticType.ADDRESS);
        assertThat(addressJson.jsonPaths().get(0).strategy()).isEqualTo(Strategy.FAKE);
    }

    @Test
    void load_appliesDefaultValues_whenFkDepthFetchSizeAndBatchSizeAbsent() {
        // Use a config with fk_depth/fetch_size/batch_size absent (they would be 0 from deserialization)
        // The compact constructor must default them to 3 and 1000/1000.
        String minimalConfigPath = Paths.get("src", "test", "resources", "test-config-minimal.yaml").toString();
        BrumeProperties props = new BrumeProperties(minimalConfigPath, null, null, "HmacSHA256", "fr",  0.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        ConfigLoader loader = new ConfigLoader(props);
        AnonymizerConfig config = loader.load();

        assertThat(config.extraction().fkDepth()).isEqualTo(3);
        assertThat(config.extraction().fetchSize()).isEqualTo(1000);
        assertThat(config.extraction().batchSize()).isEqualTo(1000);
    }

    @Test
    void load_throwsConfigurationException_whenFileNotFound() {
        BrumeProperties props = new BrumeProperties("nonexistent.yaml", null, null, "HmacSHA256", "fr",  0.0,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        ConfigLoader loader = new ConfigLoader(props);

        assertThatThrownBy(loader::load)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Config file not found");
    }
}


