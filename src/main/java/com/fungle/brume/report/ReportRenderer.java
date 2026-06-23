package com.fungle.brume.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.config.BrumeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Spring component responsible for rendering an {@link ExecutionSummary} to the console
 * (as a structured ASCII table logged at INFO level), optionally to a JSON file, and
 * optionally to a self-contained HTML file via {@link HtmlReportRenderer}.
 *
 * <p>Constructor-injected — requires {@link BrumeProperties}, a Jackson
 * {@link ObjectMapper} bean, and an {@link HtmlReportRenderer}.
 */
@Component
public class ReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(ReportRenderer.class);

    /**
     * Dedicated logger for user-facing run results (plan, execution summary, comparison).
     * Configured in {@code logback-spring.xml} at INFO level with {@code additivity=false}
     * so the report survives {@code --quiet} (which lowers the root level to ERROR).
     */
    private static final Logger output = LoggerFactory.getLogger("brume.output");

    private static final String SEP_DOUBLE = "===========================================================";
    private static final String SEP_SINGLE = "-----------------------------------------------------------";

    private final BrumeProperties brumeProperties;
    private final ObjectMapper objectMapper;
    private final HtmlReportRenderer htmlReportRenderer;

    /**
     * Creates a new {@code ReportRenderer}.
     *
     * @param brumeProperties      application properties, used to read the optional output paths
     * @param objectMapper         Jackson mapper used for JSON serialization
     * @param htmlReportRenderer   generates the optional HTML report file
     */
    public ReportRenderer(BrumeProperties brumeProperties, ObjectMapper objectMapper,
                          HtmlReportRenderer htmlReportRenderer) {
        this.brumeProperties = brumeProperties;
        this.objectMapper = objectMapper;
        this.htmlReportRenderer = htmlReportRenderer;
    }

    /**
     * Renders the given summary to the log (INFO level), optionally to a JSON file,
     * and optionally to a self-contained HTML file.
     *
     * <p>A failure to write any output file is logged as WARN and does not propagate —
     * the pipeline is never aborted for a reporting error.
     *
     * @param summary the immutable execution snapshot to render
     */
    public void render(ExecutionSummary summary) {
        String text = renderText(summary);
        // Emit each line on the dedicated `brume.output` logger so the report survives
        // --quiet (root lowered to ERROR) per ADR-0030.
        for (String line : text.split("\n")) {
            output.info("{}", line);
        }

        // Write JSON file if configured
        String jsonFile = brumeProperties.report().jsonFile();
        if (jsonFile != null && !jsonFile.isBlank()) {
            try {
                java.nio.file.Path jsonPath = java.nio.file.Path.of(jsonFile);
                java.nio.file.Path parent = jsonPath.getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(jsonPath.toFile(), summary);
                log.info("Report written to {}", jsonFile);
            } catch (IOException e) {
                log.warn("Failed to write JSON report to {}: {}", jsonFile, e.getMessage());
            }
        }

        // Write HTML file if configured
        try {
            htmlReportRenderer.render(summary);
        } catch (Exception e) {
            log.warn("Failed to render HTML report: {}", e.getMessage());
        }
    }

    /**
     * Produces the full ASCII text representation of the given execution summary.
     *
     * <p>The returned string is self-contained and can be logged, printed, or used in
     * unit tests without any Spring context.
     *
     * @param summary the immutable execution snapshot to render
     * @return multi-line ASCII report string (lines separated by {@code \n})
     */
    public String renderText(ExecutionSummary summary) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append(SEP_DOUBLE).append('\n');
        sb.append(" Brume — Rapport d'exécution\n");
        sb.append(SEP_DOUBLE).append('\n');

        String status = summary.success() ? "SUCCÈS" : "ÉCHEC";
        sb.append(" Statut       : ").append(status).append('\n');

        if (!summary.success() && summary.failureCause() != null) {
            sb.append(" Cause        : ").append(summary.failureCause()).append('\n');
        }

        sb.append(" Schéma       : ")
                .append(summary.sourceSchema())
                .append(" → ")
                .append(summary.targetSchema())
                .append('\n');

        PkStructureStats pk = summary.pkStructure();
        if (pk.tablesWithCompositePk() > 0 || pk.tablesWithoutPk() > 0) {
            sb.append(" Clés         : ")
                    .append(pk.tablesWithCompositePk()).append(" table(s) à PK composite, ")
                    .append(pk.tablesWithoutPk()).append(" sans PK\n");
        }

        String startTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(summary.startedAt());
        sb.append(" Démarré le   : ").append(startTime).append('\n');
        sb.append(" Durée totale : ").append(formatMs(summary.timings().totalMs())).append(" ms\n");

        // Per-table stats
        sb.append(SEP_SINGLE).append('\n');
        sb.append(String.format(" %-16s | %9s | %10s | %8s | %8s | %7s%n",
                "Table", "Extraites", "FK Parents", "Insérées", "Conflits", "Erreurs"));
        sb.append(SEP_SINGLE).append('\n');

        List<TableStats> stats = summary.tableStats();
        for (TableStats ts : stats) {
            sb.append(String.format(" %-16s | %9s | %10s | %8s | %8s | %7s%n",
                    ts.table(),
                    formatMs(ts.extracted()),
                    formatMs(ts.fkParents()),
                    formatMs(ts.inserted()),
                    formatMs(ts.conflicts()),
                    formatMs(ts.batchErrors())));
        }

        sb.append(SEP_SINGLE).append('\n');
        sb.append(String.format(" %-16s | %9s | %10s | %8s | %8s | %7s%n",
                "TOTAL",
                formatMs(summary.tableStats().stream().mapToLong(TableStats::extracted).sum()),
                formatMs(summary.tableStats().stream().mapToLong(TableStats::fkParents).sum()),
                formatMs(summary.totalInserted()),
                formatMs(summary.totalConflicts()),
                formatMs(summary.totalBatchErrors())));

        // Phase timings
        sb.append(SEP_DOUBLE).append('\n');
        sb.append(" Durées par phase :\n");
        sb.append("   Extraction    : ").append(formatMs(summary.timings().extractionMs())).append(" ms\n");
        sb.append("   Anonymisation : ").append(formatMs(summary.timings().anonymizationMs())).append(" ms\n");
        sb.append("   Écriture      : ").append(formatMs(summary.timings().writeMs())).append(" ms\n");

        // Strategy summary — group by strategy name, count distinct columns per strategy
        sb.append(SEP_DOUBLE).append('\n');
        sb.append(" Stratégies appliquées :\n");

        // Group: strategy → set of "table.column" pairs
        Map<String, Long> distinctColumnsByStrategy = new LinkedHashMap<>();
        summary.strategyUsages().stream()
                .collect(Collectors.groupingBy(StrategyUsage::strategy,
                        Collectors.mapping(u -> u.table() + "." + u.column(),
                                Collectors.toSet())))
                .entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, java.util.Set<String>> e) ->
                        e.getValue().size()).reversed())
                .forEach(e -> distinctColumnsByStrategy.put(e.getKey(), (long) e.getValue().size()));

        for (Map.Entry<String, Long> entry : distinctColumnsByStrategy.entrySet()) {
            String strategy = entry.getKey();
            long colCount = entry.getValue();
            String label = "KEEP".equalsIgnoreCase(strategy)
                    ? "colonnes copiées telles quelles"
                    : "colonnes anonymisées";
            sb.append(String.format("   %-7s : %d %s%n", strategy, colCount, label));
        }

        if (summary.substitutionDict().hasLimit()) {
            sb.append(SEP_DOUBLE).append('\n');
            sb.append(" Dictionnaire de substitution :\n");
            sb.append("   Entrées       : ").append(formatMs(summary.substitutionDict().entries())).append('\n');
            sb.append("   Limite        : ").append(formatMs(summary.substitutionDict().limit())).append('\n');
            sb.append("   Utilisation   : ").append(summary.substitutionDict().usagePercent()).append("%\n");
            if (summary.substitutionDict().hasTopContributors()) {
                sb.append("   Top contributeurs : ")
                        .append(formatTopContributors(summary.substitutionDict().topContributors()))
                        .append('\n');
            }
        }

        if (summary.heap().hasMax()) {
            sb.append(SEP_DOUBLE).append('\n');
            sb.append(" Heap JVM :\n");
            sb.append("   Pic observé   : ")
                    .append(formatBytes(summary.heap().peakUsedBytes()))
                    .append(" / ")
                    .append(formatBytes(summary.heap().maxBytes()))
                    .append(" (")
                    .append(summary.heap().peakUsagePercent())
                    .append("%)\n");
            if (summary.heap().warningTriggered()) {
                sb.append("   Alerte        : seuil ")
                        .append(summary.heap().warningThresholdPercent())
                        .append("% franchi pendant l'exécution\n");
            }
        }

        // Warnings
        if (summary.hasWarnings()) {
            sb.append(SEP_DOUBLE).append('\n');
            if (summary.totalConflicts() > 0) {
                sb.append(String.format(" [WARN] %s conflit(s) ignorés (ON CONFLICT DO NOTHING)%n",
                        formatMs(summary.totalConflicts())));
            }
            if (summary.totalBatchErrors() > 0) {
                sb.append(String.format(" [WARN] %s lot(s) en erreur (lignes non insérées)%n",
                        formatMs(summary.totalBatchErrors())));
            }
        }

        // DDL ignored under LENIENT mode (#28 / A17)
        if (!summary.ddlFailures().isEmpty()) {
            sb.append(SEP_DOUBLE).append('\n');
            sb.append(String.format(" [WARN] %d DDL statement(s) ignorés (LENIENT mode) :%n",
                    summary.ddlFailures().size()));
            for (DdlFailure f : summary.ddlFailures()) {
                sb.append(String.format("   #%d %s%n      → %s%n",
                        f.statementIndex(), f.sqlPreview(), f.errorMessage()));
            }
        }

        sb.append(SEP_DOUBLE);
        return sb.toString();
    }

    /**
     * Renders the pre-execution plan as a structured ASCII table logged at INFO level,
     * and optionally to a self-contained HTML file via {@link HtmlReportRenderer}.
     *
     * <p>Displays the estimated row counts per table and any PII warnings.
     * Used both in {@code --plan} dry-run mode and (in OBS-7) alongside the execution summary.
     *
     * @param planSummary the immutable pre-execution plan snapshot to render
     */
    public void renderPlan(PlanSummary planSummary) {
        String text = renderPlanText(planSummary);
        for (String line : text.split("\n")) {
            output.info("{}", line);
        }
        try {
            htmlReportRenderer.renderPlan(planSummary);
        } catch (Exception e) {
            log.warn("Failed to render HTML plan report: {}", e.getMessage());
        }
    }

    /**
     * Produces the full ASCII text representation of the given plan summary.
     *
     * <p>The returned string is self-contained and can be logged, printed, or used in
     * unit tests without any Spring context.
     *
     * @param planSummary the immutable pre-execution plan snapshot to render
     * @return multi-line ASCII report string (lines separated by {@code \n})
     */
    public String renderPlanText(PlanSummary planSummary) {
        StringBuilder sb = new StringBuilder();

        sb.append(SEP_DOUBLE).append('\n');
        sb.append(" Brume — Plan d'exécution (pré-vol)\n");
        sb.append(SEP_DOUBLE).append('\n');

        String estimatedAt = planSummary.estimatedAt() != null
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(planSummary.estimatedAt())
                : "N/A";

        sb.append(" Schéma source   : ").append(planSummary.sourceSchema()).append('\n');
        sb.append(" Estimé le       : ").append(estimatedAt).append('\n');
        sb.append(SEP_SINGLE).append('\n');
        sb.append(String.format(" %-20s | %9s | %s%n", "Table", "Planifié", "Origine"));
        sb.append(SEP_SINGLE).append('\n');

        long grandTotal = 0L;
        for (PlanTableStats ts : planSummary.tableStats()) {
            long total = ts.plannedTotal();
            String totalStr = (total < 0) ? "erreur" : formatMs(total);
            if (total > 0) {
                grandTotal += total;
            }
            String originLabel = formatOrigin(ts.origin());
            sb.append(String.format(" %-20s | %9s | %s%n", ts.table(), totalStr, originLabel));
        }

        sb.append(SEP_SINGLE).append('\n');
        sb.append(String.format(" %-20s | %9s |%n", "TOTAL", formatMs(grandTotal)));
        sb.append(SEP_DOUBLE).append('\n');

        // PII warnings
        if (planSummary.hasPiiWarnings()) {
            for (PiiWarning w : planSummary.piiWarnings()) {
                sb.append(String.format(" [WARN PII] %s.%s — aucune règle (pattern: %s)%n",
                        w.table(), w.column(), w.matchedPattern()));
            }
            sb.append(SEP_DOUBLE).append('\n');
        }

        // Quasi-identifier warnings (#21c, ADR-0035)
        if (planSummary.hasQuasiIdWarnings()) {
            for (QuasiIdWarning w : planSummary.quasiIdWarnings()) {
                String strategyLabel = (w.effectiveStrategy() == null)
                        ? "KEEP implicite"
                        : w.effectiveStrategy().name();
                sb.append(String.format(
                        " [QUASI-ID] %s.%s (%s, %s) — pattern: %s — corrélation préservée%n",
                        w.table(), w.column(), w.dataType(), strategyLabel, w.matchedPattern()));
            }
            sb.append(SEP_DOUBLE);
        } else if (planSummary.hasPiiWarnings()) {
            // PII section already appended a SEP_DOUBLE+newline above; trim the trailing
            // newline so the final string ends with SEP_DOUBLE only (existing contract).
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    /**
     * Renders the plan vs actual comparison as a structured ASCII table logged at INFO level.
     *
     * <p>A failure during rendering is caught and logged as WARN — the pipeline is never aborted
     * for a reporting error.
     *
     * @param comparison the immutable comparison snapshot to render
     */
    public void renderComparison(ComparisonSummary comparison) {
        try {
            String text = renderComparisonText(comparison);
            for (String line : text.split("\n")) {
                output.info("{}", line);
            }
            try {
                htmlReportRenderer.renderComparison(comparison);
            } catch (Exception e) {
                log.warn("Failed to render HTML comparison section: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("renderComparison failed: {}", e.getMessage());
        }
    }

    /**
     * Produces the full ASCII text representation of the plan vs actual comparison.
     *
     * <p>The returned string is self-contained and can be logged, printed, or used in
     * unit tests without any Spring context.
     *
     * @param comparison the immutable comparison snapshot to render
     * @return multi-line ASCII report string (lines separated by {@code \n})
     */
    public String renderComparisonText(ComparisonSummary comparison) {
        StringBuilder sb = new StringBuilder();

        sb.append(SEP_DOUBLE).append('\n');
        sb.append(" Brume — Plan vs Réel\n");
        sb.append(SEP_DOUBLE).append('\n');

        sb.append(String.format(" %-16s | %9s | %8s | %5s | %9s | %9s%n",
                "Table", "Planifié", "Réel", "Δ", "Insérées", "Conflits"));
        sb.append(SEP_SINGLE).append('\n');

        for (ComparisonRow row : comparison.rows()) {
            String deltaStr = formatDelta(row.deltaPercent());
            String icon = statusIcon(row);
            sb.append(String.format(" %-16s | %9s | %8s | %5s | %9s | %9s  %s%n",
                    row.table(),
                    formatMs(row.planned()),
                    formatMs(row.actual()),
                    deltaStr,
                    formatMs(row.inserted()),
                    formatMs(row.conflicts()),
                    icon));
        }

        sb.append(SEP_SINGLE).append('\n');

        // TOTAL row — delta on totals
        long totalPlanned = comparison.totalPlanned();
        long totalActual  = comparison.totalActual();
        long totalInserted = comparison.rows().stream().mapToLong(ComparisonRow::inserted).sum();
        long totalConflicts = comparison.rows().stream().mapToLong(ComparisonRow::conflicts).sum();
        double totalDelta = totalPlanned > 0
                ? ((totalActual - totalPlanned) / (double) totalPlanned) * 100.0 : 0.0;

        sb.append(String.format(" %-16s | %9s | %8s | %5s | %9s | %9s%n",
                "TOTAL",
                formatMs(totalPlanned),
                formatMs(totalActual),
                formatDelta(totalDelta),
                formatMs(totalInserted),
                formatMs(totalConflicts)));

        sb.append(SEP_DOUBLE).append('\n');

        // PII warnings
        if (comparison.hasPiiWarnings()) {
            for (PiiWarning w : comparison.piiWarnings()) {
                sb.append(String.format(" [WARN PII] %s.%s — aucune règle (pattern: %s)%n",
                        w.table(), w.column(), w.matchedPattern()));
            }
            sb.append(SEP_DOUBLE).append('\n');
        }

        // Natural-language observations
        if (comparison.hasInsights()) {
            sb.append(" Observations :\n");
            for (Insight insight : comparison.insights()) {
                sb.append(String.format("  %s %s%n", insight.icon(), insight.message()));
            }
            sb.append(SEP_DOUBLE);
        }

        return sb.toString();
    }

    /**
     * Formats a delta percentage with a mandatory sign, rounding to the nearest integer.
     * Preserves the sign for values that round to zero (e.g. {@code -0.06%} → {@code "-0%"}).
     *
     * @param d the raw delta percentage (may be negative)
     * @return formatted string, e.g. {@code "+0%"}, {@code "-1%"}, {@code "+12%"}
     */
    static String formatDelta(double d) {
        // #79h — unify with ReportTemplateModelFactory.formatDelta : "0%" (no leading space).
        // Pre-fix returned " 0%" with a leading space for tabular alignment, but the call
        // sites already use %5s right-aligned padding — the explicit space was redundant
        // and caused a string-mismatch with the HTML rendering path on the same value.
        if (Double.isNaN(d) || d == 0.0) return "0%";
        int rounded = (int) Math.round(d);
        if (rounded == 0) {
            return d < 0 ? "-0%" : "+0%";
        }
        return String.format("%+d%%", rounded);
    }

    /**
     * Returns the status icon for a comparison row.
     * {@code ✓} if conflicts == 0 and |delta| &le; 1%, {@code ⚠} otherwise.
     *
     * @param row the comparison row to evaluate
     * @return {@code "✓"} or {@code "⚠"}
     */
    private static String statusIcon(ComparisonRow row) {
        boolean ok = row.conflicts() == 0 && Math.abs(row.deltaPercent()) <= 1.0;
        return ok ? "✓" : "⚠";
    }

    /**
     * Formats an origin string to a human-readable label.
     *
     * @param origin one of {@code "direct"}, {@code "fk-parent"}, or {@code "direct+fk-parent"}
     * @return a human-readable label
     */
    private static String formatOrigin(String origin) {
        return switch (origin) {
            case "direct" -> "direct";
            case "fk-parent" -> "FK parent";
            case "direct+fk-parent" -> "direct + FK parent";
            default -> origin;
        };
    }

    /**
     * Formats a long value with a space as thousands separator.
     *
     * @param value the value to format
     * @return formatted string, e.g. {@code "2 104"} for {@code 2104L}
     */
    private static String formatMs(long value) {
        // Force US locale to get comma as grouping separator, then replace with space
        // for a consistent, locale-neutral thousands separator in the report output.
        return String.format(java.util.Locale.US, "%,d", value).replace(',', ' ');
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(java.util.Locale.US, "%.1f KiB", kib);
        }

        double mib = kib / 1024.0;
        if (mib < 1024) {
            return String.format(java.util.Locale.US, "%.1f MiB", mib);
        }

        double gib = mib / 1024.0;
        return String.format(java.util.Locale.US, "%.2f GiB", gib);
    }

    private static String formatTopContributors(List<SubstitutionDictStats.TopContributor> contributors) {
        StringJoiner joiner = new StringJoiner(", ");
        for (SubstitutionDictStats.TopContributor contributor : contributors) {
            joiner.add(contributor.semanticKey() + "=" + formatMs(contributor.entries()));
        }
        return joiner.toString();
    }
}


