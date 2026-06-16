package com.fungle.brume.writer;

/**
 * Output compression applied by file-based sinks (e.g. {@link SqlFileSink}).
 *
 * <ul>
 *   <li>{@link #NONE} — write raw bytes; restore with {@code psql -f}.</li>
 *   <li>{@link #GZIP} — chain {@link java.util.zip.GZIPOutputStream}; restore with
 *       {@code gunzip < dump.sql.gz | psql}. Universal compatibility, default level.</li>
 *   <li>{@link #ZSTD} — chain {@code com.github.luben.zstd.ZstdOutputStream}; restore with
 *       {@code zstdcat dump.sql.zst | psql}. Better ratio and speed than gzip; requires
 *       {@code zstd}/{@code zstdcat} on the target host.</li>
 * </ul>
 */
public enum CompressionType {
    NONE,
    GZIP,
    ZSTD
}
