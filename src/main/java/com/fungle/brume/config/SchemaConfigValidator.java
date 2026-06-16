package com.fungle.brume.config;

import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ColumnReference;
import com.fungle.brume.config.model.LinkedColumnsConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.util.Levenshtein;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Validates an {@link AnonymizerConfig} against a loaded {@link DatabaseSchema} to catch
 * configurations that are syntactically valid but reference tables, columns, or
 * type/strategy combinations that do not match the source schema.
 *
 * <p>Run by {@code ReplicationAgent} after {@code SchemaAnalyzer.analyze()} and after
 * {@code FkStrategyPropagator.propagate()}, so it sees the final config (user-declared
 * + auto-propagated FK rules).
 *
 * <p>Failure modes split in two categories:
 * <ul>
 *   <li><strong>Hard errors</strong> (always thrown): a table or column is referenced in
 *       the config but absent from the schema. These are typos / drift. The error message
 *       includes a Levenshtein-based "did you mean…" suggestion when one exists.</li>
 *   <li><strong>Type/strategy mismatches</strong>: a column's PostgreSQL type is a poor
 *       fit for the chosen strategy (e.g. {@code FAKE EMAIL} on a {@code bigint} column).
 *       Logged as WARN by default; promoted to {@link ConfigurationException} when
 *       {@link BrumeProperties#strictConfig()} is true.</li>
 * </ul>
 *
 * <p>The validator is intentionally narrow: it does not re-validate things already covered
 * elsewhere — extraction filter SQL ({@code ExtractionFilterValidator}), JSONB path syntax
 * (out of scope), or FK strategy compatibility ({@code FkStrategyPropagator}).
 */
@Component
public class SchemaConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaConfigValidator.class);

    /** Maximum edit distance for suggesting a close match in error messages. */
    private static final int SUGGEST_MAX_DISTANCE = 3;

    /**
     * Validates the config against the schema, raising hard errors for missing references
     * and emitting type/strategy warnings (or errors when {@code strict}).
     *
     * @param config the final, post-propagation anonymizer configuration
     * @param schema the source database schema
     * @param strict when {@code true}, type/strategy mismatches are escalated to
     *               {@link ConfigurationException} instead of WARN logs
     * @throws ConfigurationException when a referenced table or column does not exist,
     *                                or when a type/strategy mismatch is found in strict mode
     */
    public void validate(AnonymizerConfig config, DatabaseSchema schema, boolean strict) {
        log.debug("Validating anonymizer config against source schema (strict={})", strict);
        validateExtractionTables(config, schema);
        validateAnonymizationTables(config, schema, strict);
        validateLinkedColumns(config, schema);
        log.debug("Anonymizer config matches source schema");
    }

    // -------------------------------------------------------------------------
    // Extraction tables — only existence check (no columns to validate here)
    // -------------------------------------------------------------------------

    private void validateExtractionTables(AnonymizerConfig config, DatabaseSchema schema) {
        if (config.extraction() == null) return;
        for (TableExtractionConfig table : config.extraction().tables()) {
            requireTable(table.table(), "extraction.tables", schema);
        }
    }

    // -------------------------------------------------------------------------
    // Anonymization tables — existence + per-column existence + type/strategy fit
    // -------------------------------------------------------------------------

    private void validateAnonymizationTables(AnonymizerConfig config, DatabaseSchema schema, boolean strict) {
        for (TableAnonymizationConfig table : config.anonymization().tables()) {
            TableMetadata meta = requireTable(table.table(), "anonymization.tables", schema);
            if (table.columns() == null) continue;
            for (ColumnConfig col : table.columns()) {
                ColumnMetadata colMeta = findColumn(meta, col.name());
                if (colMeta == null) {
                    String didYouMean = suggest(col.name(), meta.columns().stream().map(ColumnMetadata::name).toList());
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_SCHEMA_UNKNOWN_COLUMN,
                            "Unknown column '" + table.table() + "." + col.name() + "' referenced in "
                                    + "anonymization.tables — column does not exist in source schema.",
                            didYouMean.isEmpty()
                                    ? "Remove the column entry from anonymization.tables['"
                                            + table.table() + "'].columns, or run 'brume plan' to "
                                            + "see the current schema and pick a column that exists."
                                    : didYouMean.trim() + " Otherwise, remove the entry or pick a "
                                            + "column that exists in the source schema.");
                }
                checkTypeStrategyFit(table.table(), colMeta, col, strict);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Linked columns — existence check on each (table, column) reference
    // -------------------------------------------------------------------------

    private void validateLinkedColumns(AnonymizerConfig config, DatabaseSchema schema) {
        for (LinkedColumnsConfig linked : config.anonymization().linkedColumns()) {
            if (linked.columns() == null) continue;
            for (ColumnReference ref : linked.columns()) {
                TableMetadata meta = requireTable(ref.table(),
                        "anonymization.linked_columns[" + linked.semanticKey() + "]", schema);
                if (findColumn(meta, ref.column()) == null) {
                    String didYouMean = suggest(ref.column(),
                            meta.columns().stream().map(ColumnMetadata::name).toList());
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_SCHEMA_UNKNOWN_COLUMN,
                            "Unknown column '" + ref.table() + "." + ref.column() + "' referenced in "
                                    + "anonymization.linked_columns[" + linked.semanticKey() + "] — "
                                    + "column does not exist in source schema.",
                            didYouMean.isEmpty()
                                    ? "Remove the column entry from anonymization.linked_columns['"
                                            + linked.semanticKey() + "'], or run 'brume plan' to see "
                                            + "the current schema and pick a column that exists."
                                    : didYouMean.trim() + " Otherwise, remove the entry or pick a "
                                            + "column that exists in the source schema.");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Type/strategy compatibility matrix
    // -------------------------------------------------------------------------

    /** Coarse-grained PostgreSQL type families used to rule strategy fit. */
    private enum TypeFamily { NUMERIC, STRING, UUID, JSON, BOOLEAN, TEMPORAL, OTHER }

    /**
     * Maps a PostgreSQL {@code information_schema.columns.data_type} string to a coarse-grained
     * {@link TypeFamily}.
     *
     * <p>#79f — pre-fix used substring matching ({@code t.contains("int")},
     * {@code t.contains("char")}, {@code t.contains("time")}, {@code t.contains("json")},
     * {@code t.contains("date")}) which produced false positives on user-defined types whose
     * name happens to embed one of those substrings : {@code point} (CONTAINS "int") was
     * classed NUMERIC, {@code arch_log} (CONTAINS "char") was classed STRING, etc. The fix
     * uses a strict whitelist of standard PostgreSQL canonical type names (as returned by
     * {@code information_schema} — array types appear as {@code ARRAY}, user-defined types
     * as {@code USER-DEFINED}, neither matching the whitelist → {@link TypeFamily#OTHER}).
     *
     * <p>Also fixes the {@code toLowerCase()} → {@code toLowerCase(Locale.ROOT)} consistency
     * gap with {@code BrumePropertiesValidator}.
     */
    private TypeFamily categorize(String pgType) {
        if (pgType == null) return TypeFamily.OTHER;
        String t = pgType.toLowerCase(Locale.ROOT).trim();
        return switch (t) {
            case "smallint", "integer", "bigint",
                 "int", "int2", "int4", "int8",
                 "decimal", "numeric",
                 "real", "double precision",
                 "float", "float4", "float8",
                 "serial", "bigserial", "smallserial" -> TypeFamily.NUMERIC;
            case "character", "character varying", "varchar",
                 "text", "citext", "name", "bpchar", "char" -> TypeFamily.STRING;
            case "uuid" -> TypeFamily.UUID;
            case "json", "jsonb" -> TypeFamily.JSON;
            case "boolean", "bool" -> TypeFamily.BOOLEAN;
            case "timestamp", "timestamp with time zone", "timestamp without time zone",
                 "date", "time", "time with time zone", "time without time zone",
                 "interval", "timestamptz", "timetz" -> TypeFamily.TEMPORAL;
            default -> TypeFamily.OTHER;
        };
    }

    private void checkTypeStrategyFit(String tableName, ColumnMetadata colMeta, ColumnConfig col, boolean strict) {
        Strategy strategy = col.strategy();
        TypeFamily family = categorize(colMeta.dataType());
        String ref = tableName + "." + col.name();

        Verdict verdict = switch (strategy) {
            case FPE_ID -> switch (family) {
                case NUMERIC -> Verdict.OK;
                case STRING  -> Verdict.SUSPECT;
                default      -> Verdict.INCOMPATIBLE;
            };
            case FPE_UUID -> switch (family) {
                case UUID    -> Verdict.OK;
                case STRING  -> Verdict.SUSPECT;
                default      -> Verdict.INCOMPATIBLE;
            };
            case HASH -> switch (family) {
                case STRING  -> Verdict.OK;
                case JSON    -> Verdict.INCOMPATIBLE;
                default      -> Verdict.SUSPECT;
            };
            case FAKE -> {
                if (col.type() == SemanticType.JSONB) {
                    yield (family == TypeFamily.JSON) ? Verdict.OK : Verdict.INCOMPATIBLE;
                }
                yield switch (family) {
                    case STRING -> Verdict.OK;
                    default     -> Verdict.INCOMPATIBLE;
                };
            }
            case MASK -> switch (family) {
                case STRING  -> Verdict.OK;
                case NUMERIC -> Verdict.SUSPECT;
                default      -> Verdict.INCOMPATIBLE;
            };
            case NULLIFY, KEEP -> Verdict.OK;
        };

        if (verdict == Verdict.OK) return;

        String message = "Column '" + ref + "' has PostgreSQL type '" + colMeta.dataType()
                + "' but uses strategy " + strategy
                + (col.type() != null ? " (semantic type " + col.type() + ")" : "")
                + " — "
                + (verdict == Verdict.INCOMPATIBLE
                    ? "this combination is unlikely to produce a valid value at runtime."
                    : "this combination is unusual and may yield unexpected results.");

        // Q1 reco: INCOMPATIBLE = warning by default, error under --strict-config;
        // SUSPECT = warning always (operator's call, never blocking).
        if (verdict == Verdict.INCOMPATIBLE && strict) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_SCHEMA_TYPE_STRATEGY_INCOMPATIBLE,
                    message,
                    "Pick a strategy compatible with the column's PostgreSQL type, or drop "
                            + "--strict-config to keep this rule (the mismatch will then surface "
                            + "as a WARN at boot, not an error).");
        }
        log.warn("[config-schema] {}{}", message,
                verdict == Verdict.INCOMPATIBLE ? " Use --strict-config to fail fast on this." : "");
    }

    private enum Verdict { OK, SUSPECT, INCOMPATIBLE }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TableMetadata requireTable(String tableName, String configPath, DatabaseSchema schema) {
        TableMetadata meta = findTable(schema, tableName);
        if (meta == null) {
            String didYouMean = suggest(tableName, schema.tableNames());
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_SCHEMA_UNKNOWN_TABLE,
                    "Unknown table '" + tableName + "' referenced in " + configPath
                            + " — table does not exist in source schema.",
                    didYouMean.isEmpty()
                            ? "Remove the entry from " + configPath + ", or run 'brume plan' to "
                                    + "see the current schema and pick a table that exists."
                            : didYouMean.trim() + " Otherwise, remove the entry or pick a table "
                                    + "that exists in the source schema.");
        }
        return meta;
    }

    private TableMetadata findTable(DatabaseSchema schema, String tableName) {
        // Postgres folds unquoted identifiers to lowercase; match case-insensitively to avoid
        // a confusing "table not found" when the only difference is a capital letter.
        TableMetadata direct = schema.tables().get(tableName);
        if (direct != null) return direct;
        for (var entry : schema.tables().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(tableName)) return entry.getValue();
        }
        return null;
    }

    private ColumnMetadata findColumn(TableMetadata meta, String columnName) {
        for (ColumnMetadata c : meta.columns()) {
            if (c.name().equalsIgnoreCase(columnName)) return c;
        }
        return null;
    }

    private String suggest(String unknown, java.util.Collection<String> candidates) {
        Optional<String> match = Levenshtein.closestMatch(unknown, candidates, SUGGEST_MAX_DISTANCE);
        return match.map(s -> " Did you mean '" + s + "'?").orElse("");
    }
}
