package com.fungle.brume.config.model;

import java.util.List;

/**
 * Anonymization rule for a single column within a table.
 *
 * <p>Usage constraints (enforced by {@link com.fungle.brume.config.ConfigValidator}):
 * <ul>
 *   <li>{@link Strategy#FAKE} requires a non-null {@code type}</li>
 *   <li>{@code type = }{@link SemanticType#JSONB} requires a non-empty {@code jsonPaths} list</li>
 * </ul>
 *
 * @param name      column name as it appears in the database schema
 * @param strategy  anonymization strategy to apply to this column
 * @param type      semantic type — may be {@code null} for strategies that do not need it
 *                  (e.g. {@link Strategy#HASH}, {@link Strategy#NULLIFY}, {@link Strategy#KEEP},
 *                  {@link Strategy#FPE_ID})
 * @param jsonPaths ordered list of JSON path rules; only relevant when
 *                  {@code type == }{@link SemanticType#JSONB}
 */
public record ColumnConfig(
        String name,
        Strategy strategy,
        SemanticType type,
        List<JsonPathConfig> jsonPaths) {
}

