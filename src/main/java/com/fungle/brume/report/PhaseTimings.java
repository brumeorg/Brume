package com.fungle.brume.report;

/**
 * Immutable record holding the duration of each pipeline phase in milliseconds.
 *
 * @param extractionMs    time spent in the extraction phase (source DB reads + FK resolution)
 * @param anonymizationMs time spent applying anonymization strategies to extracted rows
 * @param writeMs         time spent writing anonymized rows to the target database
 * @param totalMs         total wall-clock time for the entire Brume run
 */
public record PhaseTimings(
        long extractionMs,
        long anonymizationMs,
        long writeMs,
        long totalMs
) {

    /**
     * Returns a human-readable summary of phase durations.
     *
     * <p>Example: {@code "Extract: 2 104 ms | Anonymize: 831 ms | Write: 3 412 ms | Total: 7 143 ms"}
     *
     * @return formatted string with all four phase durations
     */
    public String format() {
        return "Extract: " + formatMs(extractionMs)
                + " ms | Anonymize: " + formatMs(anonymizationMs)
                + " ms | Write: " + formatMs(writeMs)
                + " ms | Total: " + formatMs(totalMs) + " ms";
    }

    /**
     * Formats a millisecond value with a space as thousands separator.
     *
     * @param ms the value to format
     * @return formatted string, e.g. {@code "2 104"} for {@code 2104}
     */
    private static String formatMs(long ms) {
        // Force US locale to get comma as grouping separator, then replace with space.
        return String.format(java.util.Locale.US, "%,d", ms).replace(',', ' ');
    }

    /**
     * Returns the total duration in a compact clock-style format suitable for the
     * report hero (large display).
     *
     * <p>Format rules:
     * <ul>
     *   <li>under 1 second: {@code "0:00.X s"} (milliseconds rounded to the deciseconde)</li>
     *   <li>under 1 minute: {@code "0:SS.D s"} where {@code SS} is the seconds and
     *       {@code D} is the deciseconde digit</li>
     *   <li>1 minute or more: {@code "M:SS.D s"} — minutes and zero-padded seconds</li>
     *   <li>1 hour or more: {@code "H:MM:SS s"} — hours, zero-padded minutes and seconds,
     *       no deciseconde</li>
     * </ul>
     *
     * <p>Distinct from {@link #format()} which renders all four phases inline. Used by
     * {@code ReportTemplateModelFactory} to build the hero display in the HTML report.
     *
     * @return total duration, compact form (e.g. {@code "4:23.4 s"})
     */
    public String compactClock() {
        long ms = totalMs;
        if (ms < 0) {
            ms = 0;
        }
        long hours = ms / 3_600_000L;
        long minutes = (ms % 3_600_000L) / 60_000L;
        long seconds = (ms % 60_000L) / 1_000L;
        long deciseconds = (ms % 1_000L) / 100L;

        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d s", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d.%d s", minutes, seconds, deciseconds);
    }
}


