package com.fungle.brume.audit.anonymity;

import com.fungle.brume.util.SqlIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the k-anonymity distribution of a single (table, quasi-id-set) on a target
 * database via a single {@code GROUP BY} query, then optionally fetches a sample of
 * the smallest classes (singletons) for display.
 *
 * <p>SQL strategy :
 * <pre>{@code
 *   SELECT <q1>, <q2>, ..., COUNT(*) AS k
 *   FROM "<schema>"."<table>" [TABLESAMPLE SYSTEM (<pct>)]
 *   GROUP BY <q1>, <q2>, ...
 * }</pre>
 * The result has one row per equivalence class ; we walk the rows and bucketize on
 * the fly. Note that the result can be huge if the table has many singletons — we
 * stream via {@link JdbcTemplate#query} (row-by-row) and never materialize the full
 * list of classes.
 *
 * <p>NULL handling : PostgreSQL {@code GROUP BY} groups NULL with NULL — a class
 * with {@code birth_date IS NULL AND postal_code IS NULL} appears as a single
 * equivalence class. This matches the SQL standard and is documented in the report.
 *
 * <p>Sampling : when {@code sampleRate < 1.0}, {@code TABLESAMPLE SYSTEM (<pct>)} is
 * appended to the FROM clause. {@code SYSTEM} samples by block (statistically
 * uniform, fast, can miss rare classes — disclosed in the methodology section).
 *
 * <p>This component is stateless and not Spring-managed (instantiated by
 * {@link AnonymityAuditor}).
 */
public class KAnonymityCalculator {

    private static final Logger log = LoggerFactory.getLogger(KAnonymityCalculator.class);

    /** Max number of singletons fetched and embedded in the report — ticket #73. */
    static final int SINGLETON_LIMIT = 20;

    private final JdbcTemplate target;
    private final String schema;

    /**
     * @param target target-database JDBC template (caller's responsibility to ensure
     *               it points at the audit subject)
     * @param schema validated schema name (already passed through {@link SqlIdentifiers#validate})
     */
    public KAnonymityCalculator(JdbcTemplate target, String schema) {
        this.target = target;
        this.schema = schema;
    }

    /**
     * Audits a single (table, quasi-id-set) and returns a populated
     * {@link TableAuditResult}.
     *
     * <p>When {@code sampleRate >= 1.0}, no sampling is performed. When {@code 0 <
     * sampleRate < 1.0}, {@code TABLESAMPLE SYSTEM (<sampleRate*100>)} is appended.
     * Values outside {@code (0, 1]} are clamped to {@code 1.0}.
     */
    public TableAuditResult audit(String table, List<String> quasiIdColumns, double sampleRate) {
        SqlIdentifiers.validate(table);
        for (String col : quasiIdColumns) {
            SqlIdentifiers.validate(col);
        }
        double sr = (sampleRate <= 0.0 || sampleRate > 1.0) ? 1.0 : sampleRate;

        String sql = buildGroupBySql(table, quasiIdColumns, sr);
        log.debug("k-anonymity SQL on {}.{} ({} quasi-id cols, sampleRate={}): {}",
                schema, table, quasiIdColumns.size(), sr, sql);

        List<Long> classSizes = new ArrayList<>();
        List<SingletonRow> singletonSamples = new ArrayList<>();
        target.query(sql, rs -> {
            int colCount = quasiIdColumns.size();
            long k = rs.getLong(colCount + 1);  // COUNT(*) is the last column
            classSizes.add(k);
            if (k == 1L && singletonSamples.size() < SINGLETON_LIMIT) {
                List<String> values = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    String s = rs.getString(i);
                    values.add(rs.wasNull() ? "NULL" : s);
                }
                singletonSamples.add(new SingletonRow(values));
            }
        });

        EquivalenceClassDistribution distribution = EquivalenceClassDistribution.from(classSizes);
        List<Recommendation> recs = recommendationsFor(table, quasiIdColumns, distribution);

        return new TableAuditResult(table, quasiIdColumns, distribution, singletonSamples, sr, recs);
    }

    /**
     * Builds the {@code GROUP BY} SQL with proper identifier quoting and optional
     * {@code TABLESAMPLE SYSTEM} clause.
     */
    String buildGroupBySql(String table, List<String> quasiIdColumns, double sampleRate) {
        String quotedTable = SqlIdentifiers.quoteQualified(schema, table);
        String quotedCols = String.join(", ",
                quasiIdColumns.stream().map(SqlIdentifiers::quote).toList());
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(quotedCols).append(", COUNT(*) AS k FROM ").append(quotedTable);
        if (sampleRate < 1.0) {
            sb.append(" TABLESAMPLE SYSTEM (")
                    .append(String.format(java.util.Locale.ROOT, "%.4f", sampleRate * 100.0))
                    .append(")");
        }
        sb.append(" GROUP BY ").append(quotedCols);
        return sb.toString();
    }

    /**
     * Generates textual recommendations from a distribution.
     *
     * <p>V1 rules :
     * <ul>
     *   <li>If singletons &gt; 0 → CRITICAL "N rows are uniquely identified, generalize
     *       or suppress."</li>
     *   <li>Else if kMin &lt; 5 → WARNING "smallest class is k=N, below the academic
     *       threshold."</li>
     *   <li>Else INFO "minimum k=N, no immediate action."</li>
     * </ul>
     */
    static List<Recommendation> recommendationsFor(String table,
                                                   List<String> quasiIdColumns,
                                                   EquivalenceClassDistribution d) {
        List<Recommendation> out = new ArrayList<>();
        if (d.totalClasses() == 0) {
            out.add(new Recommendation(Recommendation.Severity.INFO,
                    "Table '" + table + "' is empty — nothing to audit."));
            return out;
        }
        if (d.singletons() > 0) {
            out.add(new Recommendation(Recommendation.Severity.CRITICAL,
                    d.singletons() + " row(s) in '" + table + "' are uniquely identified by ("
                            + String.join(", ", quasiIdColumns) + "). Consider generalizing one of "
                            + "these columns (e.g. truncate date to month, postal code to first 3 digits) "
                            + "or suppressing these records."));
        } else if (d.kMin() < 5) {
            out.add(new Recommendation(Recommendation.Severity.WARNING,
                    "Smallest equivalence class in '" + table + "' has k=" + d.kMin()
                            + ", below the k≥5 academic threshold (Sweeney 2002 ; CNIL 2020). "
                            + "Consider narrowing further."));
        } else {
            out.add(new Recommendation(Recommendation.Severity.INFO,
                    "Minimum k=" + d.kMin() + " on '" + table + "' — no singleton classes."));
        }
        return out;
    }
}
