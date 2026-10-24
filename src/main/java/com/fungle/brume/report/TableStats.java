package com.fungle.brume.report;

/**
 * Immutable statistics for a single table, collected during one Brume execution.
 *
 * @param table       fully-qualified table name (schema.table or just table)
 * @param extracted   number of rows read directly from the source table
 *                    (i.e. matching the configured WHERE filter, if any)
 * @param fkParents   number of rows the FK walk reached by climbing to a parent
 *                    ({@code FkTraversal}, ADR-0050) — not in the original extraction set
 * @param fkChildren  number of rows the FK walk reached by descending to a child : rows
 *                    holding a FK column that references an already-extracted row
 * @param inserted    number of rows actually inserted in the target table
 *                    (confirmed by JDBC batch update return values)
 * @param conflicts   number of rows skipped by {@code ON CONFLICT DO NOTHING} because a row
 *                    with the same primary key already existed in the target (re-run, resume)
 * @param uniqueCollisions number of rows <b>lost</b> : skipped by {@code ON CONFLICT DO NOTHING}
 *                    because their pseudonymized value collided with another row on a
 *                    {@code UNIQUE} constraint (#29a) — they are not in the target
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
        long uniqueCollisions,
        long batchErrors
) {
    /** Backward-compatible constructor — {@code uniqueCollisions} defaults to 0. */
    public TableStats(String table, long extracted, long fkParents, long fkChildren,
                      long inserted, long conflicts, long batchErrors) {
        this(table, extracted, fkParents, fkChildren, inserted, conflicts, 0L, batchErrors);
    }

    /** Backward-compatible constructor — {@code fkChildren} and {@code uniqueCollisions} default to 0. */
    public TableStats(String table, long extracted, long fkParents,
                      long inserted, long conflicts, long batchErrors) {
        this(table, extracted, fkParents, 0L, inserted, conflicts, 0L, batchErrors);
    }
}

