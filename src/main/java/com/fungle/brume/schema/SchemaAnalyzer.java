package com.fungle.brume.schema;

import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.timeout.BoundedQueryExecutor;
import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries a PostgreSQL database's {@code information_schema} to build a {@link DatabaseSchema}
 * describing all tables, columns and foreign keys for a given schema.
 *
 * <p>Two entry points :
 * <ul>
 *   <li>{@link #analyze(String)} — the default. Uses the source {@code JdbcTemplate} wrapped
 *       in a {@link BoundedQueryExecutor} so each {@code information_schema} query is
 *       protected by {@code SET LOCAL statement_timeout} (#23, ADR-0033).</li>
 *   <li>{@link #analyze(JdbcTemplate, String)} — explicit template, no timeout wrapper.
 *       Used by callers that target a different database (typically the
 *       <strong>target</strong> in audit mode, #73 / ADR-0036). The introspection queries
 *       are cheap (a few KB read from {@code information_schema}) so the missing
 *       timeout is a non-issue in practice.</li>
 * </ul>
 *
 * <p>Both entry points share the same SQL — the only difference is the JDBC template used.
 */
@Component
public class SchemaAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SchemaAnalyzer.class);

    private static final String COLUMNS_SQL = """
            SELECT table_name, column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = ?
            ORDER BY table_name, ordinal_position
            """;

    private static final String PRIMARY_KEYS_SQL = """
            SELECT kcu.table_name, kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
               AND tc.table_schema    = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
              AND tc.table_schema    = ?
            ORDER BY kcu.table_name, kcu.ordinal_position
            """;

    // pg_catalog rather than information_schema: the latter joins key_column_usage and
    // constraint_column_usage with no ordinal correlation, producing a cross product on a
    // composite FK (mispaired columns, over-fetch — ADR-0042). conkey/confkey are ordinal-
    // aligned arrays; unnest WITH ORDINALITY pairs child column i with parent column i exactly.
    private static final String FOREIGN_KEYS_SQL = """
            SELECT
                con.conname    AS constraint_name,
                child.relname  AS from_table,
                fcol.attname   AS from_column,
                parent.relname AS to_table,
                pcol.attname   AS to_column
            FROM pg_constraint con
            JOIN pg_class child   ON child.oid  = con.conrelid
            JOIN pg_class parent  ON parent.oid = con.confrelid
            JOIN pg_namespace ns  ON ns.oid     = con.connamespace
            JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS cols(child_attnum, parent_attnum, ord) ON true
            JOIN pg_attribute fcol ON fcol.attrelid = con.conrelid  AND fcol.attnum = cols.child_attnum
            JOIN pg_attribute pcol ON pcol.attrelid = con.confrelid AND pcol.attnum = cols.parent_attnum
            WHERE con.contype = 'f'
              AND ns.nspname  = ?
            ORDER BY child.relname, con.conname, cols.ord
            """;

    private final JdbcTemplate sourceJdbcTemplate;
    private final BoundedQueryExecutor boundedQueryExecutor;

    /**
     * Constructs a SchemaAnalyzer backed by the source database JDBC template.
     *
     * @param sourceJdbcTemplate   the source-database JDBC template (qualifier:
     *                             {@code "sourceJdbcTemplate"})
     * @param boundedQueryExecutor wraps each introspection query with a {@code
     *                             statement_timeout} guard (#23 / A21, ADR-0033)
     */
    public SchemaAnalyzer(@Qualifier("sourceJdbcTemplate") JdbcTemplate sourceJdbcTemplate,
                          BoundedQueryExecutor boundedQueryExecutor) {
        this.sourceJdbcTemplate = sourceJdbcTemplate;
        this.boundedQueryExecutor = boundedQueryExecutor;
    }

    /**
     * Analyzes the given PostgreSQL schema on the <strong>source</strong> database and
     * returns a complete {@link DatabaseSchema}. Each introspection query runs inside a
     * bounded-timeout transaction (#23).
     *
     * @param schemaName the PostgreSQL schema name to analyze
     * @return a fully populated {@link DatabaseSchema}
     */
    public DatabaseSchema analyze(String schemaName) {
        SqlIdentifiers.validate(schemaName);
        log.info("Analyzing source schema '{}'", schemaName);

        Map<String, List<ColumnMetadata>> columnsByTable = new LinkedHashMap<>();
        boundedQueryExecutor.executeVoid("SchemaAnalyzer.loadColumns", jdbc ->
                columnsByTable.putAll(loadColumns(jdbc, schemaName)));

        Map<String, List<ForeignKey>> fksByTable = new LinkedHashMap<>();
        boundedQueryExecutor.executeVoid("SchemaAnalyzer.loadForeignKeys", jdbc ->
                fksByTable.putAll(loadForeignKeys(jdbc, schemaName)));

        Map<String, List<String>> pkByTable = new LinkedHashMap<>();
        boundedQueryExecutor.executeVoid("SchemaAnalyzer.loadPrimaryKeys", jdbc ->
                pkByTable.putAll(loadPrimaryKeys(jdbc, schemaName)));

        return assemble(schemaName, columnsByTable, fksByTable, pkByTable);
    }

    /**
     * Analyzes the given PostgreSQL schema using the supplied {@link JdbcTemplate} and
     * returns a complete {@link DatabaseSchema}. No timeout wrapper — introspection
     * queries on {@code information_schema} are inherently cheap.
     *
     * <p>Intended for callers that target a database other than the configured source
     * (typically the <strong>target</strong> in audit mode, #73 / ADR-0036).
     *
     * @param jdbcTemplate the JDBC template to query (caller's responsibility to ensure
     *                     it is connected to the intended database)
     * @param schemaName   the PostgreSQL schema name to analyze
     * @return a fully populated {@link DatabaseSchema}
     */
    public DatabaseSchema analyze(JdbcTemplate jdbcTemplate, String schemaName) {
        SqlIdentifiers.validate(schemaName);
        log.info("Analyzing schema '{}' via caller-supplied JdbcTemplate", schemaName);

        return assemble(schemaName,
                loadColumns(jdbcTemplate, schemaName),
                loadForeignKeys(jdbcTemplate, schemaName),
                loadPrimaryKeys(jdbcTemplate, schemaName));
    }

    // -------------------------------------------------------------------------
    // Helpers parameterized on JdbcTemplate
    // -------------------------------------------------------------------------

    private DatabaseSchema assemble(String schemaName,
                                    Map<String, List<ColumnMetadata>> columnsByTable,
                                    Map<String, List<ForeignKey>> fksByTable,
                                    Map<String, List<String>> pkByTable) {
        // LinkedHashMap to preserve the alphabetic table order set by the SQL ORDER BY
        // — propagates a deterministic iteration order downstream to GraphAnalyzer
        // (topological sort visit order), ChunkedTableProcessor and finally SqlFileSink
        // (COPY block order in the dump). Tracked under #25c (spike HMAC 2026-05-12).
        Map<String, TableMetadata> tables = new LinkedHashMap<>();
        for (String tableName : columnsByTable.keySet()) {
            List<ColumnMetadata> columns = columnsByTable.get(tableName);
            List<ForeignKey> fks = fksByTable.getOrDefault(tableName, List.of());
            // Empty when the table has no PK ; size ≥ 2 for a composite PK (#81b / ADR-0042).
            List<String> pk = pkByTable.getOrDefault(tableName, List.of());
            tables.put(tableName, new TableMetadata(tableName, columns, fks, pk));
        }
        log.info("Schema '{}' analyzed: {} table(s)", schemaName, tables.size());
        // Wrap rather than Map.copyOf — the latter returns a hash-based map whose
        // iteration order is unspecified, which would discard the alpha sort above.
        DatabaseSchema schema = new DatabaseSchema(Collections.unmodifiableMap(new LinkedHashMap<>(tables)));
        warnAboutPkStructure(schema);
        return schema;
    }

    /**
     * Emits a single boot-time WARN listing the tables whose primary key is composite or
     * absent — the two shapes that historically disabled per-row dedup and FK-by-PK matching.
     * Composite and PK-less tables are listed separately so an operator can tell a composite
     * PK (now supported, #81b) from a table with no PK at all (best-effort). Replaces the
     * former silent {@code log.debug} in {@code loadPrimaryKeys} (#81a).
     *
     * <p>Package-visible for {@code SchemaAnalyzerCompositePkWarningTest}.
     */
    static void warnAboutPkStructure(DatabaseSchema schema) {
        List<String> composite = schema.compositePkTables();
        List<String> withoutPk = schema.tablesWithoutPrimaryKey();
        if (!composite.isEmpty()) {
            log.warn("{} table(s) with a composite primary key: {}", composite.size(), composite);
        }
        if (!withoutPk.isEmpty()) {
            log.warn("{} table(s) without a primary key: {}", withoutPk.size(), withoutPk);
        }
    }

    private static Map<String, List<ColumnMetadata>> loadColumns(JdbcTemplate jdbc,
                                                                 String schemaName) {
        // LinkedHashMap — SQL ORDER BY table_name guarantees alpha sort ; preserving
        // insertion order propagates the determinism to the assembled DatabaseSchema (#25c).
        Map<String, List<ColumnMetadata>> result = new LinkedHashMap<>();
        jdbc.query(COLUMNS_SQL, rs -> {
            String table = rs.getString("table_name");
            String column = rs.getString("column_name");
            String dataType = rs.getString("data_type");
            boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
            result.computeIfAbsent(table, _ -> new ArrayList<>())
                    .add(new ColumnMetadata(column, dataType, nullable));
        }, schemaName);
        return result;
    }

    private static Map<String, List<String>> loadPrimaryKeys(JdbcTemplate jdbc, String schemaName) {
        // ORDER BY ordinal_position in PRIMARY_KEYS_SQL guarantees the columns of a composite
        // PK arrive in their declared order — preserved here in insertion order (#81b).
        Map<String, List<String>> pkColumns = new LinkedHashMap<>();
        jdbc.query(PRIMARY_KEYS_SQL, rs -> {
            String table = rs.getString("table_name");
            String column = rs.getString("column_name");
            pkColumns.computeIfAbsent(table, _ -> new ArrayList<>()).add(column);
        }, schemaName);
        return pkColumns;
    }

    private static Map<String, List<ForeignKey>> loadForeignKeys(JdbcTemplate jdbc, String schemaName) {
        // Accumulate the ordinal-ordered (from,to) column pairs per (table, constraint) so a
        // composite FK collapses into a single ForeignKey with positionally-aligned column
        // lists (#81b). FOREIGN_KEYS_SQL's ORDER BY ... cols.ord guarantees pair order.
        Map<String, FkAccumulator> byConstraint = new LinkedHashMap<>();
        jdbc.query(FOREIGN_KEYS_SQL, rs -> {
            String constraint = rs.getString("constraint_name");
            String fromTable = rs.getString("from_table");
            String fromColumn = rs.getString("from_column");
            String toTable = rs.getString("to_table");
            String toColumn = rs.getString("to_column");
            // Constraint names are unique per table, not per schema — key by table + constraint.
            byConstraint.computeIfAbsent(fromTable + ' ' + constraint,
                            _ -> new FkAccumulator(fromTable, toTable))
                    .add(fromColumn, toColumn);
        }, schemaName);

        Map<String, List<ForeignKey>> result = new LinkedHashMap<>();
        for (FkAccumulator acc : byConstraint.values()) {
            result.computeIfAbsent(acc.fromTable, _ -> new ArrayList<>())
                    .add(new ForeignKey(acc.fromTable, List.copyOf(acc.fromColumns),
                            acc.toTable, List.copyOf(acc.toColumns)));
        }
        return result;
    }

    /** Mutable accumulator of a single FK constraint's ordinal-ordered column pairs. */
    private static final class FkAccumulator {
        private final String fromTable;
        private final String toTable;
        private final List<String> fromColumns = new ArrayList<>();
        private final List<String> toColumns = new ArrayList<>();

        FkAccumulator(String fromTable, String toTable) {
            this.fromTable = fromTable;
            this.toTable = toTable;
        }

        void add(String fromColumn, String toColumn) {
            fromColumns.add(fromColumn);
            toColumns.add(toColumn);
        }
    }
}
