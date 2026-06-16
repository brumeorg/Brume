package com.fungle.brume.anonymization.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.BrumeProperties.ReportProperties;
import com.fungle.brume.config.model.SemanticType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * #30 (A9) — V1.0 determinism contract.
 *
 * <p>For a fixed (HMAC secret + algorithm + locale + FPE key + input), each of the 7
 * anonymization strategies must produce the same output across JVM runs, code refactors,
 * Datafaker upgrades and CI agents. Fixtures live in {@code
 * src/test/resources/determinism-fixtures-v1.0.json} and are versioned alongside the code.
 *
 * <p><strong>Modifying an "expected" value is a breaking change for V1.x</strong> —
 * requires a MAJOR SemVer bump per ticket #34 (E1). Bumping {@code net.datafaker:datafaker}
 * (currently 2.4.0 in {@code pom.xml}) is equally breaking: its internal name/email
 * dictionaries are part of the deterministic output contract for {@link FakeStrategy}.
 *
 * <p>Run {@code mvn test -Dtest=DeterminismRegressionTest -DREGEN_DETERMINISM_FIXTURES=1}
 * (or set the env var) to regenerate the baseline after an intentional breaking change.
 * Without the flag, divergence between code output and fixtures fails the test loudly.
 */
class DeterminismRegressionTest {

    private static final Path FIXTURES_PATH =
            Paths.get("src/test/resources/determinism-fixtures-v1.0.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("V1.0 fixtures: each (strategy, type, input) produces the figé output — no drift allowed without intentional regen")
    void v1FixturesMatchCurrentImplementation() throws Exception {
        boolean regen = isRegenRequested();

        JsonNode root = MAPPER.readTree(Files.readAllBytes(FIXTURES_PATH));
        JsonNode config = root.get("config");
        BrumeProperties props = buildProperties(config);

        // 1 instance per strategy — they're stateless w.r.t. the input value.
        FakeStrategy fake = new FakeStrategy(props);
        HashStrategy hash = new HashStrategy(props);
        FpeIdStrategy fpeId = new FpeIdStrategy(props);
        FpeUuidStrategy fpeUuid = new FpeUuidStrategy(props);
        MaskStrategy mask = new MaskStrategy();
        NullifyStrategy nullify = new NullifyStrategy();
        KeepStrategy keep = new KeepStrategy();

        List<String> failures = new ArrayList<>();

        for (JsonNode fixture : root.get("fixtures")) {
            String id = fixture.get("id").asText();
            String strategyName = fixture.get("strategy").asText();
            SemanticType type = SemanticType.valueOf(fixture.get("type").asText());
            Object input = readTypedValue(fixture.get("input"));

            // Per-fixture dict — isolates each invocation; the substitution cache must not
            // bleed across fixtures or the order of fixtures would alter outputs.
            SubstitutionDictionary dict = new SubstitutionDictionary(props);
            String semanticKey = "det-test." + id;

            Object actual = switch (strategyName) {
                case "FAKE"     -> fake.anonymize(input, type, semanticKey, dict);
                case "HASH"     -> hash.anonymize(input, type, semanticKey, dict);
                case "FPE_ID"   -> fpeId.anonymize(input, type, semanticKey, dict);
                case "FPE_UUID" -> fpeUuid.anonymize(input, type, semanticKey, dict);
                case "MASK"     -> mask.anonymize(input, type, semanticKey, dict);
                case "NULLIFY"  -> nullify.anonymize(input, type, semanticKey, dict);
                case "KEEP"     -> keep.anonymize(input, type, semanticKey, dict);
                default -> throw new IllegalStateException("unknown strategy in fixture: " + strategyName);
            };

            if (regen) {
                writeExpected((ObjectNode) fixture, actual);
                continue;
            }

            Object expected = readTypedValue(fixture.get("expected"));
            if (!typedEquals(expected, actual)) {
                failures.add(String.format(
                        "[%s] %s on %s — expected %s (%s), got %s (%s)",
                        id, strategyName, formatValue(input),
                        formatValue(expected), typeName(expected),
                        formatValue(actual),   typeName(actual)));
            }
        }

        if (regen) {
            Files.writeString(FIXTURES_PATH, MAPPER.writeValueAsString(root) + "\n");
            fail("REGEN_DETERMINISM_FIXTURES=1: baseline rewritten to " + FIXTURES_PATH
                    + ". Re-run the test without the flag to verify, then commit the JSON.");
        }

        assertThat(failures)
                .as("All V1.0 fixtures must match. A divergence here means a strategy "
                  + "changed its output — that is a breaking change for V1.x and requires "
                  + "a MAJOR SemVer bump + intentional regen via REGEN_DETERMINISM_FIXTURES=1.")
                .isEmpty();
    }

    private static boolean isRegenRequested() {
        String prop = System.getProperty("REGEN_DETERMINISM_FIXTURES");
        if (prop != null) return "1".equals(prop) || "true".equalsIgnoreCase(prop);
        String env = System.getenv("REGEN_DETERMINISM_FIXTURES");
        return "1".equals(env) || "true".equalsIgnoreCase(env);
    }

    /**
     * Builds a minimal {@link BrumeProperties} from the config block in the fixture file —
     * deliberately constructed in code rather than wired via {@code @SpringBootTest}, so
     * the test is decoupled from any mutable default in {@code application.yml}
     * (notably {@code brume.faker-locale}).
     */
    private static BrumeProperties buildProperties(JsonNode config) {
        return new BrumeProperties(
                "test-config.yaml",
                config.get("hmacSecret").asText(),
                config.get("fpeKey").asText(),
                config.get("hmacAlgorithm").asText(),
                config.get("fakerLocale").asText(),
                0.0,
                new ReportProperties("", "", "")
        );
    }

    /**
     * Reads a typed value node of the form {@code {"type": "string|long|uuid|null", "value": ...}}.
     * Longs are encoded as quoted strings to survive a round-trip through JSON readers that
     * downcast big numbers to {@code double}.
     */
    private static Object readTypedValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String type = node.get("type").asText();
        JsonNode value = node.get("value");
        if (value == null || value.isNull()) return null;
        return switch (type) {
            case "string" -> value.asText();
            case "long"   -> Long.parseLong(value.asText());
            case "uuid"   -> UUID.fromString(value.asText());
            case "null"   -> null;
            default -> throw new IllegalStateException("unknown typed-value tag: " + type);
        };
    }

    /** Writes the {@code expected} node in place during regen. */
    private static void writeExpected(ObjectNode fixture, Object actual) {
        ObjectNode expected = MAPPER.createObjectNode();
        if (actual == null) {
            expected.put("type", "null");
            expected.putNull("value");
        } else if (actual instanceof String s) {
            expected.put("type", "string");
            expected.put("value", s);
        } else if (actual instanceof Long l) {
            expected.put("type", "long");
            expected.put("value", l.toString());
        } else if (actual instanceof UUID u) {
            expected.put("type", "uuid");
            expected.put("value", u.toString());
        } else {
            throw new IllegalStateException(
                    "unsupported actual type in regen: " + actual.getClass().getName());
        }
        fixture.set("expected", expected);
    }

    /**
     * Type-aware equality — UUIDs compare via {@link UUID#equals}, Longs via numeric value,
     * everything else falls back to {@link java.util.Objects#equals}.
     */
    private static boolean typedEquals(Object expected, Object actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected instanceof UUID eu && actual instanceof UUID au) return eu.equals(au);
        if (expected instanceof Long el && actual instanceof Long al) return el.longValue() == al.longValue();
        return expected.equals(actual);
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static String formatValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + s + "\"";
        return v.toString();
    }
}
