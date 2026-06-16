package com.fungle.brume.writer;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.WriteException;

import java.util.Locale;

/**
 * Exception thrown when the batch error rate for a table exceeds the configured threshold.
 *
 * <p>This is a fail-safe mechanism to prevent silent data loss: if too many batches fail
 * (e.g. due to schema mismatch, constraint violations, or data quality issues), the pipeline
 * aborts rather than continuing with incomplete data.
 *
 * <p>The threshold is configured via {@code brume.max-batch-error-rate} (default: 0.0 = zero tolerance).
 */
public class BatchErrorThresholdExceededException extends WriteException {

    /**
     * Constructs a new exception with a detailed error message.
     *
     * @param table     the table name for which the threshold was exceeded
     * @param errors    the number of batches that failed
     * @param total     the total number of batches processed
     * @param rate      the observed error rate (0.0 to 1.0)
     * @param threshold the configured maximum error rate (0.0 to 1.0)
     */
    public BatchErrorThresholdExceededException(String table, long errors, long total, double rate, double threshold) {
        super(BrumeErrorCode.WRITE_BATCH_THRESHOLD,
                String.format(Locale.US,
                        "Batch error rate exceeded for table '%s': %d/%d batches failed (%.2f%%, threshold %.2f%%).",
                        table, errors, total, rate * 100, threshold * 100),
                "Raise brume.max-batch-error-rate or fix the underlying data quality issue.");
    }
}


