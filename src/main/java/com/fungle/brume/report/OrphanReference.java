package com.fungle.brume.report;

import java.util.List;

/**
 * FK references left dangling on purpose by {@code orphan_policy: ALLOW_ORPHANS} (ADR-0050) :
 * {@code rows} rows of {@code table} point through {@code columns} to {@code missingParents}
 * distinct rows of {@code parentTable} that lie outside the {@code fk_depth} radius.
 *
 * @param table          child table holding the FK
 * @param columns        FK columns of {@code table}
 * @param parentTable    referenced table
 * @param rows           copied rows of {@code table} whose reference has no target row
 * @param missingParents distinct referenced keys absent from the copy
 */
public record OrphanReference(String table, List<String> columns, String parentTable,
                              long rows, long missingParents) {

    public OrphanReference {
        columns = List.copyOf(columns);
    }

    /** One-line description used by the WARN logs of plan and execute. */
    public String describe() {
        return rows + " row(s) of " + table + " reference " + missingParents + " row(s) of "
                + parentTable + " outside fk_depth via " + String.join(", ", columns);
    }
}
