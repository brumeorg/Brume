package com.fungle.brume.anonymization;

import com.fungle.brume.anonymization.strategies.AnonymizationStrategy;
import com.fungle.brume.anonymization.strategies.FakeStrategy;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.LinkedColumnsConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /** "table.column" → declared length (null = unbounded) of the columns needing unique fakes (#29a). */
    private volatile Map<String, Integer> uniqueColumns = Map.of();

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
     * Run-scoped preparation (#29a) : records the columns whose FAKE values must not collide —
     * columns unique on their own in the source schema — and every column that must hold the
     * very same value as one of them : both ends of each FK column pair, in both directions, and
     * the members of {@code linked_columns} (#29c). The suffix reaches the whole class, cut to the
     * shortest declared length of the class, so that a FK still finds its parent value. Without
     * this call (unit tests, audit), no uniqueness suffix is applied.
     *
     * @param schema the analyzed source schema
     * @param config the anonymization config of the run
     */
    public void prepare(DatabaseSchema schema, AnonymizationConfig config) {
        Map<String, String> classes = new HashMap<>();
        for (TableMetadata table : schema.tables().values()) {
            for (ForeignKey fk : table.foreignKeys()) {
                for (int i = 0; i < fk.fromColumns().size(); i++) {
                    union(classes, fk.fromTable() + "." + fk.fromColumns().get(i), fk.toTable() + "." + fk.toColumns().get(i));
                }
            }
        }
        if (config != null && config.linkedColumns() != null) {
            for (LinkedColumnsConfig linked : config.linkedColumns()) {
                List<String> members = linked.columns() == null ? List.of()
                        : linked.columns().stream().map(r -> r.table() + "." + r.column()).toList();
                for (int i = 1; i < members.size(); i++) {
                    union(classes, members.getFirst(), members.get(i));
                }
            }
        }
        Set<String> uniqueClasses = new HashSet<>();
        schema.tables().values().forEach(t -> t.uniqueColumns().forEach(c -> uniqueClasses.add(find(classes, t.name() + "." + c))));

        Set<String> members = new HashSet<>(classes.keySet());
        schema.tables().values().forEach(t -> t.uniqueColumns().forEach(c -> members.add(t.name() + "." + c)));
        Map<String, Integer> classLength = new HashMap<>();
        for (String ref : members) {
            Integer length = maxLength(schema, ref);
            if (length != null) classLength.merge(find(classes, ref), length, Math::min);
        }
        Map<String, Integer> lengths = new HashMap<>();
        for (String ref : members) {
            String root = find(classes, ref);
            if (uniqueClasses.contains(root)) lengths.put(ref, classLength.get(root));
        }
        this.uniqueColumns = Collections.unmodifiableMap(lengths);
    }

    private static String find(Map<String, String> classes, String ref) {
        String root = classes.getOrDefault(ref, ref);
        if (root.equals(ref)) return ref;
        root = find(classes, root);
        classes.put(ref, root);
        return root;
    }

    private static void union(Map<String, String> classes, String a, String b) {
        classes.putIfAbsent(a, a);
        classes.putIfAbsent(b, b);
        String rootA = find(classes, a);
        String rootB = find(classes, b);
        if (!rootA.equals(rootB)) classes.put(rootA, rootB);
    }

    private static Integer maxLength(DatabaseSchema schema, String ref) {
        int dot = ref.indexOf('.');
        TableMetadata table = schema.get(ref.substring(0, dot));
        return table == null ? null : table.columns().stream()
                .filter(c -> c.name().equals(ref.substring(dot + 1)))
                .map(ColumnMetadata::maxLength).filter(Objects::nonNull).findFirst().orElse(null);
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

        // Special path (#45b) : FAKE+DATE with explicit per-column min_year/max_year bounds
        // (pediatric apps, student directories, ...). The standard AnonymizationStrategy.anonymize
        // signature doesn't carry ColumnConfig, so we read the bounds here and route through
        // FakeStrategy.anonymizeDate directly. Columns without bounds fall through to the normal
        // path and get the adult-DOB default anchor baked into FakeStrategy.generateWithSeed.
        Object anonymized;
        if (impl instanceof FakeStrategy fs
                && colConfig.type() == SemanticType.DATE
                && (colConfig.minYear() != null || colConfig.maxYear() != null)) {
            anonymized = fs.anonymizeDate(value, semanticKey, substitutionDictionary,
                    colConfig.minYear(), colConfig.maxYear());
        } else {
            anonymized = impl.anonymize(value, colConfig.type(), semanticKey, substitutionDictionary);
        }

        // #29a — a small-vocabulary fake on a UNIQUE column would collide : suffix it.
        String ref = table + "." + column;
        if (impl instanceof FakeStrategy fs && anonymized instanceof String fake && value != null
                && FakeStrategy.SMALL_VOCABULARY.contains(colConfig.type()) && uniqueColumns.containsKey(ref)) {
            anonymized = fs.withUniqueSuffix(fake, value, uniqueColumns.get(ref));
        }

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

