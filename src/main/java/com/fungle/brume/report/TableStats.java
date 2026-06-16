package com.fungle.brume.report;

/**
 * Immutable statistics for a single table, collected during one Brume execution.
 *
 * @param table       fully-qualified table name (schema.table or just table)
 * @param extracted   number of rows read directly from the source table
 *                    (i.e. matching the configured WHERE filter, if any)
 * @param fkParents   number of rows added by {@code FkParentResolver} to satisfy
 *                    foreign-key constraints (recursive parent rows, not in the
 *                    original extraction set)
 * @param fkChildren  number of rows added by {@code FkChildResolver}: rows from tables
 *                    that hold a FK column referencing an already-extracted row
 * @param inserted    number of rows actually inserted in the target table
 *                    (confirmed by JDBC batch update return values)
 * @param conflicts   number of rows silently ignored by {@code ON CONFLICT DO NOTHING}
 *                    — these rows already existed in the target and were skipped
 * @param batchErrors number of JDBC batches that failed with a {@code DataAccessException}
 *                    and were skipped entirely (rows in those batches were not inserted)
 */
public record TableStats(
        String table,
        long extracted,
        long fkParents,
        long fkChildren,
        long inserted,
        long conflicts,
        long batchErrors
) {
    /** Backward-compatible constructor — {@code fkChildren} defaults to 0. */
    public TableStats(String table, long extracted, long fkParents,
                      long inserted, long conflicts, long batchErrors) {
        this(table, extracted, fkParents, 0L, inserted, conflicts, batchErrors);
    }
}

