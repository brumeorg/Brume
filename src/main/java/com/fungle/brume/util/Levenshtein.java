package com.fungle.brume.util;

import java.util.Collection;
import java.util.Optional;

/**
 * Tiny Levenshtein-distance helper used to suggest "did you mean ..." hints when a config
 * references a name that does not exist in the source schema.
 *
 * <p>Two-row dynamic-programming implementation: O(m·n) time, O(min(m,n)) memory. Sufficient
 * for the short identifiers (≤ 63 chars) and small candidate lists Brume deals with at boot.
 *
 * <p>The matching is case-insensitive, since PostgreSQL identifiers are folded to lowercase
 * by default and Brume's config tolerates either case.
 */
public final class Levenshtein {

    private Levenshtein() {
        // Utility class — no instantiation
    }

    /**
     * Returns the Levenshtein edit distance between two strings (case-insensitive).
     *
     * @param a first string (must not be null)
     * @param b second string (must not be null)
     * @return the minimum number of single-character edits (insert / delete / substitute)
     *         needed to transform {@code a} into {@code b}
     */
    public static int distance(String a, String b) {
        String s = a.toLowerCase();
        String t = b.toLowerCase();
        int m = s.length();
        int n = t.length();
        if (m == 0) return n;
        if (n == 0) return m;

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = (s.charAt(i - 1) == t.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    /**
     * Returns the closest match from {@code candidates} to {@code query}, if any candidate
     * is within {@code maxDistance} edits.
     *
     * @param query        the unknown string the user typed
     * @param candidates   the list of valid names to match against
     * @param maxDistance  the maximum acceptable edit distance (inclusive); typically 3
     * @return the closest candidate by edit distance, or {@link Optional#empty()} if none qualifies
     */
    public static Optional<String> closestMatch(String query, Collection<String> candidates, int maxDistance) {
        String best = null;
        int bestDist = maxDistance + 1;
        for (String c : candidates) {
            int d = distance(query, c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }
}
