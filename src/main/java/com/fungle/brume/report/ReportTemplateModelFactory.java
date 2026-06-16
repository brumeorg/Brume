package com.fungle.brume.report;

import com.fungle.brume.config.model.Strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds view-model maps consumed by Thymeleaf report templates.
 *
 * <p>This factory centralizes the variables passed to the templates so report rendering can
 * progressively move out of {@link HtmlReportRenderer} while preserving its public API.
 */
public class ReportTemplateModelFactory {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter HERO_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter HERO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    /** All known DDL object keywords that {@link #inferDdlObjectType(String)} recognizes. */
    private static final Set<String> KNOWN_DDL_OBJECTS = Set.of(
            "VIEW", "TRIGGER", "INDEX", "EXTENSION", "POLICY", "FUNCTION", "TABLE",
            "SEQUENCE", "TYPE", "DOMAIN", "MATERIALIZED VIEW", "SCHEMA"
    );

    private static String formatPlanOrigin(String origin) {
        return switch (origin) {
            case "direct"                       -> "direct";
            case "fk-child"                     -> "FK enfant";
            case "fk-parent"                    -> "FK parent";
            case "direct+fk-child"              -> "direct + FK enfant";
            case "direct+fk-parent"             -> "direct + FK parent";
            case "fk-child+fk-parent"           -> "FK enfant + parent";
            case "direct+fk-child+fk-parent"    -> "direct + FK enfant + parent";
            default -> origin;
        };
    }

    private static String formatCountOrError(long value) {
        return value < 0 ? "err" : formatCount(value);
    }

    private static String formatCount(long value) {
        return String.format(java.util.Locale.US, "%,d", value).replace(',', ' ');
    }

    private static String formatDurationCompact(long millis) {
        if (millis < 1_000) {
            return millis + " ms";
        }
        if (millis < 10_000) {
            BigDecimal seconds = BigDecimal.valueOf(millis)
                    .divide(BigDecimal.valueOf(1_000), 1, RoundingMode.DOWN);
            return seconds.toPlainString().replace('.', ',') + " s";
        }

        long totalSeconds = millis / 1_000;
        if (millis < 60_000) {
            return totalSeconds + " s";
        }

        long hours = totalSeconds / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;

        if (millis < 3_600_000) {
            return minutes + " min " + seconds + " s";
        }
        return hours + " h " + minutes + " min " + seconds + " s";
    }

    private static String formatDelta(double value) {
        if (Double.isNaN(value) || value == 0.0) {
            return "0%";
        }
        int rounded = (int) Math.round(value);
        if (rounded == 0) {
            return value < 0 ? "-0%" : "+0%";
        }
        return String.format("%+d%%", rounded);
    }

    private static String deltaBackground(double value) {
        double abs = Math.abs(value);
        if (abs <= 1.0) {
            return "#d1fae5";
        }
        if (abs <= 10.0) {
            return "#fef3c7";
        }
        return "#fee2e2";
    }

    private static String formatInstant(Instant value) {
        return value == null ? "" : ISO_FORMATTER.format(value);
    }

