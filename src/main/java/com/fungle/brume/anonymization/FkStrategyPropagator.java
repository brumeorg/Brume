package com.fungle.brume.anonymization;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ColumnReference;
import com.fungle.brume.config.model.LinkedColumnsConfig;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Propagates anonymization strategies from primary keys to the foreign keys that reference them,
 * so that referential integrity is preserved in the target database without requiring users to
 * declare every FK column explicitly.
 *
 * <p>Run after {@link com.fungle.brume.schema.SchemaAnalyzer} has produced the
 * {@link DatabaseSchema} and after the user-supplied {@link AnonymizerConfig} has passed
 * {@link com.fungle.brume.config.ConfigValidator#validate(AnonymizerConfig)}. The output is a new
 * (immutable) {@code AnonymizerConfig} where FK columns inherit the strategy of their parent PK
 * when not declared explicitly by the user.
 *
 * <p>Eligible strategies for automatic propagation (deterministic, value-only — no dictionary
 * required for cross-table consistency):
 * <ul>
 *   <li>{@link Strategy#FPE_ID}</li>
 *   <li>{@link Strategy#FPE_UUID}</li>
 *   <li>{@link Strategy#HASH}</li>
 * </ul>
 *
 * <p>Other strategies require explicit handling and trigger fail-fast errors when applied to a
 * PK that is referenced by at least one FK:
 * <ul>
 *   <li>{@link Strategy#NULLIFY}, {@link Strategy#MASK} — lossy or null-producing, would break FK
 *       integrity. Rejected.</li>
 *   <li>{@link Strategy#FAKE} — cross-table consistency requires a shared semantic key declared
 *       via {@code linked_columns}. If absent for any FK, rejected with a remediation hint.</li>
 *   <li>{@link Strategy#KEEP} or absent — no transformation on the PK, FK keeps its source
 *       value unchanged. Nothing to do.</li>
 * </ul>
 *
 * <p>User-declared FK rules always take precedence over auto-propagation. If the user declares
 * an FK with a strategy that would break integrity (e.g. {@code KEEP} while the PK is anonymized
 * with {@code FPE_ID}), the propagator rejects the configuration at boot.
 *
 * <p>Composite primary keys and composite foreign keys are not handled — Brume's
 * {@link com.fungle.brume.schema.SchemaAnalyzer} ignores them upstream. This propagator only
 * sees single-column FKs, which is the only shape it needs to handle.
 */
@Component
public class FkStrategyPropagator {

    private static final Logger log = LoggerFactory.getLogger(FkStrategyPropagator.class);

    /**
     * Strategies whose output is a pure deterministic function of the input value (plus a
     * project-wide secret). Same input + same secret → same output, regardless of which table
     * or column the value sits in. These are safe to propagate automatically from PK to FK.
     */
    private static final Set<Strategy> DETERMINISTIC_STRATEGIES =
            EnumSet.of(Strategy.FPE_ID, Strategy.FPE_UUID, Strategy.HASH);

    /**
     * Strategies that always break FK integrity when applied to a referenced PK. {@code MASK}
     * is lossy (collisions possible), {@code NULLIFY} produces {@code null} and would either
     * violate {@code NOT NULL} on the FK side or orphan all child rows.
     */
    private static final Set<Strategy> INTEGRITY_BREAKING_STRATEGIES =
            EnumSet.of(Strategy.MASK, Strategy.NULLIFY);

    /**
     * Returns a new {@link AnonymizerConfig} where FK columns referencing a PK with a
     * deterministic anonymization strategy have inherited that strategy, unless the user
     * already declared a rule for them.
     *
     * @param config the user-supplied configuration (already syntactically validated)
     * @param schema the source database schema (PKs and single-column FKs)
     * @return a new {@code AnonymizerConfig} enriched with the propagated FK rules
     * @throws ConfigurationException if the configuration is incompatible with the schema —
     *                                e.g. a referenced PK uses {@code NULLIFY}/{@code MASK},
     *                                a referenced PK uses {@code FAKE} without a covering
     *                                {@code linked_columns}, or an FK is declared explicitly
     *                                with a strategy that conflicts with its parent PK.
     */
    public AnonymizerConfig propagate(AnonymizerConfig config, DatabaseSchema schema) {
        AnonymizationConfig anonymization = config.anonymization();
        Map<String, Map<String, ColumnConfig>> rulesIndex = indexRules(anonymization);
        Set<SemanticKeyPair> linkedPairs = indexLinkedColumns(anonymization);

        // Mutable structure we'll mutate while walking the FK graph; only "promoted" once at the
        // end into a fresh AnonymizerConfig (records are immutable).
        Map<String, Map<String, ColumnConfig>> enriched = deepCopy(rulesIndex);
        int propagatedCount = 0;

        for (TableMetadata table : schema.tables().values()) {
            List<ForeignKey> fks = table.foreignKeys();
            if (fks == null || fks.isEmpty()) continue;

            for (ForeignKey fk : fks) {
                ColumnConfig pkRule = lookup(rulesIndex, fk.toTable(), fk.toColumn());
                if (pkRule == null || pkRule.strategy() == Strategy.KEEP) {
                    // PK not anonymized → FK keeps its source value, nothing to do.
                    continue;
                }

                Strategy pkStrategy = pkRule.strategy();
                String pkRef = fk.toTable() + "." + fk.toColumn();
                String fkRef = fk.fromTable() + "." + fk.fromColumn();

                if (INTEGRITY_BREAKING_STRATEGIES.contains(pkStrategy)) {
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_FK_PROPAGATION_INTEGRITY_BREAKING,
                            "Primary key '" + pkRef + "' uses strategy " + pkStrategy
                                    + " but is referenced by FK '" + fkRef + "'",
                            pkStrategy + " on a referenced PK breaks referential integrity "
                                    + "(produces nulls or collisions in the target). Switch the "
                                    + "PK rule to FPE_ID / FPE_UUID / HASH (which auto-propagate "
                                    + "to FKs), or to KEEP (no anonymization), or drop the FK "
                                    + "from the source schema before running Brume.");
                }

                if (pkStrategy == Strategy.FAKE) {
                    if (!linkedPairs.contains(new SemanticKeyPair(fk.toTable(), fk.toColumn(),
                            fk.fromTable(), fk.fromColumn()))) {
                        throw new ConfigurationException(
                                BrumeErrorCode.CONFIG_FK_PROPAGATION_FAKE_REQUIRES_LINK,
                                "Primary key '" + pkRef + "' uses strategy FAKE but the FK '"
                                        + fkRef + "' is not covered by a 'linked_columns' entry",
                                "FAKE strategies require a shared semantic_key to keep the FK "
                                        + "consistent with its parent (otherwise the substitution "
                                        + "dictionary returns a different fake value for the FK and "
                                        + "the child row is orphaned). Either declare both columns "
                                        + "under the same 'linked_columns' entry in config.yaml, or "
                                        + "switch the PK to FPE_ID / FPE_UUID / HASH for automatic "
                                        + "propagation without linked_columns.");
                    }
                    continue;
                }

                // pkStrategy ∈ {FPE_ID, FPE_UUID, HASH} — eligible for auto-propagation.
                ColumnConfig fkRule = lookup(rulesIndex, fk.fromTable(), fk.fromColumn());
                if (fkRule == null) {
                    ColumnConfig propagated = new ColumnConfig(
                            fk.fromColumn(), pkStrategy, pkRule.type(), null);
                    enriched.computeIfAbsent(fk.fromTable(), k -> new LinkedHashMap<>())
                            .put(fk.fromColumn(), propagated);
                    propagatedCount++;
                    log.info("[FK propagation] {} ← {} ({})", fkRef, pkRef, pkStrategy);
                    continue;
                }

                if (fkRule.strategy() == Strategy.NULLIFY) {
                    continue;
                }
                if (fkRule.strategy() != pkStrategy) {
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_FK_PROPAGATION_STRATEGY_CONFLICT,
                            "Foreign key '" + fkRef + "' is declared with strategy "
                                    + fkRule.strategy() + " but its parent PK '" + pkRef
                                    + "' uses " + pkStrategy,
                            "An FK declared explicitly must either match the PK strategy ("
                                    + pkStrategy + ") to stay consistent with its parent, or use "
                                    + "NULLIFY to opt out of the link explicitly. Removing the FK "
                                    + "declaration from config.yaml would auto-propagate "
                                    + pkStrategy + " (the default for this case).");
                }
                // fkRule already matches pkRule — user declared it explicitly, nothing to do.
            }
        }

        if (propagatedCount == 0) {
            log.debug("FK propagation: no rules added (all FKs already covered or PKs not anonymized)");
            return config;
        }
        log.info("FK propagation: {} rule(s) inherited from parent PKs", propagatedCount);
        return rebuild(config, enriched);
    }

    // -------------------------------------------------------------------------
    // Indexing helpers
    // -------------------------------------------------------------------------

    private Map<String, Map<String, ColumnConfig>> indexRules(AnonymizationConfig anonymization) {
        Map<String, Map<String, ColumnConfig>> index = new HashMap<>();
        for (TableAnonymizationConfig table : anonymization.tables()) {
            if (table.columns() == null) continue;
            Map<String, ColumnConfig> byColumn = new LinkedHashMap<>();
            for (ColumnConfig col : table.columns()) {
                byColumn.put(col.name(), col);
            }
            index.put(table.table(), byColumn);
        }
        return index;
    }

    private Set<SemanticKeyPair> indexLinkedColumns(AnonymizationConfig anonymization) {
        Set<SemanticKeyPair> pairs = new HashSet<>();
        for (LinkedColumnsConfig linked : anonymization.linkedColumns()) {
            List<ColumnReference> refs = linked.columns();
            if (refs == null || refs.size() < 2) continue;
            for (ColumnReference a : refs) {
                for (ColumnReference b : refs) {
                    if (a == b) continue;
                    pairs.add(new SemanticKeyPair(a.table(), a.column(), b.table(), b.column()));
                }
            }
        }
        return pairs;
    }

    private ColumnConfig lookup(Map<String, Map<String, ColumnConfig>> index,
                                 String table, String column) {
        Map<String, ColumnConfig> byColumn = index.get(table);
        return byColumn == null ? null : byColumn.get(column);
    }

    private Map<String, Map<String, ColumnConfig>> deepCopy(Map<String, Map<String, ColumnConfig>> src) {
        Map<String, Map<String, ColumnConfig>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ColumnConfig>> e : src.entrySet()) {
            copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return copy;
    }

    private AnonymizerConfig rebuild(AnonymizerConfig original,
                                     Map<String, Map<String, ColumnConfig>> enriched) {
        AnonymizationConfig oldAnon = original.anonymization();

        List<TableAnonymizationConfig> newTables = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (TableAnonymizationConfig declared : oldAnon.tables()) {
            Map<String, ColumnConfig> byColumn = enriched.getOrDefault(declared.table(), new LinkedHashMap<>());
            newTables.add(new TableAnonymizationConfig(declared.table(), new ArrayList<>(byColumn.values())));
            emitted.add(declared.table());
        }
        for (Map.Entry<String, Map<String, ColumnConfig>> e : enriched.entrySet()) {
            if (emitted.contains(e.getKey())) continue;
            newTables.add(new TableAnonymizationConfig(e.getKey(), new ArrayList<>(e.getValue().values())));
        }

        AnonymizationConfig newAnon = new AnonymizationConfig(oldAnon.linkedColumns(), newTables);
        return new AnonymizerConfig(original.extraction(), newAnon);
    }

    /**
     * Symmetric pair of (table, column) endpoints used to test whether a given FK is covered
     * by a {@code linked_columns} entry. Two pairs are equal regardless of endpoint order.
     */
    private record SemanticKeyPair(String aTable, String aColumn, String bTable, String bColumn) {
    }
}
