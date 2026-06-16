package com.fungle.brume.audit.anonymity;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Renders an {@link AnonymityReport} as a self-contained Markdown document
 * targeted at a Data Protection Officer (DPO) — the main differentiator vs
 * Greenmask, postgresql_anonymizer, Datanymizer (#73 / ADR-0036).
 *
 * <p>The structure is fixed in V1 ({@link Locale#FRENCH}) and aligned on the
 * sections a DPO expects in an anonymization-quality dossier :
 * <ol>
 *   <li>Synthèse — k_min global, verdict policy, tables auditées.</li>
 *   <li>Distribution — un tableau Markdown par table avec les 5 buckets.</li>
 *   <li>Classes singletons — listing brut des rows uniquement-identifiables.</li>
 *   <li>Recommandations — sortées par severity (CRITICAL → INFO).</li>
 *   <li>Méthodologie &amp; limites — citations RGPD obligatoires + sampling biais.</li>
 * </ol>
 *
 * <p>Pure function : no I/O, no state. Output is UTF-8 Markdown ready to paste
 * into a Confluence page, send as PDF, or commit to a git repo.
 */
public final class AnonymityMarkdownRenderer {

    private AnonymityMarkdownRenderer() {}

    public static String render(AnonymityReport report) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Audit anonymité — k-anonymity\n\n");
        sb.append("| Champ | Valeur |\n");
        sb.append("|---|---|\n");
        sb.append("| Schéma cible | `").append(report.schema()).append("` |\n");
        sb.append("| Audité le | ")
                .append(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .withZone(ZoneOffset.UTC).format(report.auditedAt()))
                .append(" |\n");
        sb.append("| Politique | ");
        if (report.strict()) {
            sb.append("**STRICT**, seuil `k_min ≥ ").append(report.kMin()).append("`");
        } else {
            sb.append("Informational (sans `--strict`)");
        }
        sb.append(" |\n");
        sb.append("| k_min global | ");
        if (report.overallKMin() < 0) {
            sb.append("_n/a (aucune classe)_");
        } else {
            sb.append("**").append(report.overallKMin()).append("**");
        }
        sb.append(" |\n");
        sb.append("| Verdict | ");
        if (report.policyViolated()) {
            sb.append(":x: **POLICY VIOLÉE** — au moins une table a `k_min < ")
                    .append(report.kMin()).append("`");
        } else if (report.strict()) {
            sb.append(":white_check_mark: Conforme à la politique");
        } else {
            sb.append("_informationnel_");
        }
        sb.append(" |\n\n");

        if (report.tables().isEmpty()) {
            sb.append("> :warning: Aucun couple (table, quasi-id) audité. ")
                    .append("Vérifier `--quasi-id` ou `--auto-detect-quasi-id`.\n\n");
        }

        sb.append("## Synthèse par table\n\n");
        sb.append("| Table | Quasi-id | k_min | k_moyen | Lignes | Classes | Singletons |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (TableAuditResult t : report.tables()) {
            EquivalenceClassDistribution d = t.distribution();
            sb.append("| `").append(t.table()).append("` ");
            sb.append("| `").append(String.join(", ", t.quasiIdColumns())).append("` ");
            sb.append("| ").append(d.totalClasses() == 0 ? "_vide_" : String.valueOf(d.kMin())).append(" ");
            sb.append("| ").append(d.totalClasses() == 0 ? "—" : String.format(java.util.Locale.FRENCH, "%.2f", d.kAverage())).append(" ");
            sb.append("| ").append(d.totalRows()).append(" ");
            sb.append("| ").append(d.totalClasses()).append(" ");
            sb.append("| ").append(d.singletons() == 0 ? "0" : ":x: **" + d.singletons() + "**").append(" |\n");
        }
        sb.append('\n');

        sb.append("## Distribution\n\n");
        for (TableAuditResult t : report.tables()) {
            EquivalenceClassDistribution d = t.distribution();
            sb.append("### `").append(t.table()).append("`\n\n");
            if (d.totalClasses() == 0) {
                sb.append("_Table vide — aucune classe d'équivalence._\n\n");
                continue;
            }
            sb.append("| Bucket | Nombre de classes |\n");
            sb.append("|---|---|\n");
            sb.append("| `k = 1` (singletons) | ").append(d.singletons()).append(" |\n");
            sb.append("| `k = 2..4` | ").append(d.k2to4()).append(" |\n");
            sb.append("| `k = 5..9` | ").append(d.k5to9()).append(" |\n");
            sb.append("| `k = 10..99` | ").append(d.k10to99()).append(" |\n");
            sb.append("| `k ≥ 100` | ").append(d.k100plus()).append(" |\n\n");
            if (t.sampleRate() < 1.0) {
                sb.append("> :information_source: Échantillon : ")
                        .append(String.format(java.util.Locale.ROOT, "%.2f%%", t.sampleRate() * 100.0))
                        .append(" (`TABLESAMPLE SYSTEM`)\n\n");
            }
        }

        boolean anySingleton = report.tables().stream().anyMatch(t -> !t.singletons().isEmpty());
        if (anySingleton) {
            sb.append("## Classes singletons\n\n");
            sb.append("Lignes uniquement identifiables (max ")
                    .append(KAnonymityCalculator.SINGLETON_LIMIT).append(" par table).\n\n");
            for (TableAuditResult t : report.tables()) {
                if (t.singletons().isEmpty()) continue;
                sb.append("### `").append(t.table()).append("`\n\n");
                sb.append("| ").append(String.join(" | ", t.quasiIdColumns())).append(" |\n");
                sb.append("|").append("---|".repeat(t.quasiIdColumns().size())).append("\n");
                for (SingletonRow r : t.singletons()) {
                    sb.append("| ").append(String.join(" | ", r.values())).append(" |\n");
                }
                sb.append('\n');
            }
        }

        sb.append("## Recommandations\n\n");
        boolean anyRec = false;
        for (TableAuditResult t : report.tables()) {
            for (Recommendation r : t.recommendations()) {
                anyRec = true;
                sb.append("- **[").append(r.severity()).append("]** ")
                        .append(r.message()).append('\n');
            }
        }
        if (!anyRec) sb.append("_Aucune recommandation._\n");
        sb.append('\n');

        sb.append("## Méthodologie & limites\n\n");
        sb.append("- **k-anonymity** : taille minimum d'une classe d'équivalence sur les colonnes quasi-id\n");
        sb.append("  (Sweeney, 2002). Une classe de taille 1 = une ligne uniquement identifiable.\n");
        sb.append("- **Seuil k ≥ 5** : standard académique, repris par la CNIL (Guide pratique\n");
        sb.append("  d'anonymisation, 2020) et l'ICO (Anonymisation Code of Practice).\n");
        sb.append("- **NULL** est traité comme une valeur normale (`GROUP BY` SQL). Une classe\n");
        sb.append("  composée uniquement de NULLs est une classe d'équivalence comme une autre.\n");
        sb.append("- **Limites RGPD** : k-anonymity ne couvre QUE le _singling out_ (Article 4.5).\n");
        sb.append("  Les risques de _linkability_ et d'_inference_ restent ouverts — l-diversity\n");
        sb.append("  et t-closeness ne sont pas mesurés en V1 de Brume.\n");
        sb.append("- **Considérant 26 RGPD** : ce rapport documente une **pseudonymisation forte**.\n");
        sb.append("  Le dataset cible reste **donnée personnelle** au sens RGPD si un attaquant\n");
        sb.append("  possède un dataset auxiliaire permettant la ré-identification.\n");
        if (report.tables().stream().anyMatch(t -> t.sampleRate() < 1.0)) {
            sb.append("- **Échantillonnage** : `TABLESAMPLE SYSTEM` utilisé sur au moins une table.\n");
            sb.append("  Biais possible — les classes rares peuvent être manquées par l'échantillon.\n");
            sb.append("  Pour un audit officiel DPO, relancer avec `--sample-rate=1` (pleine étendue).\n");
        }
        sb.append("- **Auditeur** : généré par Brume (https://github.com/...) — sous-commande `audit --anonymity`.\n");

        return sb.toString();
    }
}
