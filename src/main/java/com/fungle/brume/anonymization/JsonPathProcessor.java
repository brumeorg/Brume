package com.fungle.brume.anonymization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.JsonPathConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Processes anonymization rules on JSONB column values using simple dot-notation JSON paths.
 *
 * <p>Supported path syntax: {@code $.field} or {@code $.parent.child.leaf} — any depth.
 * The leading {@code $.} is stripped; remaining segments are used to traverse a Jackson
 * {@link ObjectNode} tree.
 *
 * <p>If a path segment does not resolve to an {@link ObjectNode} at any intermediate level,
 * the path is skipped with a WARN log (no exception thrown).
 *
 * <p>The input value is expected to be either a {@link String} (raw JSON) or an already-parsed
 * {@link JsonNode}. The return value is always a {@link String} (re-serialized JSON).
 *
 * <p>JSON leaf types are preserved across anonymization (audit § B4, ADR-0019) — a numeric
 * leaf stays a number, a boolean stays a boolean. Coercion only happens (with a WARN log)
 * when the configured strategy returns a value that cannot fit the original type.
 */
@Component
public class JsonPathProcessor {

    private static final Logger log = LoggerFactory.getLogger(JsonPathProcessor.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates a new {@code JsonPathProcessor} using the provided Jackson {@link ObjectMapper}.
     *
     * @param objectMapper Jackson mapper used for JSON parsing and serialization
     */
    public JsonPathProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Applies each {@link JsonPathConfig} in {@code paths} to the given JSON value,
     * anonymizing the targeted field using the supplied {@link AnonymizationEngine}.
     *
     * <p>The engine is passed back so that {@code JsonPathProcessor} itself does not need
     * to know about strategies — it only handles JSON traversal.
     *
     * <p>#79b — {@code hostTable}, {@code hostColumn} and {@code config} are propagated to
     * {@link AnonymizationEngine#anonymizeValue} so the per-leaf semanticKey can be resolved
     * via {@link SemanticKeyResolver} (consulting {@code linked_columns}). Pre-fix used a
     * synthetic semanticKey {@code strategy.name() + "." + type.name()} which broke
     * cross-table consistency.
     *
     * @param jsonValue  the original JSONB column value; may be a {@link String} or {@link JsonNode}
     * @param paths      ordered list of JSON path rules to apply
     * @param engine     the anonymization engine used to anonymize each extracted leaf value
     * @param hostTable  the host table of the JSONB column being processed
     * @param hostColumn the JSONB column name being processed
     * @param config     the full anonymization config (consulted for {@code linked_columns})
     * @return the re-serialized JSON string with all targeted fields anonymized;
     *         returns the original value as a string if parsing fails
     * @throws IllegalArgumentException if {@code paths} is {@code null}
     */
    public Object process(Object jsonValue, List<JsonPathConfig> paths, AnonymizationEngine engine,
                          String hostTable, String hostColumn, AnonymizationConfig config) {
        if (jsonValue == null) {
            return null;
        }
        if (paths == null || paths.isEmpty()) {
            return jsonValue;
        }

        // Parse to a mutable tree. A malformed source JSONB is the only legitimate degraded path :
        // log + return the original (still as-is, but at least the run keeps moving). Anything
        // else — including any failure inside a strategy via applyPath — must fail loud so PII
        // never lands on the target unanonymized (audit § B2, ADR-0018).
        JsonNode root;
        try {
            root = (jsonValue instanceof JsonNode jn)
                    ? jn.deepCopy()
                    : objectMapper.readTree(jsonValue.toString());
        } catch (IOException e) {
            log.warn("Malformed JSONB value — returning original. Reason: {}", e.getMessage());
            return jsonValue;
        }

        if (!(root instanceof ObjectNode rootObject)) {
            // Not a JSON object — cannot traverse; return as-is
            return jsonValue;
        }

        for (JsonPathConfig pathConfig : paths) {
            // Any exception thrown by engine.anonymizeValue propagates : the whole run fails.
            applyPath(rootObject, pathConfig, engine, hostTable, hostColumn, config);
        }

        try {
            return objectMapper.writeValueAsString(rootObject);
        } catch (JsonProcessingException e) {
            // Should not happen with a tree we just successfully parsed and mutated, but if it
            // does, fail loud — silently returning the original would defeat anonymization.
            throw new UncheckedIOException(
                    "Failed to serialize anonymized JSONB after successful parse + apply", e);
        }
    }

    /**
     * Applies a single {@link JsonPathConfig} to the given root {@link ObjectNode}.
     *
     * <p>Traverses the path segments, stopping early if any intermediate node is not an
     * {@link ObjectNode}. If the final leaf exists, its value is anonymized in place,
     * using the host JSONB column's table/column for semanticKey resolution (#79b).
     */
    private void applyPath(ObjectNode root, JsonPathConfig pathConfig, AnonymizationEngine engine,
                           String hostTable, String hostColumn, AnonymizationConfig config) {
        // Strip leading "$." (e.g. "$.user.email" → ["user", "email"])
        String rawPath = pathConfig.path();
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        String stripped = rawPath.startsWith("$.") ? rawPath.substring(2) : rawPath;
        String[] segments = stripped.split("\\.");

        if (segments.length == 0) {
            return;
        }

        // Navigate to the parent of the leaf
        ObjectNode current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonNode child = current.get(segments[i]);
            if (child instanceof ObjectNode childObject) {
                current = childObject;
            } else {
                log.warn("JSON path '{}' — segment '{}' is not an object node; skipping",
                        rawPath, segments[i]);
                return;
            }
        }

        // Anonymize the leaf field
        String lastKey = segments[segments.length - 1];
        JsonNode leafNode = current.get(lastKey);
        if (leafNode == null || leafNode.isNull()) {
            return;
        }

        String originalValue = leafNode.asText();
        Object anonymized = engine.anonymizeValue(
                originalValue, pathConfig.type(), pathConfig.strategy(),
                hostTable, hostColumn, config);

        putAnonymized(current, lastKey, leafNode, anonymized, rawPath);
    }

    /**
     * Writes {@code anonymized} back into {@code parent} at {@code key}, preserving the JSON
     * type of {@code originalLeaf} whenever possible (audit § B4, ADR-0019).
     *
     * <p>Type preservation rules :
     * <ul>
     *   <li>If the strategy already returned a typed JSON-friendly value (Number / Boolean),
     *       it is written with the matching {@code put(...)} overload — no string coercion.</li>
     *   <li>Else, if the original leaf was numeric or boolean, the anonymized string is parsed
     *       back to that type. On parse failure, we log a WARN and degrade to a string write
     *       (the only case where JSON schema drift is unavoidable — caller chose an
     *       incompatible strategy for the leaf type).</li>
     *   <li>Else, the value is written as a string — the prior behavior for string leaves.</li>
     * </ul>
     */
    private void putAnonymized(ObjectNode parent, String key, JsonNode originalLeaf,
                                Object anonymized, String rawPath) {
        if (anonymized == null) {
            parent.putNull(key);
            return;
        }
        if (anonymized instanceof Long l) {
            parent.put(key, l);
            return;
        }
        if (anonymized instanceof Integer i) {
            parent.put(key, i);
            return;
        }
        if (anonymized instanceof Double d) {
            parent.put(key, d);
            return;
        }
        if (anonymized instanceof Float f) {
            parent.put(key, f);
            return;
        }
        if (anonymized instanceof Boolean b) {
            parent.put(key, b);
            return;
        }
        if (anonymized instanceof Number n) {
            parent.put(key, n.doubleValue());
            return;
        }

        String anonymizedStr = anonymized.toString();
        if (originalLeaf.isNumber()) {
            try {
                if (originalLeaf.isIntegralNumber()) {
                    parent.put(key, Long.parseLong(anonymizedStr));
                } else {
                    parent.put(key, Double.parseDouble(anonymizedStr));
                }
                return;
            } catch (NumberFormatException e) {
                // #79a — anonymizedStr may carry a real metier value (KEEP at this leaf or
                // schema drift). Don't log the raw value (ADR-0025 no PII in logs) — length
                // alone is enough signal to distinguish "obvious garbage" from "wrong-typed
                // but plausible" without leaking PII into operator logs.
                log.warn("JSON path '{}' — anonymized value (length={}, masked) is not numeric but original leaf was {}; storing as string (schema drift)",
                        rawPath, anonymizedStr.length(), originalLeaf.getNodeType());
            }
        } else if (originalLeaf.isBoolean()) {
            if ("true".equalsIgnoreCase(anonymizedStr)) {
                parent.put(key, Boolean.TRUE);
                return;
            }
            if ("false".equalsIgnoreCase(anonymizedStr)) {
                parent.put(key, Boolean.FALSE);
                return;
            }
            // #79a — same leak path as numeric drift above. Mask the raw value.
            log.warn("JSON path '{}' — anonymized value (length={}, masked) is not a boolean but original leaf was BOOLEAN; storing as string (schema drift)",
                    rawPath, anonymizedStr.length());
        }
        parent.put(key, anonymizedStr);
    }
}

