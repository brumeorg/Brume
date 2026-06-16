package com.fungle.brume.anonymization;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.ColumnReference;
import com.fungle.brume.config.model.LinkedColumnsConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the semantic key for a given table and column name.
 *
 * <p>Resolution rules (in order):
 * <ol>
 *   <li>If the column is listed in a {@code linked_columns} entry, return that entry's
 *       {@code semanticKey}.</li>
 *   <li>Otherwise, fall back to {@code "table.column"}.</li>
 * </ol>
 */
@Component
public class SemanticKeyResolver {

    /**
     * Resolves the semantic key for a given table+column pair.
     *
     * @param table  the table name
     * @param column the column name
     * @param config the anonymization configuration containing the {@code linkedColumns} list
     * @return the semantic key — never {@code null}
     */
    public String resolve(String table, String column, AnonymizationConfig config) {
        List<LinkedColumnsConfig> linkedColumns = config.linkedColumns();
        if (linkedColumns != null) {
            for (LinkedColumnsConfig linked : linkedColumns) {
                for (ColumnReference ref : linked.columns()) {
                    if (ref.table().equals(table) && ref.column().equals(column)) {
                        return linked.semanticKey();
                    }
                }
            }
        }
        return table + "." + column;
    }
}

