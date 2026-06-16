package com.fungle.brume.report;

import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates natural-language {@link Insight}s from plan vs actual comparison data.
 *
 * <p>Encapsulates all insight-generation rules, keeping
 * {@link com.fungle.brume.agent.ReplicationAgent} focused on orchestration.
 * Rules are evaluated in a fixed order: per-table anomalies first, then global summaries.
 *
 * <p>All messages are written in French and designed to be actionable — each observation
 * either confirms expected behaviour or points the operator to a concrete corrective action.
 */
public class InsightGenerator {

    private final DatabaseSchema schema;

    /**
     * Creates an {@code InsightGenerator} that can attribute extra rows to FK relationships.
     *
     * @param schema source database schema used for FK attribution; may be {@code null},
     *               in which case FK attribution is skipped
     */
    public InsightGenerator(DatabaseSchema schema) {
        this.schema = schema;
    }

    /**
     * Generates the full list of insights for a completed run.
     *
     * <p>Rules applied (in order):
     * <ol>
     *   <li>Per-table: positive delta (&gt; +1 %) — FK attribution if available</li>
     *   <li>Per-table: negative delta (&lt; −1 %) — fewer rows than planned</li>
     *   <li>Per-table: table absent from plan but present in execution</li>
     *   <li>Per-table: FK parents resolved — informational (no delta anomaly)</li>
     *   <li>Per-table: conflicts (rows skipped by {@code ON CONFLICT DO NOTHING})</li>
     *   <li>Per-table: batch errors (JDBC batches that failed entirely)</li>
     *   <li>Global: N/M tables matched exactly</li>
     *   <li>Global: total FK parent rows resolved</li>
     *   <li>Global: insertion rate (100 % or partial)</li>
     *   <li>Global: no conflicts confirmation</li>
     *   <li>Global: no batch errors confirmation</li>
     *   <li>Global: PII warnings count</li>
     * </ol>
     *
     * @param rows        per-table comparison rows (plan vs actual)
     * @param exec        post-execution summary (includes per-table {@link TableStats})
     * @param piiWarnings PII column warnings detected before the run
     * @param planByTable planned stats indexed by table name (may be empty but not null)
     * @return ordered list of natural-language insights
     */
    public List<Insight> generate(List<ComparisonRow> rows,
                                   ExecutionSummary exec,
                                   List<PiiWarning> piiWarnings,
                                   Map<String, PlanTableStats> planByTable) {
        List<Insight> insights = new ArrayList<>();

        Map<String, TableStats> execByTable = exec.tableStats().stream()
                .collect(Collectors.toMap(TableStats::table, t -> t));

        // Per-table observations
        for (ComparisonRow row : rows) {
            TableStats ts = execByTable.get(row.table());
            PlanTableStats ps = planByTable.get(row.table());
            generateTableInsights(row, ts, ps, insights);
        }

        // Global observations
        generateGlobalInsights(rows, exec, piiWarnings, insights);

        return insights;
    }

    // -------------------------------------------------------------------------
    // Per-table rules
    // -------------------------------------------------------------------------

    private void generateTableInsights(ComparisonRow row, TableStats ts,
                                        PlanTableStats ps, List<Insight> insights) {
        long excess = row.actual() - row.planned();

        String cat = row.table();

        // Rule 1 — more rows than planned (positive delta > 1 %)
        if (row.deltaPercent() > 1.0) {
            StringBuilder msg = new StringBuilder(row.table())
                    .append(" : +").append(excess).append(" ligne(s) non planifiée(s)");
            if (ts != null && ts.fkParents() > 0) {
                msg.append(" (dont ").append(ts.fkParents()).append(" via résolution FK)");
            }
            String fkAttrib = findFkReferencingTables(row.table());
            if (fkAttrib != null) {
                msg.append(" — demandée(s) par ").append(fkAttrib);
            }
            msg.append(". Envisager un filtre explicite sur cette table.");
            insights.add(new Insight(Insight.Level.WARN, msg.toString(), cat));
        }

        // Rule 2 — fewer rows than planned (negative delta > 1 %)
        if (row.deltaPercent() < -1.0) {
            String msg = row.table() + " : " + Math.abs(excess) + " ligne(s) de moins que prévu"
                    + " — filtre actif ou lignes absentes de la source.";
            insights.add(new Insight(Insight.Level.WARN, msg, cat));
        }

        // Rule 3 — table absent from plan but rows extracted (planned == 0 and ps == null)
        if (ps == null && row.actual() > 0) {
            StringBuilder msg = new StringBuilder(row.table())
                    .append(" : table non planifiée — ")
                    .append(row.actual())
                    .append(" ligne(s) extraite(s) par remontée FK");
            String fkAttrib = findFkReferencingTables(row.table());
            if (fkAttrib != null) {
                msg.append(" (depuis ").append(fkAttrib).append(")");
            }
            msg.append(".");
            insights.add(new Insight(Insight.Level.WARN, msg.toString(), cat));
        }

        // Rule 4 — FK parents resolved without delta anomaly (informational)
        if (ts != null && ts.fkParents() > 0 && Math.abs(row.deltaPercent()) <= 1.0) {
            insights.add(new Insight(Insight.Level.OK,
                    row.table() + " : " + ts.fkParents()
                    + " ligne(s) parente(s) remontée(s) automatiquement par FK.", cat));
        }

        // Rule 5 — conflicts (rows silently skipped by ON CONFLICT DO NOTHING)
        if (row.conflicts() > 0) {
            insights.add(new Insight(Insight.Level.WARN,
                    row.table() + " : " + row.conflicts()
                    + " doublon(s) ignoré(s) — ces lignes existent déjà en cible.", cat));
        }

        // Rule 6 — batch errors (JDBC batches that failed entirely)
        if (ts != null && ts.batchErrors() > 0) {
            insights.add(new Insight(Insight.Level.ERROR,
                    row.table() + " : " + ts.batchErrors()
                    + " lot(s) en erreur — lignes non insérées. Vérifier les logs.", cat));
        }
    }

