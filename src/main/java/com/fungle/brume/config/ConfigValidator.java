package com.fungle.brume.config;

import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates an {@link AnonymizerConfig} for semantic correctness before the replication
 * pipeline is allowed to start.
 *
 * <p>Validation is fail-fast: the first violation found throws a {@link ConfigurationException}
 * with a clear, actionable message. No database connection is opened before this check passes.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>{@code extraction.tables} must not be empty — protects against accidental full-DB copy</li>
 *   <li>Every {@code extraction.tables[].filter} passes {@link ExtractionFilterValidator}
 *       (anti SQL-injection denylist heuristic — see ADR-0017)</li>
 *   <li>Every column with strategy {@link Strategy#FAKE} must declare a non-null
 *       {@link SemanticType}</li>
 *   <li>Every column with type {@link SemanticType#JSONB} must declare a non-empty
 *       {@code jsonPaths} list</li>
 * </ul>
 */
@Component
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    private static final String IDENTIFIER_RULE_HINT =
            "Write table and column names exactly as stored in PostgreSQL (case, accents and "
                    + "spaces included, without double quotes) : 1 to 63 bytes in UTF-8, no NUL character.";

    /**
     * Validates the given configuration.
     *
     * @param config the parsed anonymizer configuration to validate
     * @throws ConfigurationException if any validation rule is violated
     */
    public void validate(AnonymizerConfig config) {
        log.debug("Validating anonymizer config...");
        validateExtraction(config);
        validateAnonymization(config);
        log.debug("Anonymizer config is valid.");
    }

    /**
     * Validates the extraction section.
     *
     * @param config the parsed anonymizer configuration
     * @throws ConfigurationException if {@code extraction.tables} is empty
     */
    private void validateExtraction(AnonymizerConfig config) {
        if (config.extraction() == null || config.extraction().tables().isEmpty()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_EXTRACTION_TABLES_EMPTY,
                    "extraction.tables must not be empty",
                    "Brume refuses to copy the entire database by default — declare at "
                            + "least one entry under 'extraction.tables' in config.yaml, naming "
                            + "the tables you want to replicate.");
        }

        if (config.extraction().fkDepth() <= 0) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_EXTRACTION_FK_DEPTH_INVALID,
                    "extraction.fk_depth must be > 0 (got " + config.extraction().fkDepth() + ")",
                    "Set 'extraction.fk_depth' to a positive integer in config.yaml. "
                            + "Typical values are 1-3 depending on schema depth.");
        }
        if (config.extraction().fetchSize() <= 0) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_EXTRACTION_FETCH_SIZE_INVALID,
                    "extraction.fetch_size must be > 0 (got " + config.extraction().fetchSize() + ")",
                    "Set 'extraction.fetch_size' to a positive integer in config.yaml "
                            + "(recommended starting point: 1000).");
        }
        if (config.extraction().batchSize() <= 0) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_EXTRACTION_BATCH_SIZE_INVALID,
                    "extraction.batch_size must be > 0 (got " + config.extraction().batchSize() + ")",
                    "Set 'extraction.batch_size' to a positive integer in config.yaml "
                            + "(recommended starting point: 1000).");
        }
        if (config.extraction().chunkSize() <= 0) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_EXTRACTION_CHUNK_SIZE_INVALID,
                    "extraction.chunk_size must be > 0 (got " + config.extraction().chunkSize() + ")",
                    "Set 'extraction.chunk_size' to a positive integer in config.yaml "
                            + "(recommended starting point: 10000).");
        }

        // Validate table names + filter expressions
        for (var tableConfig : config.extraction().tables()) {
            try {
                SqlIdentifiers.validate(tableConfig.table());
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException(
                        BrumeErrorCode.CONFIG_INVALID_IDENTIFIER,
                        "Invalid table name in extraction.tables: '" + tableConfig.table()
                                + "' — " + e.getMessage(),
                        IDENTIFIER_RULE_HINT);
            }
            ExtractionFilterValidator.validate(tableConfig.table(), tableConfig.filter());
        }
    }

    /**
     * Validates the anonymization section: each column rule must be self-consistent.
     *
     * @param config the parsed anonymizer configuration
     * @throws ConfigurationException if any column rule violates a constraint
     */
    private void validateAnonymization(AnonymizerConfig config) {
        List<TableAnonymizationConfig> tables = config.anonymization().tables();
        for (TableAnonymizationConfig tableConfig : tables) {
            String tableName = tableConfig.table();

            // Validate table name
            try {
                SqlIdentifiers.validate(tableName);
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException(
                        BrumeErrorCode.CONFIG_INVALID_IDENTIFIER,
                        "Invalid table name in anonymization.tables: '" + tableName
                                + "' — " + e.getMessage(),
                        IDENTIFIER_RULE_HINT);
            }

            List<ColumnConfig> columns = tableConfig.columns();
            if (columns == null) continue;

            for (ColumnConfig col : columns) {
                // Validate column name
                try {
                    SqlIdentifiers.validate(col.name());
                } catch (IllegalArgumentException e) {
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_INVALID_IDENTIFIER,
                            "Invalid column name in '" + tableName + "': '" + col.name()
                                    + "' — " + e.getMessage(),
                            IDENTIFIER_RULE_HINT);
                }

                validateColumn(tableName, col);
            }
        }
    }

    /**
     * Validates a single column rule.
     *
     * @param tableName name of the enclosing table (used in error messages)
     * @param col       the column configuration to validate
     * @throws ConfigurationException if the column rule is invalid
     */
    private void validateColumn(String tableName, ColumnConfig col) {
        String ref = tableName + "." + col.name();

        // FAKE strategy requires a semantic type to know what kind of data to generate
        if (col.strategy() == Strategy.FAKE && col.type() == null) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_STRATEGY_REQUIRES_TYPE,
                    "Column '" + ref + "' uses strategy FAKE but has no 'type' declared",
                    "Add a SemanticType (e.g. 'type: EMAIL') to tell Brume what kind of "
                            + "fake data to generate, or switch to a strategy that does not need "
                            + "a type (HASH, MASK, NULLIFY, KEEP).");
        }

        // JSONB type requires json_paths to know which nested fields to anonymize
        if (col.type() == SemanticType.JSONB) {
            if (col.jsonPaths() == null || col.jsonPaths().isEmpty()) {
                throw new ConfigurationException(
                        BrumeErrorCode.CONFIG_JSONB_REQUIRES_JSON_PATHS,
                        "Column '" + ref + "' has type JSONB but no 'json_paths' declared",
                        "Add at least one entry under 'json_paths' to specify which JSON "
                                + "fields to anonymize (e.g. '- path: $.email\\n  type: EMAIL\\n  "
                                + "strategy: FAKE').");
            }
        }

        validateDateRange(col, ref);
    }

    /**
     * Validates {@code min_year} / {@code max_year} (#45b).
     *
     * <p>Rules:
     * <ul>
     *   <li>Bounds are only accepted when {@code strategy = FAKE} and {@code type = DATE}.</li>
     *   <li>Both bounds must be in {@code [1900, 2100]} — well before PG's {@code date}
     *       support edges and after the first realistic DOB we'd anonymize.</li>
     *   <li>{@code min < max}. Equal bounds would collapse the range to a single day.</li>
     *   <li>Omitting both is legal — falls back to the adult-DOB default anchor (1935-2008).</li>
     * </ul>
     */
    private void validateDateRange(ColumnConfig col, String ref) {
        Integer min = col.minYear();
        Integer max = col.maxYear();
        if (min == null && max == null) {
            return;
        }

        boolean isFakeDate = col.strategy() == Strategy.FAKE && col.type() == SemanticType.DATE;
        if (!isFakeDate) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_DATE_RANGE_INVALID,
                    "Column '" + ref + "' declares min_year/max_year but strategy is '"
                            + col.strategy() + "' with type '" + col.type() + "'",
                    "min_year/max_year are only read when strategy=FAKE and type=DATE. "
                            + "Remove the bounds, or switch to FAKE+DATE if you want a custom "
                            + "year range.");
        }
        if (min == null || max == null) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_DATE_RANGE_INVALID,
                    "Column '" + ref + "' declares only one of min_year/max_year — both must "
                            + "be set together",
                    "Either declare both bounds (e.g. 'min_year: 2008' + 'max_year: 2026') or "
                            + "omit both to use the adult-DOB default (1935-2008).");
        }
        if (min < 1900 || min > 2100 || max < 1900 || max > 2100) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_DATE_RANGE_INVALID,
                    "Column '" + ref + "' has min_year/max_year out of [1900, 2100] (got "
                            + min + "/" + max + ")",
                    "Pick a range inside [1900, 2100]. If your dataset genuinely needs older "
                            + "or future dates, open an issue — we'll widen the bounds.");
        }
        if (min >= max) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_DATE_RANGE_INVALID,
                    "Column '" + ref + "' has min_year (" + min + ") >= max_year (" + max + ")",
                    "min_year must be strictly less than max_year (max_year is exclusive).");
        }
    }
}