    /**
     * Builds the model for a full HTML document wrapper.
     *
     * @param title       document title
     * @param cssContent  CSS block content to inline inside {@code <style>}
     * @return deterministic template model preserving insertion order
     */
    public Map<String, Object> document(String title, String cssContent) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("title", title);
        model.put("cssContent", cssContent);
        return model;
    }

    /**
     * Builds the model for the full execution report template.
     *
     * @param cssContent shared CSS content to inline
     * @param summary execution summary to expose as a structured view-model
     * @return deterministic template model
     */
    public Map<String, Object> executionDocument(String cssContent, ExecutionSummary summary) {
        return executionDocument(cssContent, summary, null);
    }

    /**
     * Builds the model for the full execution report template.
     *
     * @param cssContent shared CSS content to inline
     * @param summary    execution summary to expose as a structured view-model
     * @param comparison optional comparison snapshot to expose as a structured sub-view-model
     * @return deterministic template model
     */
    public Map<String, Object> executionDocument(String cssContent,
                                                 ExecutionSummary summary,
                                                 ComparisonSummary comparison) {
        Map<String, Object> model = document("Brume — Rapport d'exécution", cssContent);

        // Per-table grid view records — consumed by V2 fragment execution-tables-v2.
        model.put("tableRows", buildExecutionTableRows(summary.tableStats()));
        model.put("tableTotals", buildExecutionTableTotals(summary));

        // Comparison (Plan vs Réel) — legacy fragment intentionally preserved.
        model.put("hasComparisonSection", comparison != null);
        model.put("comparisonSection", comparison == null ? null : buildComparisonSection(comparison));

        // V2 model — chantier B — design system v1 keys.
        addExecutionV2Model(model, summary);
        return model;
    }

    /**
     * Builds the model for the full plan report template.
     *
     * @param cssContent shared CSS content to inline
     * @param plan immutable pre-execution plan snapshot
     * @return deterministic template model
     */
    public Map<String, Object> planDocument(String cssContent, PlanSummary plan) {
        Map<String, Object> model = document("Brume — Plan d'exécution", cssContent);

        // V2 model — chantier B — design system v1 keys.
        addPlanV2Model(model, plan);
        return model;
    }

    /**
     * Builds the model for the comparison section template.
     *
     * @param comparison immutable comparison snapshot
     * @return deterministic template model
     */
    public Map<String, Object> comparisonSection(ComparisonSummary comparison) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("comparison", buildComparisonSection(comparison));
        return model;
    }

    private ComparisonSectionView buildComparisonSection(ComparisonSummary comparison) {
        List<ComparisonRowView> rows = buildComparisonRows(comparison.rows());
        List<PiiWarningView> piiWarnings = buildPiiWarnings(comparison.piiWarnings());
        List<InsightGroupView> insightGroups = buildInsightGroups(comparison.insights());
        return new ComparisonSectionView(
                rows,
                buildComparisonTotal(comparison),
                !piiWarnings.isEmpty(),
                piiWarnings.size(),
                piiWarnings,
                !insightGroups.isEmpty(),
                insightGroups
        );
    }

    private List<ExecutionTableRowView> buildExecutionTableRows(List<TableStats> tableStats) {
        List<ExecutionTableRowView> rows = new ArrayList<>();
        if (tableStats == null) {
            return rows;
        }
        for (int i = 0; i < tableStats.size(); i++) {
            TableStats stats = tableStats.get(i);
            rows.add(new ExecutionTableRowView(
                    stats.table(),
                    i % 2 != 0,
                    stats.fkParents() > 0,
                    formatCount(stats.extracted()),
                    formatCount(stats.fkParents()),
                    formatCount(stats.inserted()),
                    formatCount(stats.conflicts()),
                    formatCount(stats.batchErrors()),
                    stats.conflicts() > 0,
                    stats.batchErrors() > 0
            ));
        }
        return rows;
    }

    private ExecutionTableTotalsView buildExecutionTableTotals(ExecutionSummary summary) {
        long totalExtracted = summary.tableStats().stream().mapToLong(TableStats::extracted).sum();
        long totalFkParents = summary.tableStats().stream().mapToLong(TableStats::fkParents).sum();
        long totalInserted = summary.totalInserted();
        long totalConflicts = summary.totalConflicts();
        long totalErrors = summary.totalBatchErrors();

        return new ExecutionTableTotalsView(
                formatCount(totalExtracted),
                formatCount(totalFkParents),
                formatCount(totalInserted),
                formatCount(totalConflicts),
                formatCount(totalErrors),
                totalConflicts > 0,
                totalErrors > 0
        );
    }

    private ComparisonTotalView buildComparisonTotal(ComparisonSummary comparison) {
        long totalPlanned = comparison.totalPlanned();
        long totalActual = comparison.totalActual();
        long totalInserted = comparison.rows().stream().mapToLong(ComparisonRow::inserted).sum();
        long totalConflicts = comparison.rows().stream().mapToLong(ComparisonRow::conflicts).sum();
        double totalDelta = totalPlanned > 0
                ? ((totalActual - totalPlanned) / (double) totalPlanned) * 100.0
                : 0.0;

        return new ComparisonTotalView(
                formatCount(totalPlanned),
                formatCount(totalActual),
                formatDelta(totalDelta),
                deltaBackground(totalDelta),
                formatCount(totalInserted),
                formatCount(totalConflicts),
                totalConflicts > 0
        );
    }

    private List<ComparisonRowView> buildComparisonRows(List<ComparisonRow> comparisonRows) {
        List<ComparisonRowView> rows = new ArrayList<>();
        if (comparisonRows == null) {
            return rows;
        }
        for (int i = 0; i < comparisonRows.size(); i++) {
            ComparisonRow row = comparisonRows.get(i);
            double abs = Math.abs(row.deltaPercent());
            boolean ok = row.conflicts() == 0 && abs <= 1.0;
            rows.add(new ComparisonRowView(
                    row.table(),
                    i % 2 != 0,
                    formatCount(row.planned()),
                    formatCount(row.actual()),
                    formatDelta(row.deltaPercent()),
                    deltaBackground(row.deltaPercent()),
                    formatCount(row.inserted()),
                    formatCount(row.conflicts()),
                    row.conflicts() > 0,
                    ok ? "✓" : "⚠",
                    ok ? "#16a34a" : "#d97706"
            ));
        }
        return rows;
    }

    private List<PiiWarningView> buildPiiWarnings(List<PiiWarning> warnings) {
        List<PiiWarningView> views = new ArrayList<>();
        if (warnings == null) {
            return views;
        }
        for (PiiWarning warning : warnings) {
            views.add(new PiiWarningView(
                    warning.table() + "." + warning.column(),
                    warning.matchedPattern()
            ));
        }
        return views;
    }

    private List<InsightGroupView> buildInsightGroups(List<Insight> insights) {
        Map<String, List<Insight>> byCategory = new LinkedHashMap<>();
        if (insights != null) {
            for (Insight insight : insights) {
                List<Insight> categoryInsights = byCategory.get(insight.category());
                if (categoryInsights == null) {
                    categoryInsights = new ArrayList<>();
                    byCategory.put(insight.category(), categoryInsights);
                }
                categoryInsights.add(insight);
            }
        }

        List<InsightGroupView> groups = new ArrayList<>();
        for (Map.Entry<String, List<Insight>> entry : byCategory.entrySet()) {
            List<InsightItemView> items = new ArrayList<>();
            boolean hasIssues = false;
            long warnCount = 0;

            for (Insight insight : entry.getValue()) {
                String cssClass = switch (insight.level()) {
                    case OK -> "insight-ok";
                    case WARN -> "insight-warn";
                    case ERROR -> "insight-error";
                };
                if (insight.level() != Insight.Level.OK) {
                    warnCount++;
                    hasIssues = true;
                }
                items.add(new InsightItemView(insight.icon(), insight.message(), cssClass));
            }

            int insightCount = items.size();
            String displayLabel = "Global".equals(entry.getKey()) ? "🌐 Global" : "📋 " + entry.getKey();
            String countLabel = "(" + insightCount + " observation" + (insightCount > 1 ? "s" : "") + ")";
            groups.add(new InsightGroupView(
                    displayLabel,
                    hasIssues,
                    countLabel,
                    warnCount + " ⚠",
                    warnCount > 0,
                    items
            ));
        }

        return groups;
    }

    public record PiiWarningView(String qualifiedColumn, String matchedPattern) {
    }

    public record ExecutionTableRowView(
            String table,
            boolean altRow,
            boolean hasFkBadge,
            String extracted,
            String fkParents,
            String inserted,
            String conflicts,
            String batchErrors,
            boolean conflictWarn,
            boolean errorWarn
    ) {
    }

    public record ExecutionTableTotalsView(
            String extracted,
            String fkParents,
            String inserted,
            String conflicts,
            String batchErrors,
            boolean conflictWarn,
            boolean errorWarn
    ) {
    }

    public record ComparisonRowView(
            String table,
            boolean altRow,
            String planned,
            String actual,
            String deltaFormatted,
            String deltaBackground,
            String inserted,
            String conflicts,
            boolean conflictWarn,
            String statusSymbol,
            String statusColor
    ) {
    }

    public record ComparisonTotalView(
            String planned,
            String actual,
            String deltaFormatted,
            String deltaBackground,
            String inserted,
            String conflicts,
            boolean conflictWarn
    ) {
    }

    public record ComparisonSectionView(
            List<ComparisonRowView> rows,
            ComparisonTotalView total,
            boolean hasPiiWarnings,
            int piiWarningCount,
            List<PiiWarningView> piiWarnings,
            boolean hasInsights,
            List<InsightGroupView> insightGroups
    ) {
    }

    public record InsightGroupView(
            String displayLabel,
            boolean open,
            String countLabel,
            String warnBadgeLabel,
            boolean hasWarnBadge,
            List<InsightItemView> items
    ) {
    }

    public record InsightItemView(String icon, String message, String cssClass) {
    }

    // =========================================================================
    // V2 — design system v1 model
    // Consumed by the V2 Thymeleaf fragments (execution-*-v2.html / plan-*-v2.html).
    // The legacy comparison.html + pii-warnings.html fragments still use the
    // ExecutionTableRowView/ExecutionTableTotalsView/PiiWarningView records and
    // the Comparison*/Insight* records preserved above.
    // =========================================================================

    /**
     * Augments the model produced by {@link #executionDocument(String, ExecutionSummary, ComparisonSummary)}
     * with the V2 view-models. Call this after the V1 keys are in place.
     */
    void addExecutionV2Model(Map<String, Object> model, ExecutionSummary summary) {
        model.put("masthead", buildExecutionMasthead(summary));
        model.put("hero", buildExecutionHero(summary));
        model.put("metaStrip", buildExecutionMetaStrip(summary));
        model.put("statRow", buildExecutionStatRow(summary));
        model.put("timingV2", buildExecutionTimingV2(summary.timings()));
        model.put("callout", buildExecutionCallout(summary));
        model.put("hasCallout", buildExecutionCallout(summary) != null);
        model.put("strategyCards", buildStrategyCards(summary.strategyUsages()));
        model.put("dictV2", buildExecutionDictV2(summary.substitutionDict()));
        model.put("hasDictV2", buildExecutionDictV2(summary.substitutionDict()) != null);
        model.put("warningSummary", buildWarningSummary(summary));
        model.put("warningBlocks", buildWarningBlocks(summary));
        model.put("footerV2", buildExecutionFooter(summary));
    }

    /**
     * Augments the model produced by {@link #planDocument(String, PlanSummary)} with the V2
     * view-models. Call after V1 keys are in place.
     */
    void addPlanV2Model(Map<String, Object> model, PlanSummary plan) {
        model.put("masthead", buildPlanMasthead(plan));
        model.put("hero", buildPlanHero(plan));
        model.put("metaStrip", buildPlanMetaStrip(plan));
        model.put("statRow", buildPlanStatRow(plan));
        model.put("callouts", buildPlanCallouts(plan));
        model.put("tableRowsV2", buildPlanTableRowsV2(plan));
        model.put("warningBlocksV2", buildPlanWarningBlocks(plan));
        model.put("footerV2", buildPlanFooter(plan));
    }

    // ---------------------------------------------------------- masthead ---

    private ExecutionMastheadView buildExecutionMasthead(ExecutionSummary summary) {
        BrumeRuntimeContext ctx = summary.runtimeContext();
        return new ExecutionMastheadView(
                "Rapport d'exécution",
                summary.sourceSchema(),
                summary.targetSchema(),
                ctx.configPath(),
                formatHeroDate(Instant.now()),
                ctx.brumeVersion(),
                ctx.command(),
                commandPillClass(ctx.command()),
                !ctx.command().isEmpty()
        );
    }

    private PlanMastheadView buildPlanMasthead(PlanSummary plan) {
        BrumeRuntimeContext ctx = plan.runtimeContext();
        return new PlanMastheadView(
                "Plan d'exécution",
                plan.sourceSchema(),
                ctx.configPath(),
                formatHeroDate(plan.estimatedAt()),
                ctx.brumeVersion(),
                ctx.command(),
                commandPillClass(ctx.command()),
                !ctx.command().isEmpty()
        );
    }

    /**
     * Maps a CLI command label to a DS v1 pill class:
     * <ul>
     *   <li>{@code execute} → {@code info} (active, indigo accent)</li>
     *   <li>{@code dry-run} / {@code plan} / {@code audit} → {@code muted} (no-write, gray)</li>
     *   <li>anything else → {@code muted} fallback</li>
     * </ul>
     */
    static String commandPillClass(String command) {
        if ("execute".equals(command)) {
            return "info";
        }
        return "muted";
    }

    // ---------------------------------------------------------- hero ---

    private ExecutionHeroView buildExecutionHero(ExecutionSummary summary) {
        String statusClass = deriveStatusClass(summary);
        String badgeText = switch (statusClass) {
            case "failure" -> "Échec";
            case "partial" -> "Partiel — " + summary.totalBatchErrors() + " lot(s) en erreur";
            default -> "Succès";
        };

        return new ExecutionHeroView(
                statusClass,
                badgeText,
                buildHeadlineProse(summary, statusClass),
                buildLedeProse(summary, statusClass),
                summary.timings().compactClock(),
                formatHeroStarted(summary.startedAt()),
                formatHeroFinished(summary.startedAt(), summary.timings().totalMs()),
                throughputAvg(summary)
        );
    }

    private PlanHeroView buildPlanHero(PlanSummary plan) {
        long piiCount = plan.piiWarnings() == null ? 0 : plan.piiWarnings().size();
        long qidCount = plan.quasiIdWarnings() == null ? 0 : plan.quasiIdWarnings().size();
        String badgeText = "Pré-vol — décision go / no-go";

        long totalDirect = sumPlanned(plan, PlanTableStats::plannedDirect);
        long totalFkChildren = sumPlanned(plan, PlanTableStats::plannedFkChildren);
        long totalFkParents = sumPlanned(plan, PlanTableStats::plannedFkParents);

        return new PlanHeroView(
                badgeText,
                buildPlanHeadlineProse(plan, piiCount, qidCount),
                buildPlanLedeProse(plan, piiCount, qidCount),
                formatCount(plan.totalPlanned()),
                formatCount(totalDirect),
                formatCount(totalFkChildren),
                formatCount(totalFkParents),
                plan.tableStats() == null ? 0 : plan.tableStats().size()
        );
    }

    // ---------------------------------------------------------- meta strip ---

    private ExecutionMetaStripView buildExecutionMetaStrip(ExecutionSummary summary) {
        BrumeRuntimeContext ctx = summary.runtimeContext();
        return new ExecutionMetaStripView(
                summary.sourceSchema(),
                summary.targetSchema(),
                ctx.configPath(),
                ctx.ddlErrorMode(),
                ctx.fakerLocale()
        );
    }

    private PlanMetaStripView buildPlanMetaStrip(PlanSummary plan) {
        BrumeRuntimeContext ctx = plan.runtimeContext();
        return new PlanMetaStripView(
                plan.sourceSchema(),
                ctx.configPath(),
                ctx.ddlErrorMode(),
                ctx.fakerLocale()
        );
    }

    // ---------------------------------------------------------- stat row ---

    private ExecutionStatRowView buildExecutionStatRow(ExecutionSummary summary) {
        long totalExtracted = summary.totalExtracted();
        long totalInserted = summary.totalInserted();
        long totalConflicts = summary.totalConflicts();
        long totalBatchErrors = summary.totalBatchErrors();
        long tablesWithData = summary.tableStats() == null ? 0
                : summary.tableStats().stream()
                        .filter(t -> t.extracted() > 0 || t.fkParents() > 0)
                        .count();
        long tablesWithConflicts = summary.tableStats() == null ? 0
                : summary.tableStats().stream().filter(t -> t.conflicts() > 0).count();
        long tablesWithErrors = summary.tableStats() == null ? 0
                : summary.tableStats().stream().filter(t -> t.batchErrors() > 0).count();

        return new ExecutionStatRowView(
                formatCount(totalExtracted),
                tablesWithData + " table(s) lue(s)",
                formatCount(totalInserted),
                tablesWithErrors > 0
                        ? formatCount(totalExtracted - totalInserted - totalConflicts) + " lignes non écrites"
                        : "",
                formatCount(totalConflicts),
                totalConflicts > 0
                        ? tablesWithConflicts + " table(s) · ON CONFLICT DO NOTHING"
                        : "",
                formatCount(totalBatchErrors),
                totalBatchErrors > 0
                        ? tablesWithErrors + " table(s) · voir les logs"
                        : "",
                totalConflicts > 0,
                totalBatchErrors > 0
        );
    }

    private PlanStatRowView buildPlanStatRow(PlanSummary plan) {
        long piiCount = plan.piiWarnings() == null ? 0 : plan.piiWarnings().size();
        long qidCount = plan.quasiIdWarnings() == null ? 0 : plan.quasiIdWarnings().size();
        int tablesCount = plan.tableStats() == null ? 0 : plan.tableStats().size();

        return new PlanStatRowView(
                formatCount(plan.totalPlanned()),
                tablesCount + " table(s) · estimation pg_class",
                formatCount(piiCount),
                derivePlanPiiStats(plan),
                formatCount(qidCount),
                derivePlanQuasiIdStats(plan),
                piiCount > 0,
                qidCount > 0
        );
    }

    // ---------------------------------------------------------- timing v2 ---

    private ExecutionTimingV2View buildExecutionTimingV2(PhaseTimings timings) {
        long extraction = Math.max(0L, timings.extractionMs());
        long anonymization = Math.max(0L, timings.anonymizationMs());
        long write = Math.max(0L, timings.writeMs());
        long phasesSum = extraction + anonymization + write;
        long ref = phasesSum > 0 ? phasesSum : Math.max(1L, timings.totalMs());

        double extractionPct = (extraction * 100.0) / ref;
        double anonymizationPct = (anonymization * 100.0) / ref;
        double writePct = (write * 100.0) / ref;

        return new ExecutionTimingV2View(
                formatDurationCompact(timings.totalMs()),
                formatTimingPhase(extraction, extractionPct, "Extraction"),
                formatTimingPhase(anonymization, anonymizationPct, "Anonymisation"),
                formatTimingPhase(write, writePct, "Écriture"),
                String.format(java.util.Locale.US, "%.1f", extractionPct),
                String.format(java.util.Locale.US, "%.1f", anonymizationPct),
                String.format(java.util.Locale.US, "%.1f", writePct)
        );
    }

    private TimingPhaseView formatTimingPhase(long ms, double pct, String label) {
        return new TimingPhaseView(
                label,
                formatDurationCompact(ms),
                String.format(java.util.Locale.US, "%.1f %%", pct)
        );
    }

    // ---------------------------------------------------------- callout ---

    private ExecutionCalloutView buildExecutionCallout(ExecutionSummary summary) {
        if (!summary.success() && summary.failureCause() != null) {
            return new ExecutionCalloutView(
                    "err",
                    "Action requise",
                    "Le pipeline a échoué : " + summary.failureCause()
                            + ". Aucune anonymisation supplémentaire n'a été appliquée à partir de ce point."
            );
        }
        if (summary.totalBatchErrors() > 0) {
            long tablesWithErrors = summary.tableStats() == null ? 0
                    : summary.tableStats().stream().filter(t -> t.batchErrors() > 0).count();
            return new ExecutionCalloutView(
                    "err",
                    "Action requise",
                    formatCount(summary.totalBatchErrors()) + " lot(s) ont échoué sur "
                            + tablesWithErrors + " table(s). Brume ne capture pas aujourd'hui "
                            + "la cause individuelle de chaque lot — consulter les logs Postgres / "
                            + "applicatifs pour identifier la cause."
            );
        }
        return null;
    }

    private List<PlanCalloutView> buildPlanCallouts(PlanSummary plan) {
        List<PlanCalloutView> callouts = new ArrayList<>();
        long piiCount = plan.piiWarnings() == null ? 0 : plan.piiWarnings().size();
        long qidCount = plan.quasiIdWarnings() == null ? 0 : plan.quasiIdWarnings().size();

        if (piiCount > 0) {
            callouts.add(new PlanCalloutView(
                    "warn",
                    "À résoudre",
                    formatCount(piiCount) + " colonne(s) PII détectée(s) par pattern ne sont couvertes "
                            + "par aucune règle — elles partiraient telles quelles en cible."
            ));
        }
        if (qidCount > 0) {
            callouts.add(new PlanCalloutView(
                    "warn",
                    "À surveiller",
                    formatCount(qidCount) + " quasi-identifiant(s) dont la stratégie préserve la corrélation. "
                            + "Combinés à d'autres colonnes, ils peuvent permettre une ré-identification. "
                            + "Revue DPO recommandée."
            ));
        }
        return callouts;
    }

    // ---------------------------------------------------------- strategy cards ---

    private List<ExecutionStrategyCardView> buildStrategyCards(List<StrategyUsage> usages) {
        List<ExecutionStrategyCardView> cards = new ArrayList<>();
        if (usages == null) {
            return cards;
        }
        Map<String, Long> countsByStrategy = new LinkedHashMap<>();
        for (StrategyUsage u : usages) {
            countsByStrategy.merge(u.strategy(), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : countsByStrategy.entrySet()) {
            cards.add(new ExecutionStrategyCardView(
                    e.getKey(),
                    Strategy.descriptionOf(e.getKey()),
                    formatCount(e.getValue())
            ));
        }
        return cards;
    }

    // ---------------------------------------------------------- dict V2 ---

    private ExecutionDictV2View buildExecutionDictV2(SubstitutionDictStats stats) {
        if (stats == null || !stats.hasLimit()) {
            return null;
        }
        long entries = stats.entries();
        long limit = stats.limit();
        long headroom = Math.max(0L, limit - entries);
        double percent = limit > 0 ? (entries * 100.0) / limit : 0.0;

        List<ExecutionDictContributorView> contributors = new ArrayList<>();
        for (SubstitutionDictStats.TopContributor c : stats.topContributors()) {
            contributors.add(new ExecutionDictContributorView(
                    c.semanticKey(),
                    formatCount(c.entries())
            ));
        }
        return new ExecutionDictV2View(
                formatCount(entries),
                formatCount(limit),
                formatCount(headroom),
                String.format(java.util.Locale.US, "%.2f", percent),
                contributors,
                percent >= 75.0
        );
    }

    // ---------------------------------------------------------- warnings ---

    private ExecutionWarningSummaryView buildWarningSummary(ExecutionSummary summary) {
        long totalBatchErrors = summary.totalBatchErrors();
        long tablesWithErrors = summary.tableStats() == null ? 0
                : summary.tableStats().stream().filter(t -> t.batchErrors() > 0).count();
        int ddlIgnored = summary.ddlExecution() == null ? 0 : summary.ddlExecution().ignored();
        long totalConflicts = summary.totalConflicts();
        long tablesWithConflicts = summary.tableStats() == null ? 0
                : summary.tableStats().stream().filter(t -> t.conflicts() > 0).count();

        return new ExecutionWarningSummaryView(
                formatCount(totalBatchErrors),
                totalBatchErrors > 0
                        ? tablesWithErrors + " table(s) en erreur"
                        : "aucun lot en erreur",
                formatCount(ddlIgnored),
                ddlIgnored > 0 ? "mode LENIENT — le run a continué" : "aucun DDL ignoré",
                formatCount(totalConflicts),
                totalConflicts > 0
                        ? tablesWithConflicts + " table(s) · lignes droppées par ON CONFLICT"
                        : "aucun conflit"
        );
    }

    private ExecutionWarningBlocksView buildWarningBlocks(ExecutionSummary summary) {
        return new ExecutionWarningBlocksView(
                buildBatchErrorBlock(summary),
                buildDdlIgnoredBlock(summary),
                buildConflictBlock(summary)
        );
    }

    private List<BatchErrorRowView> buildBatchErrorBlock(ExecutionSummary summary) {
        List<BatchErrorRowView> rows = new ArrayList<>();
        if (summary.tableStats() == null) {
            return rows;
        }
        for (TableStats stats : summary.tableStats()) {
            if (stats.batchErrors() > 0) {
                long unwritten = Math.max(0L,
                        stats.extracted() + stats.fkParents() - stats.inserted() - stats.conflicts());
                rows.add(new BatchErrorRowView(
                        stats.table(),
                        formatCount(stats.batchErrors()),
                        formatCount(unwritten)
                ));
            }
        }
        return rows;
    }

    private List<DdlIgnoredRowView> buildDdlIgnoredBlock(ExecutionSummary summary) {
        List<DdlIgnoredRowView> rows = new ArrayList<>();
        if (summary.ddlExecution() == null) {
            return rows;
        }
        for (DdlFailure f : summary.ddlExecution().failures()) {
            rows.add(new DdlIgnoredRowView(
                    inferDdlObjectType(f.sqlPreview()),
                    f.sqlPreview(),
                    f.errorMessage(),
                    formatDdlStatementNumber(f.statementIndex())
            ));
        }
        return rows;
    }

    private List<ConflictRowView> buildConflictBlock(ExecutionSummary summary) {
        List<ConflictRowView> rows = new ArrayList<>();
        if (summary.tableStats() == null) {
            return rows;
        }
        for (TableStats stats : summary.tableStats()) {
            if (stats.conflicts() > 0) {
                rows.add(new ConflictRowView(
                        stats.table(),
                        formatCount(stats.conflicts()),
                        "Lignes droppées par ON CONFLICT DO NOTHING"
                ));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------- footer ---

    private ExecutionFooterView buildExecutionFooter(ExecutionSummary summary) {
        HeapStats heap = summary.heap();
        String heapDisplay = heap == null || heap.maxBytes() <= 0
                ? "n/a"
                : formatGiB(heap.peakUsedBytes()) + " / " + formatGiB(heap.maxBytes());

        String heapAlert;
        if (heap == null || heap.maxBytes() <= 0) {
            heapAlert = "n/a";
        } else if (heap.warningTriggered()) {
            heapAlert = "ALERTE · seuil " + heap.warningThresholdPercent() + " % dépassé";
        } else {
            heapAlert = "OK · < " + heap.warningThresholdPercent() + " %";
        }

        return new ExecutionFooterView(
                heapDisplay,
                throughputAvg(summary),
                heapAlert,
                summary.runtimeContext().brumeVersion(),
                formatHeroDate(Instant.now())
        );
    }

    private PlanFooterView buildPlanFooter(PlanSummary plan) {
        return new PlanFooterView(
                plan.runtimeContext().brumeVersion(),
                formatHeroDate(plan.estimatedAt())
        );
    }

    // ---------------------------------------------------------- plan tables V2 ---

    private List<PlanTableRowV2View> buildPlanTableRowsV2(PlanSummary plan) {
        List<PlanTableRowV2View> rows = new ArrayList<>();
        if (plan.tableStats() == null) {
            return rows;
        }
        for (PlanTableStats stats : plan.tableStats()) {
            String warningPill = derivePlanRowWarning(plan, stats.table());
            String origin = stats.origin();
            boolean hasFkBadge = origin != null && (origin.contains("fk-parent") || origin.contains("fk-child"));
            rows.add(new PlanTableRowV2View(
                    stats.table(),
                    formatPlanOrigin(origin),
                    hasFkBadge,
                    formatCountOrError(stats.plannedDirect()),
                    formatCountOrError(stats.plannedFkChildren()),
                    formatCountOrError(stats.plannedFkParents()),
                    formatCountOrError(stats.plannedTotal()),
                    warningPill,
                    warningPill != null && !warningPill.isEmpty()
            ));
        }
        return rows;
    }

    private List<PlanWarningRowView> buildPlanWarningBlocks(PlanSummary plan) {
        List<PlanWarningRowView> rows = new ArrayList<>();
        if (plan.piiWarnings() != null) {
            for (PiiWarning w : plan.piiWarnings()) {
                rows.add(new PlanWarningRowView(
                        "PII",
                        w.table() + "." + w.column(),
                        "Pattern " + w.matchedPattern(),
                        "Sans règle d'anonymisation"
                ));
            }
        }
        if (plan.quasiIdWarnings() != null) {
            for (QuasiIdWarning w : plan.quasiIdWarnings()) {
                String strategyLabel = w.effectiveStrategy() == null
                        ? "KEEP (implicite)"
                        : w.effectiveStrategy().name();
                rows.add(new PlanWarningRowView(
                        "Quasi-ID",
                        w.table() + "." + w.column(),
                        "Pattern " + w.matchedPattern() + " · type " + w.dataType(),
                        strategyLabel
                ));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------- helpers ---

    /**
     * Derives a CSS class describing the overall run status: {@code "success"},
     * {@code "partial"} (success with at least one failed batch), or {@code "failure"}.
     */
    static String deriveStatusClass(ExecutionSummary summary) {
        if (!summary.success()) {
            return "failure";
        }
        if (summary.totalBatchErrors() > 0) {
            return "partial";
        }
        return "success";
    }

    /**
     * Returns the average throughput in formatted form (e.g. {@code "287k lignes/s"}),
     * computed as {@code totalInserted / totalSeconds}. Returns {@code "n/a"} when the run
     * lasted less than 1 second.
     */
    static String throughputAvg(ExecutionSummary summary) {
        long ms = summary.timings().totalMs();
        if (ms < 1_000L) {
            return "n/a";
        }
        double rowsPerSecond = summary.totalInserted() * 1000.0 / ms;
        if (rowsPerSecond >= 1_000_000.0) {
            return String.format(java.util.Locale.US, "%.1fM lignes/s", rowsPerSecond / 1_000_000.0);
        }
        if (rowsPerSecond >= 1_000.0) {
            return String.format(java.util.Locale.US, "%.0fk lignes/s", rowsPerSecond / 1_000.0);
        }
        return String.format(java.util.Locale.US, "%.0f lignes/s", rowsPerSecond);
    }

    /**
     * Counts the distinct {@code (table, column)} pairs that received a strategy. Used as
     * the « 242 colonnes traitées » figure in the timing legend.
     */
    static long coveredColumnsCount(ExecutionSummary summary) {
        if (summary.strategyUsages() == null) {
            return 0L;
        }
        return summary.strategyUsages().stream()
                .map(u -> u.table() + "|" + u.column())
                .distinct()
                .count();
    }

    /**
     * Formats the hero date as {@code "yyyy-MM-dd HH:mm:ss UTC"}. Used in the masthead
     * and the footer.
     */
    static String formatHeroDate(Instant instant) {
        return instant == null ? "" : HERO_DATE_FORMATTER.format(instant);
    }

    /**
     * Formats the hero "started at" time as {@code "HH:mm:ss"}.
     */
    static String formatHeroStarted(Instant startedAt) {
        return startedAt == null ? "" : HERO_TIME_FORMATTER.format(startedAt);
    }

    /**
     * Formats the hero "finished at" time as {@code "HH:mm:ss"}, computed as
     * {@code startedAt + totalMs}.
     */
    static String formatHeroFinished(Instant startedAt, long totalMs) {
        if (startedAt == null) {
            return "";
        }
        return HERO_TIME_FORMATTER.format(startedAt.plusMillis(Math.max(0L, totalMs)));
    }

    /**
     * Infers the DDL object type ({@code VIEW}, {@code TRIGGER}, {@code INDEX}, etc.) from
     * the first words of the SQL preview. Falls back to {@code "DDL"} when no keyword is
     * recognized. Used in the warnings block of the execution report.
     */
    static String inferDdlObjectType(String sqlPreview) {
        if (sqlPreview == null || sqlPreview.isBlank()) {
            return "DDL";
        }
        String upper = sqlPreview.trim().toUpperCase(java.util.Locale.ROOT);
        // Strip leading verb (CREATE / ALTER / DROP) so we look at the object keyword.
        String[] verbs = {"CREATE ", "ALTER ", "DROP ", "REPLACE "};
        for (String v : verbs) {
            if (upper.startsWith(v)) {
                upper = upper.substring(v.length()).trim();
                break;
            }
        }
        // MATERIALIZED VIEW is a 2-word keyword — check it first.
        if (upper.startsWith("MATERIALIZED VIEW")) {
            return "MATERIALIZED VIEW";
        }
        for (String keyword : KNOWN_DDL_OBJECTS) {
            if (upper.startsWith(keyword + " ") || upper.equals(keyword)) {
                return keyword;
            }
        }
        return "DDL";
    }

    /**
     * Formats a DDL statement index as {@code "#142,901"} with space-separated thousands.
     */
    static String formatDdlStatementNumber(int idx) {
        return "#" + formatCount(idx);
    }

    /**
     * Returns a short summary of the patterns that matched the PII warnings,
     * e.g. {@code "3 tables · pattern email/phone/address"}. Empty when none.
     */
    static String derivePlanPiiStats(PlanSummary plan) {
        if (plan.piiWarnings() == null || plan.piiWarnings().isEmpty()) {
            return "";
        }
        long tables = plan.piiWarnings().stream().map(PiiWarning::table).distinct().count();
        String patterns = plan.piiWarnings().stream()
                .map(PiiWarning::matchedPattern)
                .distinct()
                .limit(3)
                .reduce((a, b) -> a + "/" + b)
                .orElse("");
        return tables + " table(s) · pattern " + patterns;
    }

    /**
     * Returns a short summary of the quasi-identifier risk,
     * e.g. {@code "2 colonnes KEEP à risque de ré-identification"}. Empty when none.
     */
    static String derivePlanQuasiIdStats(PlanSummary plan) {
        if (plan.quasiIdWarnings() == null || plan.quasiIdWarnings().isEmpty()) {
            return "";
        }
        long keep = plan.quasiIdWarnings().stream()
                .filter(w -> w.effectiveStrategy() == null
                        || w.effectiveStrategy() == Strategy.KEEP)
                .count();
        if (keep == 0) {
            return plan.quasiIdWarnings().size() + " colonne(s) corrélables";
        }
        return keep + " colonne(s) KEEP à risque de ré-identification";
    }

    /**
     * Returns the warning pill text for a plan row, or empty when the table has no warning.
     * Pattern: {@code "PII × 2"} or {@code "PII × 1 · QID × 1"}.
     */
    private static String derivePlanRowWarning(PlanSummary plan, String table) {
        long pii = plan.piiWarnings() == null ? 0
                : plan.piiWarnings().stream().filter(w -> table.equals(w.table())).count();
        long qid = plan.quasiIdWarnings() == null ? 0
                : plan.quasiIdWarnings().stream().filter(w -> table.equals(w.table())).count();

        if (pii == 0 && qid == 0) {
            return "";
        }
        if (pii > 0 && qid > 0) {
            return "PII × " + pii + " · QID × " + qid;
        }
        if (pii > 0) {
            return "PII × " + pii;
        }
        return "QID × " + qid;
    }

    /**
     * Builds the headline prose of the execution hero. Simple French branching on status —
     * the « rich » prose (named failed table, batch number, etc.) is out of V1 scope.
     */
    private static String buildHeadlineProse(ExecutionSummary summary, String statusClass) {
        int totalTables = summary.tableStats() == null ? 0 : summary.tableStats().size();
        long withErrors = summary.tableStats() == null ? 0
                : summary.tableStats().stream().filter(t -> t.batchErrors() > 0).count();
        long writtenTables = summary.tableStats() == null ? 0
                : summary.tableStats().stream().filter(t -> t.inserted() > 0).count();

        return switch (statusClass) {
            case "failure" -> "Pipeline interrompu : "
                    + (summary.failureCause() == null ? "cause inconnue" : summary.failureCause());
            case "partial" -> writtenTables + " table(s) sur " + totalTables
                    + " écrite(s), " + withErrors + " en erreur.";
            default -> totalTables + " table(s) anonymisée(s), "
                    + formatCount(summary.totalInserted()) + " lignes insérées.";
        };
    }

    /**
     * Builds the lede paragraph below the headline. Simple French sentence summarising the
     * outcome.
     */
    private static String buildLedeProse(ExecutionSummary summary, String statusClass) {
        return switch (statusClass) {
            case "failure" -> "La cible est dans un état incomplet. Aucune anonymisation "
                    + "supplémentaire n'a été appliquée à partir du point d'échec.";
            case "partial" -> "L'anonymisation a tourné jusqu'au bout, mais certains lots d'insertion "
                    + "ont échoué. La cible est dans un état partiel jusqu'à ce que la cause soit "
                    + "corrigée et les tables concernées rejouées.";
            default -> "Run terminé sans avertissement. Les données anonymisées sont disponibles "
                    + "dans le schéma cible.";
        };
    }

    private static String buildPlanHeadlineProse(PlanSummary plan, long piiCount, long qidCount) {
        int tables = plan.tableStats() == null ? 0 : plan.tableStats().size();
        String summary = tables + " table(s) planifiée(s), "
                + formatCount(plan.totalPlanned()) + " lignes estimées.";
        if (piiCount > 0) {
            summary += " " + piiCount + " colonne(s) PII sans règle.";
        }
        return summary;
    }

    private static String buildPlanLedeProse(PlanSummary plan, long piiCount, long qidCount) {
        if (piiCount == 0 && qidCount == 0) {
            return "Toutes les colonnes détectées sont couvertes par une règle. Le plan peut "
                    + "être lancé sans réserve technique.";
        }
        StringBuilder sb = new StringBuilder("Avant `brume execute`, ce plan énumère ce qui sera "
                + "lu et anonymisé. ");
        if (piiCount > 0) {
            sb.append(piiCount).append(" colonne(s) détectée(s) comme PII n'ont pas de règle — "
                    + "elles partiraient en clair si la run partait en l'état. ");
        }
        if (qidCount > 0) {
            sb.append(qidCount).append(" quasi-identifiant(s) restent en stratégie corrélable.");
        }
        return sb.toString().trim();
    }

    private static String formatGiB(long bytes) {
        if (bytes <= 0L) {
            return "0";
        }
        double gib = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gib >= 1.0) {
            return String.format(java.util.Locale.US, "%.2f GiB", gib);
        }
        double mib = bytes / (1024.0 * 1024.0);
        return String.format(java.util.Locale.US, "%.0f MiB", mib);
    }

    private static long sumPlanned(PlanSummary plan, java.util.function.ToLongFunction<PlanTableStats> extractor) {
        if (plan.tableStats() == null) {
            return 0L;
        }
        return plan.tableStats().stream()
                .mapToLong(extractor)
                .filter(v -> v >= 0L)
                .sum();
    }

    // ---------------------------------------------------------- V2 records ---

    public record ExecutionMastheadView(
            String reportTitle,
            String sourceSchema,
            String targetSchema,
            String configPath,
            String generatedAt,
            String brumeVersion,
            String commandLabel,
            String commandPillClass,
            boolean hasCommand
    ) {
    }

    public record PlanMastheadView(
            String reportTitle,
            String sourceSchema,
            String configPath,
            String estimatedAt,
            String brumeVersion,
            String commandLabel,
            String commandPillClass,
            boolean hasCommand
    ) {
    }

    public record ExecutionHeroView(
            String statusClass,      // "success" / "partial" / "failure"
            String badgeText,
            String headline,
            String lede,
            String durationCompact,
            String startedAt,
            String finishedAt,
            String throughputAvg
    ) {
    }

    public record PlanHeroView(
            String badgeText,
            String headline,
            String lede,
            String totalPlanned,
            String totalDirect,
            String totalFkChildren,
            String totalFkParents,
            int tablesCount
    ) {
    }

    public record ExecutionMetaStripView(
            String sourceSchema,
            String targetSchema,
            String configPath,
            String ddlErrorMode,
            String fakerLocale
    ) {
    }

    public record PlanMetaStripView(
            String sourceSchema,
            String configPath,
            String ddlErrorMode,
            String fakerLocale
    ) {
    }

    public record ExecutionStatRowView(
            String extractedNum,
            String extractedDelta,
            String insertedNum,
            String insertedDelta,
            String conflictsNum,
            String conflictsDelta,
            String batchErrorsNum,
            String batchErrorsDelta,
            boolean conflictsWarn,
            boolean batchErrorsErr
    ) {
    }

    public record PlanStatRowView(
            String totalPlannedNum,
            String totalPlannedDelta,
            String piiCount,
            String piiDelta,
            String qidCount,
            String qidDelta,
            boolean piiWarn,
            boolean qidWarn
    ) {
    }

    public record ExecutionTimingV2View(
            String totalDuration,
            TimingPhaseView extraction,
            TimingPhaseView anonymization,
            TimingPhaseView write,
            String extractionFlex,
            String anonymizationFlex,
            String writeFlex
    ) {
    }

    public record TimingPhaseView(String label, String duration, String pctLabel) {
    }

    public record ExecutionCalloutView(String level, String tag, String message) {
    }

    public record PlanCalloutView(String level, String tag, String message) {
    }

    public record ExecutionStrategyCardView(String name, String description, String count) {
    }

    public record ExecutionDictV2View(
            String entries,
            String limit,
            String headroom,
            String usagePercent,
            List<ExecutionDictContributorView> topContributors,
            boolean aboveThreshold
    ) {
    }

    public record ExecutionDictContributorView(String semanticKey, String entries) {
    }

    public record ExecutionWarningSummaryView(
            String batchErrorsCount,
            String batchErrorsDesc,
            String ddlIgnoredCount,
            String ddlIgnoredDesc,
            String conflictsCount,
            String conflictsDesc
    ) {
    }

    public record ExecutionWarningBlocksView(
            List<BatchErrorRowView> batchErrors,
            List<DdlIgnoredRowView> ddlIgnored,
            List<ConflictRowView> conflicts
    ) {
    }

    public record BatchErrorRowView(String table, String batchCount, String unwrittenRows) {
    }

    public record DdlIgnoredRowView(
            String objectType,
            String statementPreview,
            String errorMessage,
            String statementNumber
    ) {
    }

    public record ConflictRowView(String table, String conflictCount, String resolution) {
    }

    public record ExecutionFooterView(
            String heapPeak,
            String throughputAvg,
            String heapAlert,
            String brumeVersion,
            String generatedAt
    ) {
    }

    public record PlanFooterView(String brumeVersion, String estimatedAt) {
    }

    public record PlanTableRowV2View(
            String table,
            String originLabel,
            boolean hasFkBadge,
            String plannedDirect,
            String plannedFkChildren,
            String plannedFkParents,
            String plannedTotal,
            String warningPill,
            boolean hasWarning
    ) {
    }

    public record PlanWarningRowView(
            String category,   // "PII" | "Quasi-ID"
            String qualifiedColumn,
            String detail,
            String strategy
    ) {
    }
}

