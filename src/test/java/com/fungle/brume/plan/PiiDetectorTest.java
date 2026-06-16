package com.fungle.brume.plan;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.report.PiiWarning;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PiiDetector}.
 *
 * <p>No Spring context — {@code PiiDetector} is instantiated directly.
 */
class PiiDetectorTest {

    private final PiiDetector detector = new PiiDetector();

    /**
     * A varchar column named "billing_email" with no anonymization rule
     * must produce exactly one warning with pattern "email".
     */
    @Test
    void shouldDetectEmailColumnWithoutRule() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users",
                        List.of(new ColumnMetadata("billing_email", "character varying", true)),
                        List.of(), "id")
        ));
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000,
                        List.of(new TableExtractionConfig("users", null))),
                new AnonymizationConfig(List.of(), List.of())
        );

        List<PiiWarning> warnings = detector.detect(schema, config, "test");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).table()).isEqualTo("users");
        assertThat(warnings.get(0).column()).isEqualTo("billing_email");
        assertThat(warnings.get(0).matchedPattern()).isEqualTo("email");
    }

    /**
     * A varchar column named "email" that already has an anonymization rule declared
     * in the config must not produce any warning.
     */
    @Test
    void shouldNotWarnForCoveredColumn() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users",
                        List.of(new ColumnMetadata("email", "character varying", false)),
                        List.of(), "id")
        ));
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000,
                        List.of(new TableExtractionConfig("users", null))),
                new AnonymizationConfig(List.of(),
                        List.of(new TableAnonymizationConfig("users",
                                List.of(new ColumnConfig("email", Strategy.FAKE, null, List.of())))))
        );

        List<PiiWarning> warnings = detector.detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    /**
     * A column named "zip_code" with data type "integer" must not produce a warning
     * even though its name matches the pattern "zip". Numeric types are excluded.
     */
    @Test
    void shouldNotWarnForNumericColumnMatchingPattern() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "addresses", new TableMetadata("addresses",
                        List.of(new ColumnMetadata("zip_code", "integer", true)),
                        List.of(), "id")
        ));
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000,
                        List.of(new TableExtractionConfig("addresses", null))),
                new AnonymizationConfig(List.of(), List.of())
        );

        List<PiiWarning> warnings = detector.detect(schema, config, "test");

        assertThat(warnings).isEmpty();
    }

    /**
     * A varchar column named "iban" with no anonymization rule must produce
     * exactly one warning with pattern "iban".
     */
    @Test
    void shouldDetectIbanColumn() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "users", new TableMetadata("users",
                        List.of(new ColumnMetadata("iban", "character varying", true)),
                        List.of(), "id")
        ));
        AnonymizerConfig config = new AnonymizerConfig(
                new ExtractionConfig(3, 1000,
                        List.of(new TableExtractionConfig("users", null))),
                new AnonymizationConfig(List.of(), List.of())
        );

        List<PiiWarning> warnings = detector.detect(schema, config, "test");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).matchedPattern()).isEqualTo("iban");
    }
}

