package com.fungle.brume.anonymization;

import com.fungle.brume.anonymization.strategies.AnonymizationStrategy;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the anonymization of all rows in an {@link ExtractionResult}.
 *
 * <p>For each table declared in {@link AnonymizationConfig}, columns are anonymized
 * according to their declared {@link Strategy} and {@link SemanticType}. Tables or columns
 * not declared in the configuration are copied unchanged.
 *
 * <p>JSONB columns (type {@link SemanticType#JSONB}) are delegated to {@link JsonPathProcessor},
 * which traverses the embedded JSON tree and applies per-path strategies.
 *
 * <p>Cross-table value consistency is ensured via {@link SubstitutionDictionary}: two columns
 * sharing the same semantic key (via {@code linked_columns} config) will always produce the
 * same anonymized value for the same real input.
 *
 * <p>The engine produces a <em>new</em> {@link ExtractionResult} with anonymized rows — the
 * input result is never mutated.
 */
@Component
public class AnonymizationEngine {

    private static final Logger log = LoggerFactory.getLogger(AnonymizationEngine.class);

    private final StrategyResolver strategyResolver;
    private final SubstitutionDictionary substitutionDictionary;
    private final SemanticKeyResolver semanticKeyResolver;
    private final JsonPathProcessor jsonPathProcessor;
    private final Map<AnonymizationConfig, Map<String, List<ColumnConfig>>> rulesCache = new ConcurrentHashMap<>();

    /**
     * Creates a new {@code AnonymizationEngine}.
     *
     * @param strategyResolver       resolves strategy enum to strategy implementation
     * @param substitutionDictionary cross-table value consistency store
     * @param semanticKeyResolver    resolves the semantic key for a table+column pair
     * @param jsonPathProcessor      handles JSONB column traversal and per-path anonymization
     */
    public AnonymizationEngine(
            StrategyResolver strategyResolver,
            SubstitutionDictionary substitutionDictionary,
            SemanticKeyResolver semanticKeyResolver,
            JsonPathProcessor jsonPathProcessor) {
        this.strategyResolver = strategyResolver;
        this.substitutionDictionary = substitutionDictionary;
        this.semanticKeyResolver = semanticKeyResolver;
        this.jsonPathProcessor = jsonPathProcessor;
    }

    /**
     * Anonymizes all rows in {@code input} according to {@code config}, returning a new
     * {@link ExtractionResult} containing the transformed rows.
     *
     * <p>Tables not referenced in {@code config} are added to the result unchanged.
     * Columns not referenced in a table's config are passed through unchanged.
     *
     * @param input  the extraction result containing the raw rows to anonymize
     * @param config the anonymization configuration specifying which columns to transform
     * @param report the execution report collector; strategy usage is recorded here
     * @return a new {@link ExtractionResult} with anonymized rows in the same table order
     */
    public OrderedExtractionResult anonymize(OrderedExtractionResult input, AnonymizationConfig config,
                                              ExecutionReport report) {
        OrderedExtractionResult output = new OrderedExtractionResult();

        Map<String, List<ColumnConfig>> rulesByTable = getRulesIndex(config);

        for (String table : input.allTables()) {
            List<ColumnConfig> columnRules = rulesByTable.getOrDefault(table, List.of());
            List<ExtractedRow> inputRows = input.getRows(table);

            log.info("Anonymizing table '{}' — {} rows, {} column rules", table, inputRows.size(), columnRules.size());

            for (ExtractedRow row : inputRows) {
                output.add(anonymizeRow(row, config, report));
            }
        }

        return output;
    }

    /**
     * Anonymizes a single row according to the table rules declared in {@code config}.
     *
     * @param row    the source row to anonymize
     * @param config the anonymization configuration
     * @param report execution report collector used to record strategy usage
     * @return a new anonymized row; the input row is never mutated
     */
    public ExtractedRow anonymizeRow(ExtractedRow row, AnonymizationConfig config, ExecutionReport report) {
        Map<String, List<ColumnConfig>> rulesByTable = getRulesIndex(config);
        String table = row.table();
        List<ColumnConfig> columnRules = rulesByTable.getOrDefault(table, List.of());
        Map<String, Object> anonymizedData = anonymizeRowData(row, table, columnRules, config, report);
        return new ExtractedRow(table, anonymizedData);
    }

    /**
     * Anonymizes a single value using the given strategy and semantic type, with the
     * table/column context of the JSONB column that contains this leaf.
     *
     * <p>This method is exposed as package-accessible so that {@link JsonPathProcessor} can
     * delegate anonymization of individual JSON leaf values back to this engine without
     * duplicating strategy-resolution logic.
     *
     * <p>#79b — pre-fix used a synthetic semanticKey {@code strategy.name() + "." + type.name()}
     * which broke cross-table consistency: a same email value appearing in a native column
     * (e.g. {@code users.email}) and in a JSONB leaf (e.g. {@code users.profile.$.email})
     * produced two different fake values, breaking the {@code linked_columns} promise. Post-fix,
     * the semanticKey is resolved via {@link SemanticKeyResolver} using the host JSONB column's
     * table/column, so {@code linked_columns} entries covering the JSONB column propagate to
     * its leaves.
     *
     * @param value       the value to anonymize; may be {@code null}
     * @param type        the semantic type of the value
     * @param strategy    the anonymization strategy to apply
     * @param table       the host table of the JSONB column containing this leaf
     * @param column      the JSONB column name containing this leaf
     * @param config      the full anonymization config (consulted for {@code linked_columns})
     * @return the anonymized value, or {@code null} if {@code strategy} is NULLIFY
     */
    Object anonymizeValue(Object value, SemanticType type, Strategy strategy,
                          String table, String column, AnonymizationConfig config) {
        AnonymizationStrategy impl = strategyResolver.resolve(strategy);
        String semanticKey = semanticKeyResolver.resolve(table, column, config);
        return impl.anonymize(value, type, semanticKey, substitutionDictionary);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Anonymizes all columns of a single row according to the declared column rules.
     *
     * @param row         the original row
     * @param table       the table name (used for semantic key resolution)
     * @param columnRules the list of column anonymization rules for this table
     * @param config      the full anonymization config (needed for linked_columns lookup)
     * @param report      the execution report collector; strategy usage is recorded here
     * @return a new map with the anonymized column values
     */
    private Map<String, Object> anonymizeRowData(ExtractedRow row, String table,
                                                 List<ColumnConfig> columnRules,
                                                 AnonymizationConfig config,
                                                 ExecutionReport report) {
        Map<String, Object> result = new HashMap<>(row.data());

        for (ColumnConfig colConfig : columnRules) {
            String column = colConfig.name();
            Object originalValue = row.data().get(column);

            Object anonymized = anonymizeColumn(originalValue, table, column, colConfig, config, report);
            result.put(column, anonymized);
        }

        return result;
    }

    /**
     * Anonymizes a single column value using the appropriate strategy.
     *
     * <p>If the column's type is {@link SemanticType#JSONB}, delegates to
     * {@link JsonPathProcessor} for nested-field anonymization and records "JSONB" as the strategy.
     *
     * @param value     the original column value
     * @param table     the table name
     * @param column    the column name
     * @param colConfig the column anonymization rule
     * @param config    the full anonymization config (for linked_columns)
     * @param report    the execution report collector; strategy usage is recorded here
     * @return the anonymized value
     */
    private Object anonymizeColumn(Object value, String table, String column,
                                    ColumnConfig colConfig, AnonymizationConfig config,
                                    ExecutionReport report) {
        if (colConfig.type() == SemanticType.JSONB) {
            List<com.fungle.brume.config.model.JsonPathConfig> paths =
                    colConfig.jsonPaths() != null ? colConfig.jsonPaths() : List.of();
            report.recordStrategy(table, column, "JSONB");
            return jsonPathProcessor.process(value, paths, this, table, column, config);
        }

        AnonymizationStrategy impl = strategyResolver.resolve(colConfig.strategy());
        String semanticKey = semanticKeyResolver.resolve(table, column, config);
        Object anonymized = impl.anonymize(value, colConfig.type(), semanticKey, substitutionDictionary);

        report.recordStrategy(table, column, colConfig.strategy().name());

        return anonymized;
    }

    /**
     * Builds an index of column rules grouped by table name for fast lookup.
     *
     * @param config the anonymization configuration
     * @return a map from table name to list of {@link ColumnConfig}
     */
    private Map<String, List<ColumnConfig>> buildRulesIndex(AnonymizationConfig config) {
        Map<String, List<ColumnConfig>> index = new HashMap<>();
        if (config == null || config.tables() == null) {
            return index;
        }
        for (TableAnonymizationConfig tableConfig : config.tables()) {
            if (tableConfig.columns() != null) {
                index.put(tableConfig.table(), Collections.unmodifiableList(new ArrayList<>(tableConfig.columns())));
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private Map<String, List<ColumnConfig>> getRulesIndex(AnonymizationConfig config) {
        if (config == null) {
            return Map.of();
        }
        return rulesCache.computeIfAbsent(config, this::buildRulesIndex);
    }
}

