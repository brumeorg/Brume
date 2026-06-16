package com.fungle.brume.writer;

/**
 * Write strategy for {@link JdbcSink}.
 *
 * <ul>
 *   <li>{@link #NEVER} — always use INSERT batched with {@code ON CONFLICT DO NOTHING}
 *       (the historical behaviour). Preserves graceful PK-conflict handling at the cost
 *       of prepared statement overhead.</li>
 *   <li>{@link #PREFER} — try {@code COPY FROM stdin} first (×5–10 faster on the write
 *       path); on any {@code DataAccessException}, fall back to INSERT batched for the
 *       same batch. Default.</li>
 *   <li>{@link #FORCE} — always use {@code COPY FROM stdin}; any failure (PK conflict,
 *       NOT NULL, type) is recorded as a batch error. Suitable for mass-loading into
 *       an empty target.</li>
 * </ul>
 */
public enum CopyMode {
    NEVER,
    PREFER,
    FORCE
}
