package com.fungle.brume.extraction.model;

import java.util.Map;

/**
 * Represents a single row extracted from the source database.
 *
 * <p>The {@code table} field identifies which table this row belongs to (qualified by schema
 * if needed). The {@code data} map holds column name → value pairs as returned by JDBC.
 *
 * <p>This record is immutable. The {@code data} map should be unmodifiable at the call site.
 */
public record ExtractedRow(String table, Map<String, Object> data) {}

