package com.fungle.brume.audit.anonymity;

import java.util.List;

/**
 * A re-identifiable row : an equivalence class of size 1 with its quasi-id values
 * spelled out. Listed in the audit report so a DPO can review what specifically
 * remains singling-out-able and decide whether to generalize, suppress, or accept.
 *
 * <p>Values are stored as their PostgreSQL string representation (as returned by the
 * JDBC driver). NULL values are spelled {@code "NULL"} verbatim — this matches the
 * SQL semantics of {@code GROUP BY} (NULL groups with NULL) and lets the report be
 * unambiguous about which records are null-class singletons.
 *
 * @param values one value per quasi-id column, in the same order as the quasi-id
 *               list reported in {@link TableAuditResult#quasiIdColumns()}
 */
public record SingletonRow(List<String> values) {
    public SingletonRow {
        values = List.copyOf(values);
    }
}
