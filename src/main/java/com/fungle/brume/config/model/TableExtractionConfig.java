package com.fungle.brume.config.model;

/**
 * Extraction rule for a single table.
 *
 * <p>The {@code filter} is an optional SQL {@code WHERE} clause snippet
 * (without the {@code WHERE} keyword) applied when reading from the source database.
 * If {@code null} or blank, all rows of the table are read.
 *
 * @param table  unqualified table name to extract
 * @param filter optional SQL {@code WHERE} condition — e.g. {@code "created_at >= '2025-01-01'"}
 */
public record TableExtractionConfig(String table, String filter) {
}

