package com.fungle.brume.scan;

import com.fungle.brume.scan.ColumnStrategyMapper.Suggestion;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks a {@link DatabaseSchema} and produces an in-memory, serialisable description
 * of what {@code brume scan} should write to {@code brume.yml}.
 *
 * <p>Does <em>not</em> emit a {@code AnonymizerConfig} directly — that record was
 * built for the loader side and omits the "why did we pick this rule" trace that the
 * scan output needs for commented YAML. The builder returns a dedicated
 * {@link ScanResult} tree carrying the matched pattern per column, which the writer
 * uses to render provenance comments next to each rule (ADR scan-command Q3).
 *
 * <p>Foreign-key columns are deliberately left untouched : at run time
 * {@code FkStrategyPropagator} copies the parent PK's strategy onto them, so emitting
 * a redundant FPE rule here would just duplicate work and risk divergence if the
 * parent column changes.
 */
public final class ScanConfigBuilder {

    /** One rule the scan proposes for a single column, keyed by table.column. */
    public record SuggestedColumn(String columnName, Suggestion suggestion) {
    }

    /** All proposed rules for a table, in the schema's column order. */
    public record SuggestedTable(String tableName, List<SuggestedColumn> columns) {
    }

    /** Full scan output : one entry per table that has at least one suggested rule. */
    public record ScanResult(List<SuggestedTable> tables, int totalSuggestions) {
    }

    private ScanConfigBuilder() {
    }

    /**
     * Builds a {@link ScanResult} for the given database schema.
     *
     * <p>Per-column decision (in order) :
     * <ol>
     *   <li>If the column is a foreign-key column, skip — propagation happens at run time.</li>
     *   <li>If the column is a (single-column) primary key, propose FPE_ID or FPE_UUID
     *       depending on the data type.</li>
     *   <li>Otherwise, look up the column name in {@link ColumnStrategyMapper}.</li>
     * </ol>
     *
     * <p>Tables with no match on any column are omitted from the result entirely —
     * no need to clutter the output with empty {@code columns:} lists.
     *
     * @param schema the source database schema
     * @return a {@link ScanResult} ready for YAML rendering
     */
    public static ScanResult build(DatabaseSchema schema) {
        List<SuggestedTable> suggestedTables = new ArrayList<>();
        int total = 0;

        for (TableMetadata table : schema.tables().values()) {
            Set<String> fkColumns = collectFkColumns(table);
            String singlePk = table.singlePrimaryKeyColumn();
            List<SuggestedColumn> rules = new ArrayList<>();

            for (ColumnMetadata column : table.columns()) {
                if (fkColumns.contains(column.name())) continue;

                if (column.name().equals(singlePk)) {
                    rules.add(new SuggestedColumn(column.name(),
                            ColumnStrategyMapper.suggestForPrimaryKey(column.dataType())));
                    continue;
                }

                Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(column.name(), column.dataType());
                hit.ifPresent(s -> rules.add(new SuggestedColumn(column.name(), s)));
            }

            if (!rules.isEmpty()) {
                suggestedTables.add(new SuggestedTable(table.name(), rules));
                total += rules.size();
            }
        }

        return new ScanResult(List.copyOf(suggestedTables), total);
    }

    private static Set<String> collectFkColumns(TableMetadata table) {
        Map<String, Boolean> set = new LinkedHashMap<>();
        if (table.foreignKeys() != null) {
            table.foreignKeys().forEach(fk -> fk.fromColumns().forEach(c -> set.put(c, Boolean.TRUE)));
        }
        return set.keySet();
    }
}
