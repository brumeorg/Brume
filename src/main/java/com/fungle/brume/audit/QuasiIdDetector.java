package com.fungle.brume.audit;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.BrumeProperties.AuditProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.report.QuasiIdWarning;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects database columns whose name matches a quasi-identifier heuristic and whose
 * effective anonymization strategy does not break correlation (KEEP, HASH, FPE_ID,
 * FPE_UUID by default).
 *
 * <p>Sibling to {@link com.fungle.brume.plan.PiiDetector}. The two cover distinct
 * failure modes with deliberately non-overlapping outputs:
 * <ul>
 *   <li><strong>PiiDetector</strong> — column has no rule, name matches a PII pattern,
 *       data type is text-like. Output: {@code [WARN PII]}.</li>
 *   <li><strong>QuasiIdDetector</strong> — column matches a quasi-id pattern (any type)
 *       AND either (a) has an explicit rule that does not neutralize correlation
 *       (KEEP/HASH/FPE_*), or (b) has no rule but its type is non-text (so {@code
 *       PiiDetector} would miss it). Output: {@code [QUASI-ID]}.</li>
 * </ul>
 *
 * <p>Rationale for the strategy split: HMAC-deterministic strategies (HASH, FPE_ID,
 * FPE_UUID) preserve the property "same source value → same output", which is exactly
 * the property a re-identification attack exploits when crossing the dataset with a
 * known external record. Randomized or destructive strategies (FAKE seeded per-row,
 * NULLIFY, MASK) break that property and so neutralize the quasi-id risk.
 *
 * <p>Run by {@code ReplicationAgent} AFTER {@code FkStrategyPropagator.propagate()},
 * so the strategies inspected are the final ones (user-declared + auto-propagated).
 *
 * <p>Tracked under #21c / ADR-0035.
 */
@Component
public class QuasiIdDetector {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(QuasiIdDetector.class);

    /**
     * Substrings that identify a text-like PostgreSQL data type — reused from
     * {@link com.fungle.brume.plan.PiiDetector} semantics so the two detectors
     * agree on what {@code PiiDetector} already covers.
     */
    private static final Set<String> TEXT_TYPE_FRAGMENTS = Set.of(
            "char", "text", "varchar", "name", "citext", "bpchar"
    );

    private final BrumeProperties properties;

    /**
     * Constructs a detector bound to the runtime audit configuration.
     *
     * @param properties typed Brume properties; {@link AuditProperties} drives the
     *                   pattern list, neutralizing-strategy set, and enable flag
     */
    public QuasiIdDetector(BrumeProperties properties) {
        this.properties = properties;
    }

    /**
     * Detects quasi-identifier columns in the given schema.
     *
     * <p>When {@code brume.audit.quasi-id-enabled=false}, returns an empty list without
     * inspecting any column.
     *
     * @param schema     the source database schema (all tables and columns)
     * @param config     the anonymization config <strong>after</strong> FK strategy
     *                   propagation — strategies inspected are the effective ones
     * @param schemaName the PostgreSQL schema name (unused today, kept for API
     *                   symmetry with {@link com.fungle.brume.plan.PiiDetector})
     * @return list of quasi-id warnings, one per matching column; never {@code null}
     */
    public List<QuasiIdWarning> detect(DatabaseSchema schema, AnonymizerConfig config,
                                       String schemaName) {
        AuditProperties audit = properties.audit();
        if (!audit.quasiIdEnabled()) {
            return List.of();
        }
        List<String> patterns = audit.quasiIdPatterns();
        Set<Strategy> neutralizing = audit.neutralizingStrategies();

        Map<String, Strategy> ruleIndex = buildRuleIndex(config);
        List<QuasiIdWarning> warnings = new ArrayList<>();

        for (TableMetadata tableMeta : schema.tables().values()) {
            String tableName = tableMeta.name();
            if (tableMeta.columns() == null) continue;

            for (ColumnMetadata col : tableMeta.columns()) {
                String columnName = col.name();
                String dataType = col.dataType();

                String matchedPattern = findMatchingPattern(
                        columnName.toLowerCase(Locale.ROOT), patterns);
                if (matchedPattern == null) continue;

                Strategy effective = ruleIndex.get(tableName + "." + columnName);

                if (effective == null) {
                    // No explicit rule = KEEP implicit. PiiDetector already handles text
                    // columns whose name matches a PII pattern, so avoid double-warning
                    // on those. Non-text quasi-id columns without a rule (e.g. birth_date
                    // date, postal_code integer) would otherwise be invisible — flag them.
                    if (isTextType(dataType)) continue;
                    warnings.add(new QuasiIdWarning(
                            tableName, columnName, dataType, null, matchedPattern));
                } else if (!neutralizing.contains(effective)) {
                    warnings.add(new QuasiIdWarning(
                            tableName, columnName, dataType, effective, matchedPattern));
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("QuasiIdDetector emitted {} warning(s) over {} pattern(s)",
                    warnings.size(), patterns.size());
        }
        return warnings;
    }

    /**
     * Indexes {@code "table.column" → Strategy} from the post-propagation config.
     */
    private Map<String, Strategy> buildRuleIndex(AnonymizerConfig config) {
        Map<String, Strategy> index = new HashMap<>();
        if (config.anonymization() == null || config.anonymization().tables() == null) {
            return index;
        }
        for (TableAnonymizationConfig table : config.anonymization().tables()) {
            if (table.columns() == null) continue;
            for (ColumnConfig col : table.columns()) {
                index.put(table.table() + "." + col.name(), col.strategy());
            }
        }
        return index;
    }

    /**
     * Returns the first pattern that is a substring of the given lower-cased column name,
     * or {@code null} if none match. Same matching semantics as
     * {@link com.fungle.brume.plan.PiiDetector#findMatchingPattern(String)}.
     */
    private String findMatchingPattern(String lowerColumnName, List<String> patterns) {
        for (String pattern : patterns) {
            if (lowerColumnName.contains(pattern)) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} when the data type is text-like — i.e. covered by
     * {@link com.fungle.brume.plan.PiiDetector} when no rule is declared. Used to
     * avoid emitting a redundant quasi-id warning on text columns that PiiDetector
     * already handles.
     */
    private boolean isTextType(String dataType) {
        if (dataType == null) return false;
        String lower = dataType.toLowerCase(Locale.ROOT);
        return TEXT_TYPE_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
