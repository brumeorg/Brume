package com.fungle.brume.config.model;

import java.util.List;

/**
 * Anonymization rule for a single column within a table.
 *
 * <p>Usage constraints (enforced by {@link com.fungle.brume.config.ConfigValidator}):
 * <ul>
 *   <li>{@link Strategy#FAKE} requires a non-null {@code type}</li>
 *   <li>{@code type = }{@link SemanticType#JSONB} requires a non-empty {@code jsonPaths} list</li>
 *   <li>{@code minYear} / {@code maxYear} are only valid when
 *       {@code strategy = FAKE} and {@code type = DATE}; both bounds must be in {@code [1900, 2100]}
 *       and {@code minYear < maxYear}. Omit both to fall back to the adult-DOB default
 *       anchor (1935-2008).</li>
 * </ul>
 *
 * @param name      column name as it appears in the database schema
 * @param strategy  anonymization strategy to apply to this column
 * @param type      semantic type — may be {@code null} for strategies that do not need it
 *                  (e.g. {@link Strategy#HASH}, {@link Strategy#NULLIFY}, {@link Strategy#KEEP},
 *                  {@link Strategy#FPE_ID})
 * @param jsonPaths ordered list of JSON path rules; only relevant when
 *                  {@code type == }{@link SemanticType#JSONB}
 * @param minYear   inclusive lower year bound for {@link Strategy#FAKE} + {@link SemanticType#DATE};
 *                  {@code null} = use the default (adult DOB anchor). Only read for FAKE+DATE.
 * @param maxYear   exclusive upper year bound for {@link Strategy#FAKE} + {@link SemanticType#DATE};
 *                  {@code null} = use the default (adult DOB anchor). Only read for FAKE+DATE.
 */
public record ColumnConfig(
        String name,
        Strategy strategy,
        SemanticType type,
        List<JsonPathConfig> jsonPaths,
        Integer minYear,
        Integer maxYear) {

    /**
     * 4-arg compat ctor kept for the ~40 call sites written before {@code minYear}/{@code maxYear}
     * were added (#45b). Defaults both bounds to {@code null} — identical to the pre-#45b behavior.
     */
    public ColumnConfig(String name, Strategy strategy, SemanticType type, List<JsonPathConfig> jsonPaths) {
        this(name, strategy, type, jsonPaths, null, null);
    }
}
