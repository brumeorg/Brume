package com.fungle.brume.report;

import java.util.List;

/**
 * Outcome of replaying the pg_dump DDL on the target database, returned by
 * {@code SchemaReplicator.replicate(...)} so the calling agent can fold it into
 * the {@link ExecutionReport}. Tracked under #28 (A17 audit STRICT/LENIENT).
 *
 * <p>In STRICT mode this record is only ever produced with {@code ignored=0} and
 * {@code failures.isEmpty()} — the first error throws before the result is built.
 * In LENIENT mode it carries every statement that was silently skipped so the
 * rapport can surface them.
 *
 * @param ok        number of DDL statements that applied successfully
 * @param ignored   number of DDL statements skipped under LENIENT (==  {@code failures.size()})
 * @param failures  detailed list of the ignored statements, in pg_dump order
 */
public record DdlExecutionResult(int ok, int ignored, List<DdlFailure> failures) {

    public DdlExecutionResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static DdlExecutionResult empty() {
        return new DdlExecutionResult(0, 0, List.of());
    }
}
