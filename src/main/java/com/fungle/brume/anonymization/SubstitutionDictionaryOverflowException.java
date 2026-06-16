package com.fungle.brume.anonymization;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.WriteException;
import com.fungle.brume.report.SubstitutionDictStats;

import java.util.List;

/**
 * Exception thrown when the {@link SubstitutionDictionary} exceeds its configured
 * maximum size ({@code brume.substitution-dict.max-entries}).
 *
 * <p>This is a fail-safe mechanism to prevent out-of-memory errors on datasets with
 * millions of unique values. No eviction strategy is implemented because evicting
 * entries would break the cross-table determinism guarantee (the core purpose of
 * the dictionary).
 *
 * <p>Users encountering this exception must either:
 * <ul>
 *   <li>Increase {@code brume.substitution-dict.max-entries} if heap permits, or</li>
 *   <li>Reduce the dataset volume (e.g. stricter filters in {@code config.yaml}), or</li>
 *   <li>Reduce the scope of {@code linked_columns} (fewer semantic keys → less dictionary pressure).</li>
 * </ul>
 */
public class SubstitutionDictionaryOverflowException extends WriteException {

    /**
     * Constructs a new exception for a dictionary overflow.
     *
     * @param current the current number of entries in the dictionary
     * @param max     the configured maximum number of entries allowed
     */
    public SubstitutionDictionaryOverflowException(long current, long max) {
        this(current, max, List.of());
    }

    public SubstitutionDictionaryOverflowException(long current,
                                                   long max,
                                                   List<SubstitutionDictStats.TopContributor> topContributors) {
        super(BrumeErrorCode.WRITE_DICT_OVERFLOW,
                buildMessage(current, max, topContributors),
                "Either raise brume.substitution-dict.max-entries (heap permitting) or reduce "
                        + "the dataset volume / linked_columns scope.");
    }

    private static String buildMessage(long current,
                                       long max,
                                       List<SubstitutionDictStats.TopContributor> topContributors) {
        StringBuilder message = new StringBuilder(String.format(
                "SubstitutionDictionary exceeded its maximum size (%d entries, max %d).",
                current,
                max));

        if (topContributors != null && !topContributors.isEmpty()) {
            message.append("\nTop contributors:");
            for (SubstitutionDictStats.TopContributor contributor : topContributors) {
                message.append(String.format(
                        "\n  - %s: %d entries",
                        contributor.semanticKey(),
                        contributor.entries()));
            }
        }

        message.append("\nEither increase brume.substitution-dict.max-entries (heap permitting) or reduce")
                .append("\n")
                .append("the dataset volume / linked_columns scope. Check the columns above for")
                .append("\n")
                .append("unexpectedly high cardinality.");
        return message.toString();
    }
}

