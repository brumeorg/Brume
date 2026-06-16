package com.fungle.brume.report;

/**
 * Immutable snapshot of JVM heap usage observed during a Brume run.
 *
 * @param peakUsedBytes          highest used-heap value observed during the run
 * @param maxBytes               JVM max heap as reported by {@code MemoryMXBean}; may be {@code 0} when unavailable
 * @param warningTriggered       whether the configured warning threshold was crossed at least once
 * @param warningThresholdPercent configured warning threshold percentage that triggered alerts
 */
public record HeapStats(
        long peakUsedBytes,
        long maxBytes,
        boolean warningTriggered,
        int warningThresholdPercent
) {

    public static HeapStats empty() {
        return new HeapStats(0L, 0L, false, 0);
    }

    public boolean hasMax() {
        return maxBytes > 0;
    }

    public int peakUsagePercent() {
        if (!hasMax()) {
            return 0;
        }
        return (int) Math.round((peakUsedBytes * 100.0) / maxBytes);
    }
}

