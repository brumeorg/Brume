package com.fungle.brume.preflight;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.ConnectionException;
import com.fungle.brume.shutdown.CancellationToken;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Checks, before anything is written, that every {@code extraction.tables[].filter}
 * compiles against the source (#17e, ADR-0061).
 *
 * <p>{@code ExtractionFilterValidator} only screens the filter text (no DDL, no comment).
 * A filter naming a column that does not exist used to fail deep in the extraction :
 * {@code brume execute} reported {@code [UNEXPECTED]} with a JDBC stack trace (exit 127)
 * after the DDL had been replicated, and {@code brume plan} succeeded with planned counts
 * of -1. Each filter is now planned with {@code EXPLAIN} — no row is read — and a failure
 * becomes {@link BrumeErrorCode#CONFIG_EXTRACTION_FILTER_INVALID} with PostgreSQL's reason.
 *
 * <p>A table missing from the source is skipped here : the schema validation reports it
 * with {@code CONFIG_SCHEMA_UNKNOWN_TABLE}, which names the table instead of the filter.
 */
@Component
public class ExtractionFilterProbe {

    private static final Logger log = LoggerFactory.getLogger(ExtractionFilterProbe.class);

    private final DataSource source;
    private final CancellationToken cancellationToken;

    public ExtractionFilterProbe(@Qualifier("sourceDataSource") DataSource source,
                                 CancellationToken cancellationToken) {
        this.source = source;
        this.cancellationToken = cancellationToken;
    }

    public void check(String schema, List<TableExtractionConfig> tables) {
        if (tables == null || tables.stream().noneMatch(t -> hasFilter(t))) return;
        try (Connection c = source.getConnection()) {
            int checked = 0;
            for (TableExtractionConfig t : tables) {
                if (!hasFilter(t) || !exists(c, schema, t.table())) continue;
                String sql = "EXPLAIN SELECT 1 FROM " + SqlIdentifiers.quoteQualified(schema, t.table())
                        + " WHERE " + t.filter();
                try (Statement st = c.createStatement()) {
                    st.execute(sql);
                } catch (SQLException e) {
                    // A signal aborts the connection : report the cancellation, not a bad filter.
                    cancellationToken.checkpoint();
                    throw new ConfigurationException(
                            BrumeErrorCode.CONFIG_EXTRACTION_FILTER_INVALID,
                            "Invalid filter on extraction.tables['" + t.table() + "'] : PostgreSQL rejects it — "
                                    + firstLine(e.getMessage()),
                            "Fix the filter in brume.yml : it is used as is after WHERE, on table "
                                    + schema + "." + t.table() + " of the source.",
                            e);
                }
                checked++;
            }
            log.info("Extraction filters OK : {} filter(s) compile on the source", checked);
        } catch (SQLException e) {
            cancellationToken.checkpoint();
            throw new ConnectionException(
                    BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE,
                    "Source database unreachable while checking the extraction filters: " + firstLine(e.getMessage()),
                    "Verify replication.source.url, credentials, and network reachability.",
                    e);
        }
    }

    private static boolean hasFilter(TableExtractionConfig t) {
        return t != null && t.table() != null && t.filter() != null && !t.filter().isBlank();
    }

    private static boolean exists(Connection c, String schema, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_class r JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace "
                        + "WHERE n.nspname = ? AND r.relname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).trim();
    }
}
