package com.fungle.brume.audit.anonymity;

import java.util.List;

/**
 * Bucketed distribution of equivalence classes by size (k-anonymity).
 *
 * <p>An equivalence class is a set of rows that share the same values on every
 * quasi-identifier column. Its size {@code k} is the count of rows in that class —
 * the smaller {@code k}, the easier the re-identification by singling out.
 *
 * <p>Buckets follow the ticket #73 spec : {@code k=1} (singletons), {@code k=2-4},
 * {@code k=5-9}, {@code k=10-99}, {@code k≥100}. {@code k_min} (the size of the
 * smallest class) and {@code k_average} are reported separately for at-a-glance
 * synthesis.
 *
 * @param totalClasses     total number of equivalence classes (sum of all buckets)
 * @param totalRows        total number of rows audited (sum over classes of k)
 * @param singletons       count of classes with k=1 (the headline risk number)
 * @param k2to4             count of classes with 2 ≤ k ≤ 4
 * @param k5to9             count of classes with 5 ≤ k ≤ 9
 * @param k10to99           count of classes with 10 ≤ k ≤ 99
 * @param k100plus          count of classes with k ≥ 100
 * @param kMin             smallest equivalence-class size observed (≥ 1, or {@code -1}
 *                         if no class was found — empty table)
 * @param kAverage         arithmetic mean of class sizes (= totalRows / totalClasses)
 */
public record EquivalenceClassDistribution(
        long totalClasses,
        long totalRows,
        long singletons,
        long k2to4,
        long k5to9,
        long k10to99,
        long k100plus,
        long kMin,
        double kAverage) {

    /** Empty distribution — used when the target table has no rows. */
    public static EquivalenceClassDistribution empty() {
        return new EquivalenceClassDistribution(0, 0, 0, 0, 0, 0, 0, -1L, 0.0);
    }

    /**
     * Builds a distribution from per-class sizes. {@code classSizes} must contain at
     * least one element when the table is non-empty ; if the list is empty, returns
     * {@link #empty()}.
     */
    public static EquivalenceClassDistribution from(List<Long> classSizes) {
        if (classSizes == null || classSizes.isEmpty()) {
            return empty();
        }
        long singletons = 0, b24 = 0, b59 = 0, b1099 = 0, b100p = 0;
        long total = 0;
        long minK = Long.MAX_VALUE;
        for (long k : classSizes) {
            total += k;
            if (k < minK) minK = k;
            if (k == 1L) singletons++;
            else if (k <= 4L) b24++;
            else if (k <= 9L) b59++;
            else if (k <= 99L) b1099++;
            else b100p++;
        }
        long classes = classSizes.size();
        double avg = (double) total / (double) classes;
        return new EquivalenceClassDistribution(classes, total, singletons,
                b24, b59, b1099, b100p, minK, avg);
    }
}
