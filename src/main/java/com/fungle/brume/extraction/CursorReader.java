package com.fungle.brume.extraction;

import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Reads rows from the source database using PostgreSQL streaming cursors.
 *
 * <p>All queries are executed with a JDBC fetch size of 1000 to avoid loading
 * entire tables into memory at once.
 *
 * <p>Rows are returned as {@link ExtractedRow} instances, which hold the
 * unqualified table name and a column-name-to-value map.
 */
@Component
public class CursorReader {
    private static final Logger log = LoggerFactory.getLogger(CursorReader.class);

    private static final int IN_CLAUSE_BATCH_SIZE = 1000;

    /** Lexicographic order over PK value tuples — deterministic batch/dump order (#81b / #30b). */
    private static final Comparator<List<Object>> TUPLE_COMPARATOR = (a, b) -> {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            int c = ((Comparable) a.get(i)).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    };

    private final JdbcTemplate sourceJdbcTemplate;

    public CursorReader(@Qualifier("sourceJdbcTemplate") JdbcTemplate sourceJdbcTemplate) {
        this.sourceJdbcTemplate = sourceJdbcTemplate;
    }

    public List<ExtractedRow> read(String schemaName, String tableName, String whereFilter) {
        List<ExtractedRow> rows = new ArrayList<>();
        streamRows(schemaName, tableName, whereFilter, rows::add);

        log.info("Read {} rows from {}.{}", rows.size(), schemaName, tableName);
        return rows;
    }

    public void streamRows(String schemaName, String tableName, String whereFilter, Consumer<ExtractedRow> consumer) {
        streamRows(schemaName, tableName, whereFilter, IN_CLAUSE_BATCH_SIZE, null, consumer);
    }

    public void streamRows(String schemaName, String tableName, String whereFilter,
                           int fetchSize, Consumer<ExtractedRow> consumer) {
        streamRows(schemaName, tableName, whereFilter, fetchSize, null, consumer);
    }

    /**
     * Streams rows from {@code schema.table} with an optional deterministic ordering.
     *
     * <p>When {@code orderByColumn} is non-null, appends {@code ORDER BY "<col>" ASC} to the
     * query. The column is expected to be a single-column primary key (the only PostgreSQL
     * column type guaranteed unique and indexed by default, hence cheap for ORDER BY).
     * Tables with a composite or missing PK pass {@code null} here — the caller decides
     * whether to skip ORDER BY or surface a WARN (cf. {@code ChunkedTableProcessor.processAll}).
     *
     * <p>Tracked under {@code #30b / A9b} — deterministic intra-table order in the dump path.
     */
    public void streamRows(String schemaName, String tableName, String whereFilter,
                           int fetchSize, String orderByColumn, Consumer<ExtractedRow> consumer) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(SqlIdentifiers.quoteQualified(schemaName, tableName));

        if (whereFilter != null && !whereFilter.isBlank()) {
            sql.append(" WHERE ").append(whereFilter);
        }

        if (orderByColumn != null) {
            sql.append(" ORDER BY ").append(SqlIdentifiers.quote(orderByColumn)).append(" ASC");
        }

