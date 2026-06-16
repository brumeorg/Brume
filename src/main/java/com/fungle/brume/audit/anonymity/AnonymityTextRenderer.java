package com.fungle.brume.audit.anonymity;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders an {@link AnonymityReport} as a self-contained ASCII text block —
 * the default output of {@code brume audit --anonymity} on the console (and the
 * stdout payload when {@code --json} is NOT set).
 *
 * <p>Pure function : no I/O, no Spring, no state. The result string ends with
 * a newline so it can be passed directly to {@code System.out.print} or to a
 * dedicated {@code brume.output} logger line-by-line.
 */
public final class AnonymityTextRenderer {

    private static final String SEP_DOUBLE = "=".repeat(78);
    private static final String SEP_SINGLE = "-".repeat(78);

    private AnonymityTextRenderer() {}

    public static String render(AnonymityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(SEP_DOUBLE).append('\n');
        sb.append(" Brume — Audit anonymité (k-anonymity)\n");
        sb.append(SEP_DOUBLE).append('\n');
        sb.append(" Schéma cible : ").append(report.schema()).append('\n');
        sb.append(" Audité le    : ")
                .append(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .withZone(ZoneOffset.UTC).format(report.auditedAt()))
                .append('\n');
        sb.append(" Politique    : ");
        if (report.strict()) {
            sb.append("STRICT, k_min ≥ ").append(report.kMin());
        } else {
            sb.append("informational (no --strict)");
        }
        sb.append('\n');
        sb.append(" k_min global : ")
                .append(report.overallKMin() < 0 ? "n/a (aucune classe)" : report.overallKMin())
                .append('\n');
        sb.append(SEP_DOUBLE).append('\n');

        if (report.tables().isEmpty()) {
            sb.append(" Aucun (table, quasi-id) à auditer — vérifier --quasi-id ou --auto-detect-quasi-id.\n");
            sb.append(SEP_DOUBLE).append('\n');
            return sb.toString();
        }

        for (TableAuditResult t : report.tables()) {
            appendTable(sb, t);
        }

        appendMethodology(sb, report);
        return sb.toString();
    }

    private static void appendTable(StringBuilder sb, TableAuditResult t) {
        EquivalenceClassDistribution d = t.distribution();
        sb.append('\n');
        sb.append(" Table : ").append(t.table()).append('\n');
        sb.append(" Quasi-id : ").append(String.join(", ", t.quasiIdColumns())).append('\n');
        sb.append(SEP_SINGLE).append('\n');

        if (d.totalClasses() == 0) {
            sb.append("   (table vide — aucune classe d'équivalence)\n");
            sb.append(SEP_DOUBLE).append('\n');
            return;
        }

        sb.append(String.format(Locale.ROOT, "   k_min       : %d%n", d.kMin()));
        sb.append(String.format(Locale.ROOT, "   k_moyen     : %.2f%n", d.kAverage()));
        sb.append(String.format("   Lignes      : %d%n", d.totalRows()));
        sb.append(String.format("   Classes     : %d%n", d.totalClasses()));
        sb.append(SEP_SINGLE).append('\n');
        sb.append("   Distribution :\n");
        sb.append(String.format(Locale.ROOT, "     k = 1        : %d  (singletons)%n", d.singletons()));
        sb.append(String.format(Locale.ROOT, "     k = 2..4     : %d%n", d.k2to4()));
        sb.append(String.format(Locale.ROOT, "     k = 5..9     : %d%n", d.k5to9()));
        sb.append(String.format(Locale.ROOT, "     k = 10..99   : %d%n", d.k10to99()));
        sb.append(String.format(Locale.ROOT, "     k ≥ 100      : %d%n", d.k100plus()));

        if (!t.singletons().isEmpty()) {
            sb.append(SEP_SINGLE).append('\n');
            sb.append("   Singletons (max ").append(KAnonymityCalculator.SINGLETON_LIMIT)
                    .append(") :\n");
            for (SingletonRow r : t.singletons()) {
                sb.append("     - (").append(String.join(", ", r.values())).append(")\n");
            }
        }

        if (!t.recommendations().isEmpty()) {
            sb.append(SEP_SINGLE).append('\n');
            sb.append("   Recommandations :\n");
            for (Recommendation r : t.recommendations()) {
                sb.append("     [").append(r.severity()).append("] ").append(r.message()).append('\n');
            }
        }

        if (t.sampleRate() < 1.0) {
            sb.append(SEP_SINGLE).append('\n');
            sb.append(String.format(Locale.ROOT, "   Échantillon : %.2f%% (TABLESAMPLE SYSTEM)%n",
                    t.sampleRate() * 100.0));
        }
        sb.append(SEP_DOUBLE).append('\n');
    }

    private static void appendMethodology(StringBuilder sb, AnonymityReport report) {
        sb.append('\n');
        sb.append(" Méthodologie & limites\n");
        sb.append(SEP_SINGLE).append('\n');
        sb.append("   • k-anonymity = taille minimum d'une classe d'équivalence sur les colonnes quasi-id.\n");
        sb.append("   • Seuil k≥5 = standard académique (Sweeney 2002), repris par CNIL (2020), ICO.\n");
        sb.append("   • NULL est traité comme une valeur normale (sémantique SQL GROUP BY).\n");
        sb.append("   • k-anonymity ne couvre QUE le singling out (Article 4.5 RGPD).\n");
        sb.append("     Linkability et inference restent ouverts — l-diversity et t-closeness\n");
        sb.append("     ne sont pas mesurés en V1.\n");
        sb.append("   • Considérant 26 RGPD : ce rapport documente une pseudonymisation forte.\n");
        sb.append("     La donnée reste personnelle au sens RGPD si k > 1 reste réversible par\n");
        sb.append("     un attaquant qui possède un dataset auxiliaire.\n");
        if (report.tables().stream().anyMatch(t -> t.sampleRate() < 1.0)) {
            sb.append("   • Échantillonnage TABLESAMPLE SYSTEM utilisé sur au moins une table —\n");
            sb.append("     biais possible : les classes rares peuvent être manquées.\n");
            sb.append("     Pour un audit officiel DPO, relancer en pleine étendue (--sample-rate=1).\n");
        }
        sb.append(SEP_DOUBLE).append('\n');
    }
}
