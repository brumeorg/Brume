package com.fungle.brume.config.model;

import java.util.List;

/**
 * Extraction section of the Brume configuration file.
 *
 * <p>Controls how data is read from the source database:
 * <ul>
 *   <li>Which tables to extract and with which optional filters</li>
 *   <li>How deep to follow FK parent chains ({@code fkDepth})</li>
 *   <li>The JDBC fetch size used during cursor reads ({@code fetchSize})</li>
 *   <li>The INSERT batch size used during target writes ({@code batchSize})</li>
 * </ul>
 *
 * <p>Default values applied by the compact constructor when a field is absent from
 * the YAML file (i.e. deserialized as {@code 0} or {@code null}):
 * <ul>
 *   <li>{@code fkDepth} → 3</li>
 *   <li>{@code fetchSize} → 1000</li>
 *   <li>{@code batchSize} → 1000</li>
 *   <li>{@code chunkSize} → 10000</li>
 *   <li>{@code tables} → empty list</li>
 * </ul>
 *
 * @param fkDepth   maximum number of FK parent levels to resolve automatically;
 *                  defaults to 3 when unset
 * @param fetchSize JDBC cursor fetch size for streaming reads; defaults to 1000 when unset
 * @param batchSize INSERT batch size on the target; defaults to 1000 when unset
 * @param chunkSize number of rows processed per streaming chunk; defaults to 10000 when unset
 * @param tables    tables to extract, each with an optional SQL filter;
 *                  must not be empty (enforced by {@link com.fungle.brume.config.ConfigValidator})
 */
public record ExtractionConfig(int fkDepth,
                               int fetchSize,
                               int batchSize,
                               int chunkSize,
                               List<TableExtractionConfig> tables) {

    public ExtractionConfig(int fkDepth, int batchSize, List<TableExtractionConfig> tables) {
        this(fkDepth, 1_000, batchSize, 10_000, tables);
    }

    public ExtractionConfig(int fkDepth, int batchSize, int chunkSize, List<TableExtractionConfig> tables) {
        this(fkDepth, 1_000, batchSize, chunkSize, tables);
    }

    /** Applies default values when fields are absent (0 / null) in the YAML file. */
    public ExtractionConfig {
        fkDepth = (fkDepth == 0) ? 3 : fkDepth;
        fetchSize = (fetchSize == 0) ? 1_000 : fetchSize;
        batchSize = (batchSize == 0) ? 1000 : batchSize;
        chunkSize = (chunkSize == 0) ? 10_000 : chunkSize;
        tables = (tables == null) ? List.of() : tables;
    }
}

