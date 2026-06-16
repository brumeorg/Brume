package com.fungle.brume.monitoring;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.report.ExecutionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Locale;

/**
 * Samples JVM heap usage during the pipeline and emits a single warning when the configured
 * threshold is crossed.
 */
@Component
public class HeapMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeapMonitor.class);

    private final MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();
    private final int warningThresholdPercent;

    public HeapMonitor(BrumeProperties brumeProperties) {
        this.warningThresholdPercent = brumeProperties.heapWarningThresholdPercent();
    }

    public void sample(ExecutionReport report, String stage) {
        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        long usedBytes = heapUsage.getUsed();
        long maxBytes = Math.max(0L, heapUsage.getMax());
        report.recordHeapSample(usedBytes, maxBytes, warningThresholdPercent);

        if (maxBytes <= 0 || warningThresholdPercent <= 0) {
            return;
        }

        double usagePercent = (usedBytes * 100.0) / maxBytes;
        if (usagePercent >= warningThresholdPercent && report.markHeapWarningEmitted()) {
            log.warn(
                    "High JVM heap usage detected at stage '{}': {} MiB / {} MiB ({}, threshold {}%). "
                            + "Consider reducing extraction.chunk_size / extraction.fetch_size / extraction.batch_size, "
                            + "tightening filters, or increasing -Xmx.",
                    stage,
                    mib(usedBytes),
                    mib(maxBytes),
                    String.format(Locale.US, "%.1f%%", usagePercent),
                    warningThresholdPercent
            );
        }
    }

    private static long mib(long bytes) {
        return bytes / (1024 * 1024);
    }
}

