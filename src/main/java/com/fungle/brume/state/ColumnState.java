package com.fungle.brume.state;

/**
 * One column entry in {@link ExecutionState} — captures the effective strategy
 * after FK propagation so the audit can skip neutralized columns.
 */
public record ColumnState(
        String table,
        String column,
        String strategy,
        String type  // nullable — only set for FAKE / MASK with a semantic type
) {}
