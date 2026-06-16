package com.fungle.brume.writer;

/**
 * Sink output mode selected at runtime via {@code brume.sink.type}.
 *
 * <ul>
 *   <li>{@link #JDBC} — write rows directly to the configured target database
 *       via {@link JdbcSink} (default).</li>
 *   <li>{@link #DUMP} — write a {@code psql}-restorable {@code .sql} file via
 *       {@link SqlFileSink}.</li>
 *   <li>{@link #NULL} — count rows but never persist them (real dry-run); used by
 *       the {@code dry-run} subcommand which sets this property programmatically.</li>
 * </ul>
 */
public enum SinkType {
    JDBC,
    DUMP,
    NULL
}
