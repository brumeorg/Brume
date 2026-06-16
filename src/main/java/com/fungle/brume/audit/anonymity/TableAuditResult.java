package com.fungle.brume.audit.anonymity;

import java.util.List;

/**
 * Audit result for a single (table, quasi-id-set) pair.
 *
 * @param table             table audited
 * @param quasiIdColumns    ordered list of quasi-id column names that defined the
 *                          equivalence classes
 * @param distribution      bucketed distribution of class sizes
 * @param singletons        sample of singleton rows (at most {@code SINGLETON_LIMIT}
 *                          per ticket #73), used by the report's "Classes singletons"
 *                          section. Empty when {@link
 *                          EquivalenceClassDistribution#singletons()} = 0.
 * @param sampleRate        fraction of the source table actually scanned ({@code 1.0}
 *                          when full scan, &lt;1.0 when {@code --sample-rate} was set).
 *                          Used in the methodology section to disclose the sampling
 *                          bias.
 * @param recommendations   table-level recommendations derived from the distribution
 */
public record TableAuditResult(
        String table,
        List<String> quasiIdColumns,
        EquivalenceClassDistribution distribution,
        List<SingletonRow> singletons,
        double sampleRate,
        List<Recommendation> recommendations) {

    public TableAuditResult {
        quasiIdColumns = List.copyOf(quasiIdColumns);
        singletons = List.copyOf(singletons);
        recommendations = List.copyOf(recommendations);
    }
}
