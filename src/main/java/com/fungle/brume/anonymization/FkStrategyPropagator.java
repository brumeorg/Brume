package com.fungle.brume.anonymization;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Propagates anonymization strategies across equivalence classes of columns linked by
 * foreign keys, so that referential integrity is preserved in the target database
 * regardless of which side of a FK the user declared the strategy on.
 *
 * <p>Run after {@link com.fungle.brume.schema.SchemaAnalyzer} has produced the
 * {@link DatabaseSchema} and after the user-supplied {@link AnonymizerConfig} has passed
 * {@link com.fungle.brume.config.ConfigValidator#validate(AnonymizerConfig)}. The output is a new
 * (immutable) {@code AnonymizerConfig} where every column of an equivalence class inherits the
 * single strategy declared anywhere in that class.
 *
 * <p><strong>Model.</strong> Every FK pair {@code (child_col, parent_col)} unions the two columns
 * into the same equivalence class (union-find on (table, column) keys). Composite FKs are
 * position-wise — each aligned pair is unioned independently, so a 2-column FK produces 2
 * classes, never 1. The class of a non-FK column is itself (singleton).
 *
 * <p><strong>Propagation (Q1 — supersedes ADR-0023's unidirectional PK → FK).</strong> Declaring
 * the strategy on <em>any</em> column of a class — PK side, FK side, or both — marks the class.
 * Every other column of the class that has no explicit user rule inherits this strategy. This
 * matches the user's mental model on composite junctions where the PK components are themselves
 * FKs and no "root" PK exists.
 *
 * <p><strong>Eligible strategies for automatic propagation</strong> (deterministic, value-only —
 * the same value gives the same output in every column):
 * <ul>
 *   <li>{@link Strategy#FPE_ID}</li>
 *   <li>{@link Strategy#FPE_UUID}</li>
 *   <li>{@link Strategy#HASH}</li>
 *   <li>{@link Strategy#FAKE} (ADR-0057) — the fake is drawn from the value, the type and the
 *       secret only, so no {@code linked_columns} entry is needed along a SQL foreign key ;
 *       {@code linked_columns} remains for columns that share values without a declared FK.
 *       The propagated rule carries the type and the {@code min_year} / {@code max_year} bounds.</li>
 * </ul>
 *
 * <p><strong>Conflict (Q2 — fail-fast).</strong> If two columns of the same class are declared
 * with two different non-{@link Strategy#NULLIFY} strategies, the configuration is rejected
 * at boot. The user must pick one or remove the conflicting declaration.
 *
 * <p><strong>NULLIFY (Q3 — per-column opt-out).</strong> A column declared {@link Strategy#NULLIFY}
 * does <em>not</em> participate in the class's effective strategy: it becomes {@code NULL} in
 * target. The rest of the class still receives the inherited strategy. This preserves the
 * self-FK opt-out pattern (e.g. {@code users.manager_id = NULLIFY} while {@code users.id =
 * FPE_ID}). However:
 * <ul>
 *   <li>{@code NULLIFY}/{@code MASK} on a column referenced by a FK that is <em>not</em> also
 *       opted out → rejected (would orphan child rows or produce collisions in target).</li>
 *   <li>{@code FAKE} declared on several columns of a class with a different type or different
 *       {@code min_year} / {@code max_year} bounds → rejected as a conflict (the values would
 *       diverge).</li>
 * </ul>
 *
 * <p>See ADR-0044 for the full decision record and ADR-0023 § Q1 for the unidirectional
 * predecessor.
 */
@Component
public class FkStrategyPropagator {

    private static final Logger log = LoggerFactory.getLogger(FkStrategyPropagator.class);

    private static final Set<Strategy> DETERMINISTIC_STRATEGIES =
            EnumSet.of(Strategy.FPE_ID, Strategy.FPE_UUID, Strategy.HASH);

    private static final Set<Strategy> INTEGRITY_BREAKING_STRATEGIES =
            EnumSet.of(Strategy.MASK, Strategy.NULLIFY);

    public AnonymizerConfig propagate(AnonymizerConfig config, DatabaseSchema schema) {
        AnonymizationConfig anonymization = config.anonymization();
        Map<ColumnKey, ColumnConfig> declared = indexRules(anonymization);

        // ADR-0023 direction-specific guard preserved: NULLIFY/MASK on the referenced PK side
        // breaks integrity unless the referencing FK side is also opted out (NULLIFY). This
        // rule is per-FK-direction and complements the direction-agnostic class propagation.
        validatePerPairIntegrity(schema, declared);

        // Build equivalence classes from the FK graph. Each FK position is one union.
        UnionFind<ColumnKey> classes = new UnionFind<>();
        for (TableMetadata table : schema.tables().values()) {
            for (ForeignKey fk : table.foreignKeys()) {
                List<String> fromCols = fk.fromColumns();
                List<String> toCols = fk.toColumns();
                for (int i = 0; i < fromCols.size(); i++) {
                    classes.union(
                            new ColumnKey(fk.fromTable(), fromCols.get(i)),
                            new ColumnKey(fk.toTable(), toCols.get(i)));
                }
            }
        }

        // Group declared rules by their class root. NULLIFY rules are per-column opt-outs
        // validated above by the per-FK guard, not class-driving (Q3). KEEP stays in the
        // active set so an explicit "keep this FK as source" while its sibling is anonymized
        // is detected as a conflict (Q2).
        Map<ColumnKey, List<ColumnKey>> activeByClass = new HashMap<>();
        for (Map.Entry<ColumnKey, ColumnConfig> entry : declared.entrySet()) {
            ColumnKey col = entry.getKey();
            if (entry.getValue().strategy() != Strategy.NULLIFY) {
                activeByClass.computeIfAbsent(classes.find(col), k -> new ArrayList<>()).add(col);
            }
        }

        // Resolve each class: detect conflicts, validate guards, compute propagation targets.
        Map<ColumnKey, ColumnConfig> propagationByColumn = new LinkedHashMap<>();
        for (Map.Entry<ColumnKey, List<ColumnKey>> entry : activeByClass.entrySet()) {
            ColumnKey root = entry.getKey();
            List<ColumnKey> activeColumns = entry.getValue();
            ColumnConfig classRule = resolveClassRule(activeColumns, declared);
            Strategy classStrategy = classRule.strategy();
            if (classStrategy == Strategy.KEEP) {
                // Class is explicitly KEEP — no propagation, no integrity issue (nothing
                // is being anonymized). Skip.
                continue;
            }
            List<ColumnKey> classMembers = classes.membersOf(root);

            if (!DETERMINISTIC_STRATEGIES.contains(classStrategy)
                    && classStrategy != Strategy.FAKE) {
                // MASK / any strategy outside the propagation allowlist applies to the
                // declared column only — direction-specific integrity already validated above.
                continue;
            }
            for (ColumnKey member : classMembers) {
                if (declared.containsKey(member)) continue;
                ColumnConfig propagated = new ColumnConfig(member.column(), classStrategy,
                        classRule.type(), classRule.jsonPaths(), classRule.minYear(), classRule.maxYear());
                propagationByColumn.put(member, propagated);
            }
        }

        if (propagationByColumn.isEmpty()) {
            log.debug("FK propagation: no rules added (no classes have an active strategy)");
            return config;
        }
        for (Map.Entry<ColumnKey, ColumnConfig> entry : propagationByColumn.entrySet()) {
            log.info("[FK propagation] {} ← class strategy {}",
                    entry.getKey(), entry.getValue().strategy());
        }
        log.info("FK propagation: {} rule(s) inherited from equivalence class anchors",
                propagationByColumn.size());
        return rebuild(config, declared, propagationByColumn);
    }

    private void validatePerPairIntegrity(DatabaseSchema schema,
                                          Map<ColumnKey, ColumnConfig> declared) {
        for (TableMetadata table : schema.tables().values()) {
            for (ForeignKey fk : table.foreignKeys()) {
                List<String> fromCols = fk.fromColumns();
                List<String> toCols = fk.toColumns();
                for (int i = 0; i < fromCols.size(); i++) {
                    ColumnKey from = new ColumnKey(fk.fromTable(), fromCols.get(i));
                    ColumnKey to = new ColumnKey(fk.toTable(), toCols.get(i));
                    ColumnConfig toRule = declared.get(to);
                    if (toRule == null
                            || !INTEGRITY_BREAKING_STRATEGIES.contains(toRule.strategy())) {
                        continue;
                    }
                    ColumnConfig fromRule = declared.get(from);
                    if (fromRule != null && fromRule.strategy() == Strategy.NULLIFY) {
                        continue; // explicit opt-out on the referencing side
                    }
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_FK_PROPAGATION_INTEGRITY_BREAKING,
                            "Primary key '" + to + "' uses strategy " + toRule.strategy()
                                    + " but is referenced by FK '" + from + "'",
                            toRule.strategy() + " on a referenced PK breaks referential "
                                    + "integrity (produces nulls or collisions in the target). "
                                    + "Switch the PK rule to FPE_ID / FPE_UUID / HASH (which "
                                    + "auto-propagate to FKs), or to KEEP (no anonymization), "
                                    + "or declare the FK NULLIFY to drop the link explicitly.");
                }
            }
        }
    }

    private ColumnConfig resolveClassRule(List<ColumnKey> activeColumns,
                                          Map<ColumnKey, ColumnConfig> declared) {
        // Q2 fail-fast: all non-NULLIFY declarations in a class must share the same strategy
        // (and, for FAKE, the same semantic type — otherwise the dictionary returns divergent
        // fake values across columns of the same logical id).
        ColumnConfig anchor = declared.get(activeColumns.get(0));
        for (int i = 1; i < activeColumns.size(); i++) {
            ColumnConfig other = declared.get(activeColumns.get(i));
            if (other.strategy() != anchor.strategy()
                    || (anchor.strategy() == Strategy.FAKE && (other.type() != anchor.type()
                            || !Objects.equals(other.minYear(), anchor.minYear())
                            || !Objects.equals(other.maxYear(), anchor.maxYear())))) {
                Set<String> sorted = new TreeSet<>();
                for (ColumnKey col : activeColumns) {
                    ColumnConfig rule = declared.get(col);
                    sorted.add(col + "=" + rule.strategy()
                            + (rule.type() != null ? "(" + rule.type() + ")" : "")
                            + (rule.minYear() != null || rule.maxYear() != null
                                    ? "[" + rule.minYear() + ".." + rule.maxYear() + "]" : ""));
                }
                throw new ConfigurationException(
                        BrumeErrorCode.CONFIG_FK_PROPAGATION_STRATEGY_CONFLICT,
                        "Foreign-key equivalence class has conflicting strategies: " + sorted,
                        "All columns linked by foreign keys form an equivalence class that "
                                + "shares the same logical value. They must either share one "
                                + "anonymization strategy (and same type for FAKE) or have all "
                                + "but one column removed from the config (the propagator will "
                                + "fill the rest in). Pick the strategy you want and drop the "
                                + "conflicting declaration(s).");
            }
        }
        return anchor;
    }

    // -------------------------------------------------------------------------
    // Indexing helpers
    // -------------------------------------------------------------------------

    private Map<ColumnKey, ColumnConfig> indexRules(AnonymizationConfig anonymization) {
        Map<ColumnKey, ColumnConfig> index = new LinkedHashMap<>();
        for (TableAnonymizationConfig table : anonymization.tables()) {
            if (table.columns() == null) continue;
            for (ColumnConfig col : table.columns()) {
                index.put(new ColumnKey(table.table(), col.name()), col);
            }
        }
        return index;
    }

    private AnonymizerConfig rebuild(AnonymizerConfig original,
                                     Map<ColumnKey, ColumnConfig> declared,
                                     Map<ColumnKey, ColumnConfig> propagated) {
        AnonymizationConfig oldAnon = original.anonymization();

        // Merge per-table: declared rules in their original order, then any propagated rule
        // for a column not yet present in the table.
        Map<String, LinkedHashMap<String, ColumnConfig>> byTable = new LinkedHashMap<>();
        for (TableAnonymizationConfig declaredTable : oldAnon.tables()) {
            LinkedHashMap<String, ColumnConfig> cols = new LinkedHashMap<>();
            if (declaredTable.columns() != null) {
                for (ColumnConfig c : declaredTable.columns()) {
                    cols.put(c.name(), c);
                }
            }
            byTable.put(declaredTable.table(), cols);
        }
        for (Map.Entry<ColumnKey, ColumnConfig> entry : propagated.entrySet()) {
            byTable
                    .computeIfAbsent(entry.getKey().table(), k -> new LinkedHashMap<>())
                    .putIfAbsent(entry.getKey().column(), entry.getValue());
        }

        List<TableAnonymizationConfig> newTables = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, ColumnConfig>> entry : byTable.entrySet()) {
            newTables.add(new TableAnonymizationConfig(
                    entry.getKey(), new ArrayList<>(entry.getValue().values())));
        }

        AnonymizationConfig newAnon = new AnonymizationConfig(oldAnon.linkedColumns(), newTables);
        return new AnonymizerConfig(original.extraction(), newAnon);
    }

    /** (table, column) key used throughout the union-find and indexing logic. */
    private record ColumnKey(String table, String column) implements Comparable<ColumnKey> {
        @Override
        public String toString() {
            return table + "." + column;
        }
        @Override
        public int compareTo(ColumnKey other) {
            return Comparator.comparing(ColumnKey::table)
                    .thenComparing(ColumnKey::column)
                    .compare(this, other);
        }
    }

    /**
     * Minimal union-find with path compression. {@link #find(Object)} on an unknown element
     * inserts it as a singleton class. {@link #membersOf(Object)} enumerates the class of a
     * root by linear scan over inserted elements — fine for our O(|FKs|) sizes.
     */
    private static final class UnionFind<T> {
        private final Map<T, T> parent = new HashMap<>();

        T find(T x) {
            T p = parent.putIfAbsent(x, x);
            if (p == null || p.equals(x)) return x;
            T root = find(p);
            parent.put(x, root);
            return root;
        }

        void union(T a, T b) {
            T ra = find(a);
            T rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }

        List<T> membersOf(T root) {
            T r = find(root);
            List<T> members = new ArrayList<>();
            for (T candidate : parent.keySet()) {
                if (find(candidate).equals(r)) {
                    members.add(candidate);
                }
            }
            return members;
        }
    }
}
