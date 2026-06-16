package com.fungle.brume.report;

import java.util.List;

/**
 * Immutable snapshot of the substitution dictionary usage at a given point in time.
 *
 * @param entries         current number of distinct dictionary entries
 * @param limit           configured maximum number of entries allowed
 * @param topContributors top semantic keys by number of created entries
 */
public record SubstitutionDictStats(
        long entries,
        long limit,
        List<TopContributor> topContributors
) {

    public SubstitutionDictStats {
        topContributors = topContributors == null ? List.of() : List.copyOf(topContributors);
    }

    public static SubstitutionDictStats empty() {
        return new SubstitutionDictStats(0L, 0L, List.of());
    }

    public boolean hasLimit() {
        return limit > 0;
    }

    public boolean hasTopContributors() {
        return !topContributors.isEmpty();
    }

    public int usagePercent() {
        if (limit <= 0) {
            return 0;
        }
        return (int) Math.round((entries * 100.0) / limit);
    }

    public record TopContributor(String semanticKey, long entries) {
    }
}

