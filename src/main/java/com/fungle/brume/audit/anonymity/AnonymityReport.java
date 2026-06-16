package com.fungle.brume.audit.anonymity;

import java.time.Instant;
import java.util.List;

/**
 * Top-level audit report produced by {@link AnonymityAuditor} and consumed by the
 * three formatters (text / JSON / Markdown).
 *
 * <p>Ordered, immutable, format-agnostic — each renderer projects this record
 * into its own surface.
 *
 * @param schema     target schema audited
 * @param auditedAt  timestamp at which the audit started
 * @param tables     one entry per (table, quasi-id-set) pair audited
 * @param strict     whether {@code --strict} was specified (drives exit code)
 * @param kMin       threshold compared against {@link
 *                   EquivalenceClassDistribution#kMin()} when {@code strict=true}
 * @param overallKMin smallest {@code k} observed across all audited tables, or
 *                    {@code -1} if every table was empty
 */
public record AnonymityReport(
        String schema,
        Instant auditedAt,
        List<TableAuditResult> tables,
        boolean strict,
        long kMin,
        long overallKMin) {

    public AnonymityReport {
        tables = List.copyOf(tables);
    }

    /**
     * Returns {@code true} when {@link #strict()} is on AND either : (a) at least one
     * audited table has a {@code k_min} below the {@code --k-min} threshold, OR (b) no
     * equivalence class was observed at all ({@code overallKMin == -1} — every audited
     * table is empty).
     *
     * <p>Case (b) is the {@code #79e} fix : pre-fix, an empty audit (schema vide ou
     * tables vides) retournait silencieusement "policy OK" en strict mode → un attaquant
     * CI pouvait faire passer {@code --strict --k-min N} en pointant sur un schéma vide.
     * Désormais, strict mode rejette explicitement un audit vide — l'opérateur a demandé
     * une garantie et doit en obtenir une (ou un signal clair d'erreur).
     */
    public boolean policyViolated() {
        if (!strict) {
            return false;
        }
        if (overallKMin < 0) {
            // #79e — empty audit in strict mode is a policy violation, not "OK".
            return true;
        }
        return overallKMin < kMin;
    }
}
