package com.fungle.brume.anonymization;

import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Augments an {@link AnonymizerConfig} by propagating PK anonymization strategies to all
 * referencing FK columns.
 *
 * <p>When a primary key column is anonymized with a deterministic, format-preserving strategy
 * ({@link Strategy#FPE_ID} / {@link Strategy#FPE_UUID}), every FK column referencing it must
 * apply the <em>same</em> strategy — otherwise the FK reference is broken in the dump:
 * the parent row carries the encrypted PK while the child row keeps the original FK value.
 *
 * <p>This was historically enforced silently in JDBC mode by
 * {@code session_replication_role = 'replica'} (FK validation bypassed on insert). With the
 * new dump format ({@code pg_dump --section=post-data} re-applies FK constraints after data
 * load), the inconsistency now surfaces as a hard FK violation when the constraint is added.
 *
 * <p>The resolver walks every table in the schema, and for each outgoing FK
 * {@code (fromTable, fromColumn) → (toTable, toColumn)}:
 * <ol>
 *   <li>looks up the existing rule for {@code toTable.toColumn}</li>
 *   <li>if its strategy is {@code FPE_ID} or {@code FPE_UUID}</li>
 *   <li>and there is <em>no</em> explicit rule already declared for
 *       {@code fromTable.fromColumn}</li>
 *   <li>then a synthetic rule with the same strategy is added on the FK column</li>
 * </ol>
 *
 * <p>Explicit rules in the user's config always win — propagation only fills <em>gaps</em>.
 * Strategies other than the format-preserving ones are not propagated, since they would
 * almost certainly break referential integrity.
 */
@Component
public class FkPropagationResolver {

    private static final Logger log = LoggerFactory.getLogger(FkPropagationResolver.class);

    private static final Set<Strategy> PROPAGATABLE = Set.of(Strategy.FPE_ID, Strategy.FPE_UUID);

    /**
     * Returns a new {@link AnonymizerConfig} with FK propagation applied. The input config
     * is never mutated.
     *
     * @param config the user-supplied anonymization config
     * @param schema the analyzed source schema providing FK metadata
     * @return an augmented config; identical to the input when no propagation is needed
     */
    public AnonymizerConfig propagate(AnonymizerConfig config, DatabaseSchema schema) {
        if (config == null || config.anonymization() == null || schema == null) {
            return config;
        }

        AnonymizationConfig anon = config.anonymization();
        Map<String, Map<String, ColumnConfig>> rulesByTable = indexExistingRules(anon);

        // Mutable accumulator of new rules to add: table -> (column -> ColumnConfig)
        Map<String, Map<String, ColumnConfig>> additions = new LinkedHashMap<>();
        int propagatedCount = 0;

        for (String tableName : schema.tableNames()) {
            TableMetadata meta = schema.get(tableName);
            if (meta == null || meta.foreignKeys() == null) continue;

            for (ForeignKey fk : meta.foreignKeys()) {
                ColumnConfig parentRule = lookupRule(rulesByTable, fk.toTable(), fk.toColumn());
                if (parentRule == null || !PROPAGATABLE.contains(parentRule.strategy())) {
                    continue;
                }
                // Skip if the user already declared a rule for the FK column
                if (lookupRule(rulesByTable, fk.fromTable(), fk.fromColumn()) != null) {
                    continue;
                }
                // Skip if we already queued the same propagation in this pass
                if (additions.getOrDefault(fk.fromTable(), Map.of()).containsKey(fk.fromColumn())) {
                    continue;
                }

                ColumnConfig propagated = new ColumnConfig(
                        fk.fromColumn(), parentRule.strategy(), parentRule.type(), null);
                additions.computeIfAbsent(fk.fromTable(), _ -> new LinkedHashMap<>())
                        .put(fk.fromColumn(), propagated);
                propagatedCount++;
                log.info("FK propagation: {}.{} → {} (referencing {}.{} with strategy {})",
                        fk.fromTable(), fk.fromColumn(), parentRule.strategy(),
                        fk.toTable(), fk.toColumn(), parentRule.strategy());
            }
        }

        if (additions.isEmpty()) {
            log.debug("FK propagation: no FPE_ID/FPE_UUID PKs found — config unchanged");
            return config;
        }

        log.info("FK propagation: added {} synthetic rule(s) on FK columns referencing "
                + "FPE_ID/FPE_UUID primary keys.", propagatedCount);

        return new AnonymizerConfig(
                config.extraction(),
                buildAugmentedAnonymizationConfig(anon, additions));
    }

    private Map<String, Map<String, ColumnConfig>> indexExistingRules(AnonymizationConfig anon) {
        Map<String, Map<String, ColumnConfig>> idx = new HashMap<>();
        if (anon.tables() == null) return idx;
        for (TableAnonymizationConfig tableConfig : anon.tables()) {
            Map<String, ColumnConfig> byColumn = new HashMap<>();
            if (tableConfig.columns() != null) {
                for (ColumnConfig c : tableConfig.columns()) {
                    byColumn.put(c.name(), c);
                }
            }
            idx.put(tableConfig.table(), byColumn);
        }
        return idx;
    }

    private ColumnConfig lookupRule(Map<String, Map<String, ColumnConfig>> idx,
                                    String table, String column) {
        Map<String, ColumnConfig> byColumn = idx.get(table);
        return byColumn == null ? null : byColumn.get(column);
    }

    private AnonymizationConfig buildAugmentedAnonymizationConfig(
            AnonymizationConfig original,
            Map<String, Map<String, ColumnConfig>> additions) {

        Set<String> handledTables = new HashSet<>();
        List<TableAnonymizationConfig> mergedTables = new ArrayList<>();

        // Walk existing tables in their original order, augmenting each with new FK rules
        for (TableAnonymizationConfig tableConfig : original.tables()) {
            handledTables.add(tableConfig.table());
            Map<String, ColumnConfig> additionsForTable = additions.get(tableConfig.table());
            if (additionsForTable == null || additionsForTable.isEmpty()) {
                mergedTables.add(tableConfig);
                continue;
            }
            List<ColumnConfig> columns = new ArrayList<>(
                    tableConfig.columns() != null ? tableConfig.columns() : List.of());
            columns.addAll(additionsForTable.values());
            mergedTables.add(new TableAnonymizationConfig(tableConfig.table(), columns));
        }

        // Tables that have no user rules at all but receive propagated FK rules
        for (Map.Entry<String, Map<String, ColumnConfig>> entry : additions.entrySet()) {
            if (handledTables.contains(entry.getKey())) continue;
            mergedTables.add(new TableAnonymizationConfig(
                    entry.getKey(), new ArrayList<>(entry.getValue().values())));
        }

        return new AnonymizationConfig(original.linkedColumns(), mergedTables);
    }
}
