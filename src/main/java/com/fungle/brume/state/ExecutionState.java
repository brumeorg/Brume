package com.fungle.brume.state;

import java.util.List;

/**
 * Machine-readable snapshot of what was anonymized during the last successful
 * {@code brume execute}. Written to {@code brume-state.json} in the working
 * directory. Read by {@code brume audit} to exclude neutralized columns from
 * the k-anonymity calculation (ADR-0039).
 */
public record ExecutionState(
        String generatedAt,
        String schema,
        String brumeVersion,
        List<ColumnState> columns
) {}