    // -------------------------------------------------------------------------
    // Global rules
    // -------------------------------------------------------------------------

    private void generateGlobalInsights(List<ComparisonRow> rows, ExecutionSummary exec,
                                         List<PiiWarning> piiWarnings, List<Insight> insights) {
        // Rule 7 — N/M tables matched exactly
        long exactMatches = rows.stream()
                .filter(r -> Math.abs(r.deltaPercent()) <= 1.0 && r.conflicts() == 0)
                .count();
        long total = rows.size();
        insights.add(new Insight(Insight.Level.OK,
                exactMatches + "/" + total + " table(s) correspondent exactement au plan.",
                "Global"));

        // Rule 8 — total FK parent rows resolved across all tables
        long totalFkParents = exec.tableStats().stream()
                .mapToLong(TableStats::fkParents)
                .sum();
        if (totalFkParents > 0) {
            insights.add(new Insight(Insight.Level.OK,
                    totalFkParents + " ligne(s) parente(s) résolues par remontée FK au total.",
                    "Global"));
        }

        // Rule 9 — insertion rate
        long totalActual   = exec.totalExtracted();
        long totalInserted = exec.totalInserted();
        if (totalActual > 0) {
            long dropped = totalActual - totalInserted;
            if (dropped == 0) {
                insights.add(new Insight(Insight.Level.OK,
                        "100 % des lignes extraites ont été insérées en cible ("
                        + totalInserted + " / " + totalActual + ").",
                        "Global"));
            } else {
                long pct = Math.round(dropped * 100.0 / totalActual);
                insights.add(new Insight(Insight.Level.WARN,
                        dropped + " ligne(s) non inscrite(s) en cible sur " + totalActual
                        + " (" + pct + " %) — conflits ou erreurs de lot.",
                        "Global"));
            }
        }

        // Rule 10 — no conflicts confirmation
        if (exec.totalConflicts() == 0) {
            insights.add(new Insight(Insight.Level.OK, "Aucun doublon détecté en cible.", "Global"));
        }

        // Rule 11 — no batch errors confirmation
        if (exec.totalBatchErrors() == 0) {
            insights.add(new Insight(Insight.Level.OK, "Aucune erreur de lot.", "Global"));
        }

        // Rule 12 — PII warnings
        if (piiWarnings != null && !piiWarnings.isEmpty()) {
            insights.add(new Insight(Insight.Level.WARN,
                    piiWarnings.size() + " colonne(s) potentiellement sensible(s) sans règle"
                    + " d'anonymisation — vérifier config.yaml.",
                    "Global"));
        }
    }

    // -------------------------------------------------------------------------
    // FK attribution helper
    // -------------------------------------------------------------------------

    /**
     * Finds all tables that declare a FK pointing to {@code targetTable} and returns a
     * comma-separated list of {@code 'table.column'} references (non-self-referential only),
     * or {@code null} if none found or if the schema is unavailable.
     *
     * @param targetTable the table whose inbound FK references we are looking for
     * @return formatted attribution string, or {@code null}
     */
    private String findFkReferencingTables(String targetTable) {
        if (schema == null) return null;
        List<String> refs = new ArrayList<>();
        for (var tableMeta : schema.tables().values()) {
            if (tableMeta.foreignKeys() == null) continue;
            for (ForeignKey fk : tableMeta.foreignKeys()) {
                if (targetTable.equals(fk.toTable()) && !targetTable.equals(fk.fromTable())) {
                    refs.add("'" + tableMeta.name() + "." + fk.fromColumn() + "'");
                }
            }
        }
        return refs.isEmpty() ? null : String.join(", ", refs);
    }
}

