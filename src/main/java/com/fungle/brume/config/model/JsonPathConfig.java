package com.fungle.brume.config.model;

/**
 * Anonymization rule for a single JSON path inside a JSONB column.
 *
 * <p>Each entry targets one field in the JSON tree (e.g. {@code $.user.email})
 * and declares which strategy and semantic type to apply to that field.
 *
 * @param path     JSONPath expression starting with {@code $} (e.g. {@code $.user.email})
 * @param type     semantic type of the targeted field — drives fake data generation or masking
 * @param strategy anonymization strategy to apply to the targeted field
 */
public record JsonPathConfig(
        String path,
        SemanticType type,
        Strategy strategy) {
}