        executeStreamingQuery(sql.toString(), fetchSize, ps -> {}, rs -> consumer.accept(mapRow(rs, tableName)));
    }

    public void readChunked(String schemaName, String tableName, String whereFilter,
                            int chunkSize, Consumer<List<ExtractedRow>> consumer) {
        readChunked(schemaName, tableName, whereFilter,
                Math.min(chunkSize, IN_CLAUSE_BATCH_SIZE), chunkSize, null, consumer);
    }

    public void readChunked(String schemaName, String tableName, String whereFilter,
                            int fetchSize, int chunkSize, Consumer<List<ExtractedRow>> consumer) {
        readChunked(schemaName, tableName, whereFilter, fetchSize, chunkSize, null, consumer);
    }

    /**
     * Chunked variant that propagates {@code orderByColumn} to the underlying SELECT — see
     * {@link #streamRows(String, String, String, int, String, Consumer)}. Tracked under #30b.
     */
    public void readChunked(String schemaName, String tableName, String whereFilter,
                            int fetchSize, int chunkSize, String orderByColumn,
                            Consumer<List<ExtractedRow>> consumer) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (fetchSize <= 0) {
            throw new IllegalArgumentException("fetchSize must be > 0");
        }

        List<ExtractedRow> buffer = new ArrayList<>(chunkSize);
        streamRows(schemaName, tableName, whereFilter, fetchSize, orderByColumn, row -> {
            buffer.add(row);
            if (buffer.size() >= chunkSize) {
                consumer.accept(List.copyOf(buffer));
                buffer.clear();
            }
        });

        if (!buffer.isEmpty()) {
            consumer.accept(List.copyOf(buffer));
        }
    }

    public Set<Object> readDistinctColumnValues(String schemaName, String tableName,
                                                String columnName, String whereFilter) {
        return readDistinctColumnValues(schemaName, tableName, columnName, whereFilter, IN_CLAUSE_BATCH_SIZE);
    }

    public Set<Object> readDistinctColumnValues(String schemaName, String tableName,
                                                String columnName, String whereFilter, int fetchSize) {
        if (fetchSize <= 0) {
            throw new IllegalArgumentException("fetchSize must be > 0");
        }

        StringBuilder sql = new StringBuilder("SELECT DISTINCT ")
                .append(SqlIdentifiers.quote(columnName))
                .append(" FROM ")
                .append(SqlIdentifiers.quoteQualified(schemaName, tableName));

        if (whereFilter != null && !whereFilter.isBlank()) {
            sql.append(" WHERE ").append(whereFilter);
        }

        Set<Object> values = new LinkedHashSet<>();
        executeStreamingQuery(sql.toString(), fetchSize, ps -> {}, rs -> {
            Object value = rs.getObject(1);
            if (value != null) {
                values.add(value);
            }
        });
        return values;
    }

    /**
     * Streams rows from {@code schema.table} where {@code fkColumn} IN {@code fkValues},
     * processing each IN-clause batch immediately without accumulating results in memory.
     *
     * <p>Designed for FK child resolution in STREAMING mode: parent PKs are batched into
     * groups of {@value IN_CLAUSE_BATCH_SIZE} and each batch is streamed row-by-row to
     * the consumer, so memory usage is bounded by the JDBC fetch buffer rather than the
     * full result set.
     */
    public void streamByForeignKey(String schemaName, String tableName, String fkColumn,
                                   Collection<Object> fkValues, int fetchSize,
                                   Consumer<ExtractedRow> consumer) {
        if (fkValues.isEmpty()) return;

        List<Object> sorted = new ArrayList<>(fkValues);
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Comparator<Object> comparator = (a, b) -> ((Comparable) a).compareTo(b);
            sorted.sort(comparator);
        } catch (ClassCastException e) {
            log.debug("streamByForeignKey {}.{}: FK values not mutually Comparable — type: {}",
                    schemaName, tableName,
                    sorted.isEmpty() ? "unknown" : sorted.getFirst().getClass().getName());
        }

        for (List<Object> batch : partition(sorted)) {
            String inClause = IntStream.range(0, batch.size())
                    .mapToObj(i -> "?")
                    .collect(Collectors.joining(", "));
            String sql = "SELECT * FROM " + SqlIdentifiers.quoteQualified(schemaName, tableName)
                    + " WHERE " + SqlIdentifiers.quote(fkColumn) + " IN (" + inClause + ")";
            executeStreamingQuery(sql, fetchSize, ps -> {
                for (int i = 0; i < batch.size(); i++) ps.setObject(i + 1, batch.get(i));
            }, rs -> consumer.accept(mapRow(rs, tableName)));
        }
    }

    public List<ExtractedRow> readByPrimaryKeys(
            String schemaName,
            String tableName,
            String pkColumn,
            Collection<Object> pkValues) {

        if (pkValues.isEmpty()) {
            return List.of();
        }
        long start = System.currentTimeMillis();

        // #30b — deterministic batch ordering : sort the PK values before partition() so
        // that the same set of PKs always produces the same sequence of IN clauses across
        // runs. Combined with the ORDER BY in queryBatch below, this gives a stable
        // intra-table order in the final dump. PK values are typically Long/Integer/String/UUID,
        // all Comparable. Unsupported types fall back to identity order — log debug only.
        List<Object> sorted = new ArrayList<>(pkValues);
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Comparator<Object> comparator = (a, b) -> ((Comparable) a).compareTo(b);
            sorted.sort(comparator);
        } catch (ClassCastException e) {
            log.debug("readByPrimaryKeys {}.{}: PK values not mutually Comparable — "
                    + "intra-table dump order may vary. Type: {}",
                    schemaName, tableName, sorted.getFirst().getClass().getName());
        }

        List<List<Object>> batches = partition(sorted);

        // Sequential: batches are virtually always 1 (< 1000 PKs). Nested parallelism
        // on ForkJoinPool.commonPool() would compete with the FK-level virtual threads.
        List<ExtractedRow> result = batches.stream()
                .flatMap(batch -> queryBatch(schemaName, tableName, pkColumn, batch).stream())
                .collect(Collectors.toList());

        log.debug("readByPrimaryKeys {}.{}: {} PKs → {} batches → {}ms",
                schemaName, tableName, pkValues.size(), batches.size(),
                System.currentTimeMillis() - start);
        return result;
    }

    private List<ExtractedRow> queryBatch(
            String schemaName,
            String tableName,
            String pkColumn,
            List<Object> batch) {

        String inClause = IntStream.range(0, batch.size())
                .mapToObj(i -> "?")
                .collect(Collectors.joining(", "));
        // #30b — ORDER BY pkColumn so each batch returns its rows in a stable order
        // (the SQL IN clause itself does NOT guarantee result ordering — Postgres is free
        // to reorder for plan reasons). Coupled with PK-value sort above, the full read
        // is now deterministic.
        String sql = "SELECT * FROM " + SqlIdentifiers.quoteQualified(schemaName, tableName)
                + " WHERE " + SqlIdentifiers.quote(pkColumn) + " IN (" + inClause + ")"
                + " ORDER BY " + SqlIdentifiers.quote(pkColumn) + " ASC";

        List<ExtractedRow> rows = new ArrayList<>();
        executeStreamingQuery(sql, IN_CLAUSE_BATCH_SIZE, ps -> {
            for (int i = 0; i < batch.size(); i++) {
                ps.setObject(i + 1, batch.get(i));
            }
        }, rs -> rows.add(mapRow(rs, tableName)));
        return rows;
    }

    /**
     * Composite-key variant of {@link #readByPrimaryKeys(String, String, String, Collection)}:
     * reads rows whose ordered {@code keyColumns} tuple matches one of {@code keyTuples}, via a
     * PostgreSQL row-value {@code WHERE (c1, c2) IN ((?,?), ...)} predicate. Tuples are sorted
     * lexicographically and the SELECT is {@code ORDER BY} the key columns, so the read is
     * deterministic (#81b / ADR-0042, #30b). Delegates to the scalar path when {@code keyColumns}
     * has a single element (preserving the exact single-column behavior + its bind shape).
     */
    public List<ExtractedRow> readByPrimaryKeys(
            String schemaName,
            String tableName,
            List<String> keyColumns,
            Collection<List<Object>> keyTuples) {

        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("keyColumns must not be empty");
        }
        if (keyColumns.size() == 1) {
            String col = keyColumns.getFirst();
            List<Object> scalars = new ArrayList<>(keyTuples.size());
            for (List<Object> t : keyTuples) scalars.add(t.getFirst());
            return readByPrimaryKeys(schemaName, tableName, col, scalars);
        }
        if (keyTuples.isEmpty()) {
            return List.of();
        }
        long start = System.currentTimeMillis();

        List<List<Object>> sorted = new ArrayList<>(keyTuples);
        try {
            sorted.sort(TUPLE_COMPARATOR);
        } catch (ClassCastException e) {
            log.debug("readByPrimaryKeys(composite) {}.{}: tuple values not mutually Comparable — "
                    + "intra-table order may vary", schemaName, tableName);
        }

        String orderBy = keyColumns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String rowConstructor = "(" + orderBy + ")";

        List<ExtractedRow> result = new ArrayList<>();
        for (List<List<Object>> batch : partition(sorted)) {
            String tupleList = batch.stream()
                    .map(t -> "(" + t.stream().map(_ -> "?").collect(Collectors.joining(", ")) + ")")
                    .collect(Collectors.joining(", "));
            String sql = "SELECT * FROM " + SqlIdentifiers.quoteQualified(schemaName, tableName)
                    + " WHERE " + rowConstructor + " IN (" + tupleList + ")"
                    + " ORDER BY " + orderBy;
            executeStreamingQuery(sql, IN_CLAUSE_BATCH_SIZE, ps -> {
                int i = 1;
                for (List<Object> t : batch) {
                    for (Object v : t) {
                        ps.setObject(i++, v);
                    }
                }
            }, rs -> result.add(mapRow(rs, tableName)));
        }
        log.debug("readByPrimaryKeys(composite) {}.{}: {} tuples → {} rows → {}ms",
                schemaName, tableName, keyTuples.size(), result.size(), System.currentTimeMillis() - start);
        return result;
    }

    /**
     * Composite variant of {@link #readDistinctColumnValues}: returns the distinct ordered value
     * tuples of {@code columns}. Tuples containing a {@code null} component are dropped — an
     * incomplete FK reference cannot match a parent PK. Delegates to the scalar path (wrapping
     * each value into a one-element list) when {@code columns} has a single element.
     */
    public Set<List<Object>> readDistinctColumnTuples(String schemaName, String tableName,
                                                      List<String> columns, String whereFilter, int fetchSize) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        if (fetchSize <= 0) {
            throw new IllegalArgumentException("fetchSize must be > 0");
        }
        Set<List<Object>> tuples = new LinkedHashSet<>();
        if (columns.size() == 1) {
            for (Object v : readDistinctColumnValues(schemaName, tableName, columns.getFirst(), whereFilter, fetchSize)) {
                if (v != null) {
                    tuples.add(List.of(v));
                }
            }
            return tuples;
        }

        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(cols)
                .append(" FROM ").append(SqlIdentifiers.quoteQualified(schemaName, tableName));
        if (whereFilter != null && !whereFilter.isBlank()) {
            sql.append(" WHERE ").append(whereFilter);
        }
        executeStreamingQuery(sql.toString(), fetchSize, _ -> {}, rs -> {
            List<Object> tuple = new ArrayList<>(columns.size());
            boolean hasNull = false;
            for (int i = 1; i <= columns.size(); i++) {
                Object v = rs.getObject(i);
                if (v == null) {
                    hasNull = true;
                }
                tuple.add(v);
            }
            if (!hasNull) {
                tuples.add(tuple);
            }
        });
        return tuples;
    }

    private void executeStreamingQuery(String sql, int fetchSize,
                                       SqlConsumer<PreparedStatement> statementConfigurer,
                                       SqlConsumer<ResultSet> rowConsumer) {
        // Don't log the raw SQL — user-supplied filters may carry PII (e.g. WHERE email = '…').
        // Log a non-cryptographic signature instead so operators can still correlate events
        // across the pipeline without leaking values. (#16, ADR-0025)
        log.debug("Executing read query (length={} chars, sig={})",
                sql.length(), String.format("%08x", sql.hashCode()));
        sourceJdbcTemplate.execute((Connection con) -> {
            boolean restoreAutoCommit = con.getAutoCommit();
            try {
                if (restoreAutoCommit) {
                    con.setAutoCommit(false);
                }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setFetchSize(fetchSize);
                    statementConfigurer.accept(ps);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rowConsumer.accept(rs);
                        }
                    }
                }
            } finally {
                if (restoreAutoCommit) {
                    con.setAutoCommit(true);
                }
            }
            return null;
        });
    }

    private ExtractedRow mapRow(ResultSet rs, String tableName) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        Map<String, Object> data = new LinkedHashMap<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            data.put(meta.getColumnName(i), rs.getObject(i));
        }
        return new ExtractedRow(tableName, data);
    }

    private <T> List<List<T>> partition(List<T> list) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CursorReader.IN_CLAUSE_BATCH_SIZE) {
            partitions.add(list.subList(i, Math.min(i + CursorReader.IN_CLAUSE_BATCH_SIZE, list.size())));
        }
        return partitions;
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }
}

