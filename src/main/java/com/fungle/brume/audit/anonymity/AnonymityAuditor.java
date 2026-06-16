package com.fungle.brume.audit.anonymity;

import com.fungle.brume.audit.QuasiIdDetector;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.report.QuasiIdWarning;
import com.fungle.brume.schema.SchemaAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.state.ExecutionState;
import com.fungle.brume.state.ExecutionStateReader;
import com.fungle.brume.util.Levenshtein;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the {@code brume audit --anonymity} subcommand (#73 / ADR-0036).
 *
 * <p>Pipeline :
 * <ol>
 *   <li>Validates the {@link AnonymityAuditSpec} (must have either explicit
 *       quasi-id or {@code --auto-detect-quasi-id}).</li>
 *   <li>Analyzes the <strong>target</strong> schema via
 *       {@link SchemaAnalyzer#analyze(JdbcTemplate, String)}.</li>
 *   <li>Resolves the list of (table, quasi-id-set) to audit :
 *       <ul>
 *         <li>Explicit (from {@code --quasi-id}) — validates each column exists,
 *             fails fast with Levenshtein suggestion otherwise.</li>
 *         <li>Auto-detect — runs {@link QuasiIdDetector} on the loaded target
 *             schema + {@code config.yaml} and groups warnings by table.</li>
 *       </ul></li>
 *   <li>For each pair, instantiates a {@link KAnonymityCalculator} and computes
 *       the distribution.</li>
 *   <li>Aggregates into an {@link AnonymityReport} (no I/O here — formatting and
 *       file output are the {@code AuditRunner}'s job).</li>
 * </ol>
 *
 * <p>Boot isolation (#73 / ADR-0036) : this component is wired via the standard
 * Spring DI graph but only invoked when the subcommand is {@code audit}. The
 * {@code brume.preflight.mode=AUDIT} system property posed by
 * {@code BrumeApplication.applyAuditBootIsolation} ensures the preflight skips
 * source-side checks.
 */
@Component
public class AnonymityAuditor {

    private static final Logger log = LoggerFactory.getLogger(AnonymityAuditor.class);

    private final SchemaAnalyzer schemaAnalyzer;
    private final QuasiIdDetector quasiIdDetector;
    private final ConfigLoader configLoader;
    private final ReplicationProperties replicationProperties;
    private final Optional<DataSource> targetDataSource;
    private final ExecutionStateReader executionStateReader;

    public AnonymityAuditor(SchemaAnalyzer schemaAnalyzer,
                            QuasiIdDetector quasiIdDetector,
                            ConfigLoader configLoader,
                            ReplicationProperties replicationProperties,
                            @Qualifier("targetDataSource") Optional<DataSource> targetDataSource,
                            ExecutionStateReader executionStateReader) {
        this.schemaAnalyzer = schemaAnalyzer;
        this.quasiIdDetector = quasiIdDetector;
        this.configLoader = configLoader;
        this.replicationProperties = replicationProperties;
        this.targetDataSource = targetDataSource;
        this.executionStateReader = executionStateReader;
    }

    /**
     * Runs the audit per {@code spec} and returns the populated report.
     *
     * @throws ConfigurationException when the spec is under-specified, when an
     *                                explicit quasi-id references an unknown
     *                                table/column, or when {@code targetDataSource}
     *                                is missing
     */
    public AnonymityReport audit(AnonymityAuditSpec spec) {
        if (spec.isUnderspecified()) {
            throw new ConfigurationException(
                    BrumeErrorCode.AUDIT_QUASI_ID_REQUIRED,
                    "Audit requires either --quasi-id or --auto-detect-quasi-id.",
                    "Pass --quasi-id \"table:col1,col2\" (repeatable) or "
                            + "--auto-detect-quasi-id to use the heuristic from QuasiIdDetector "
                            + "(see #21c / ADR-0035).");
        }
        DataSource ds = targetDataSource.orElseThrow(() -> new ConfigurationException(
                BrumeErrorCode.AUDIT_TARGET_NOT_CONFIGURED,
                "Audit requires brume.sink.type=JDBC and a configured replication.target.url.",
                "Set replication.target.url to point at the database to audit."));

        JdbcTemplate target = new JdbcTemplate(ds);
        String schema = replicationProperties.schema();

        Instant auditedAt = Instant.now();
        log.info("Audit starting on target schema '{}' (strict={}, k-min={}, sample-rate={})",
                schema, spec.strict(), spec.kMin(), spec.sampleRate());

        DatabaseSchema dbSchema = schemaAnalyzer.analyze(target, schema);

        Map<String, List<String>> resolved = resolveQuasiIdSets(spec, dbSchema);
        if (resolved.isEmpty()) {
            log.warn("Audit resolved no (table, quasi-id) pair to audit — "
                    + "auto-detection found no candidate column or explicit list was empty");
        }

        KAnonymityCalculator calculator = new KAnonymityCalculator(target, schema);
        List<TableAuditResult> results = new ArrayList<>(resolved.size());
        long overallKMin = -1L;
        for (Map.Entry<String, List<String>> e : resolved.entrySet()) {
            TableAuditResult r = calculator.audit(e.getKey(), e.getValue(), spec.sampleRate());
            results.add(r);
            long kMin = r.distribution().kMin();
            if (kMin >= 0 && (overallKMin < 0 || kMin < overallKMin)) {
                overallKMin = kMin;
            }
        }

        return new AnonymityReport(schema, auditedAt, results,
                spec.strict(), spec.kMin(), overallKMin);
    }

    // -------------------------------------------------------------------------
    // Quasi-id resolution
    // -------------------------------------------------------------------------

    /**
     * Returns an ordered map {@code table → quasi-id columns} preserving the user's
     * declaration order (explicit) or the schema iteration order (auto-detect).
     * Validates every (table, column) exists in {@code dbSchema} and fails fast
     * otherwise.
     */
    Map<String, List<String>> resolveQuasiIdSets(AnonymityAuditSpec spec, DatabaseSchema dbSchema) {
        Set<String> neutralized = loadNeutralizedColumns();
        if (!spec.explicitQuasiId().isEmpty()) {
            return validateExplicit(spec.explicitQuasiId(), dbSchema, neutralized);
        }
        return autoDetect(dbSchema, neutralized);
    }

    /**
     * Builds the set of {@code "table\0column"} keys whose strategy is neutralizing
     * (FAKE / NULLIFY / MASK / FPE_ID / FPE_UUID) according to the last execution
     * state. Returns an empty set when no state file is found (first run or dry-run).
     */
    private Set<String> loadNeutralizedColumns() {
        Optional<ExecutionState> stateOpt = executionStateReader.read();
        if (stateOpt.isEmpty()) {
            log.warn("No execution state file found ({}). "
                    + "Audit falls back to heuristic quasi-id detection — "
                    + "run 'brume execute' first to enable strategy-aware filtering.",
                    ExecutionStateReader.class.getSimpleName());
            return Set.of();
        }
        ExecutionState state = stateOpt.get();
        Set<String> out = new HashSet<>();
        for (var col : state.columns()) {
            if (ExecutionStateReader.NEUTRALIZED_STRATEGIES.contains(col.strategy())) {
                out.add(col.table() + "\0" + col.column());
            }
        }
        log.info("Execution state loaded: {} neutralized column(s) excluded from audit",
                out.size());
        return out;
    }

    private Map<String, List<String>> validateExplicit(Map<String, List<String>> explicit,
                                                       DatabaseSchema dbSchema,
                                                       Set<String> neutralized) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : explicit.entrySet()) {
            String table = e.getKey();
            TableMetadata tableMeta = dbSchema.tables().get(table);
            if (tableMeta == null) {
                String suggestion = suggestClosest(table, dbSchema.tables().keySet());
                // #79g — fix wording : pre-fix the table-count was interpolated as if it were
                // the schema name, producing "...(target schema '12' tables)." → confusion
                // grossière pour l'opérateur qui croyait avoir un schéma nommé "12".
                throw new ConfigurationException(
                        BrumeErrorCode.AUDIT_UNKNOWN_TABLE,
                        "Unknown table '" + table + "' in --quasi-id (target schema has "
                                + dbSchema.tables().size() + " tables).",
                        suggestion != null
                                ? "Did you mean '" + suggestion + "' ?"
                                : "Verify the table exists on the target schema (see brume diag).");
            }
            List<String> cols = e.getValue();
            List<String> known = tableMeta.columns().stream().map(c -> c.name()).toList();
            for (String c : cols) {
                if (!known.contains(c)) {
                    String suggestion = suggestClosest(c, known);
                    throw new ConfigurationException(
                            BrumeErrorCode.AUDIT_UNKNOWN_COLUMN,
                            "Unknown column '" + table + "." + c + "' in --quasi-id.",
                            suggestion != null
                                    ? "Did you mean '" + suggestion + "' ?"
                                    : "Verify the column exists on the target schema.");
                }
                // ADR-0039 — warn when the user explicitly audits a column that was anonymized
                // with a neutralizing strategy: the k-anonymity result will be low by construction
                // (many distinct fake values) and does not reflect a real re-identification risk.
                if (neutralized.contains(table + "\0" + c)) {
                    log.warn("[AUDIT] {}.{} was anonymized with a neutralizing strategy "
                            + "(FAKE/NULLIFY/MASK/FPE) — k-anonymity on this column reflects "
                            + "the fake value distribution, not a real re-identification risk.",
                            table, c);
                }
            }
            out.put(table, cols);
        }
        return out;
    }

    private Map<String, List<String>> autoDetect(DatabaseSchema dbSchema, Set<String> neutralized) {
        // For audit, we run QuasiIdDetector against the loaded target schema. The
        // detector wants an AnonymizerConfig — we load the project's config.yaml so
        // the strategy-aware filtering is consistent with the rest of Brume. If the
        // user audits a target that diverged from the source config, we still get a
        // useful auto-detection because we fall back to "all columns matching the
        // name pattern, in any table" when the config has no rule for them.
        AnonymizerConfig config = configLoader.load();
        List<QuasiIdWarning> warnings = quasiIdDetector.detect(
                dbSchema, config, replicationProperties.schema());

        Map<String, List<String>> out = new LinkedHashMap<>();
        for (QuasiIdWarning w : warnings) {
            // ADR-0039 — skip columns whose strategy was neutralizing in the last execute run.
            if (neutralized.contains(w.table() + "\0" + w.column())) {
                log.debug("[AUDIT] Skipping {}.{} — neutralized by execution state (strategy={})",
                        w.table(), w.column(), w.effectiveStrategy());
                continue;
            }
            out.computeIfAbsent(w.table(), _ -> new ArrayList<>()).add(w.column());
        }
        return out;
    }

    /**
     * Returns the closest element to {@code candidate} (Levenshtein ≤ 3) or
     * {@code null} when none. Same threshold as
     * {@link com.fungle.brume.config.SchemaConfigValidator}.
     */
    private static String suggestClosest(String candidate, Iterable<String> known) {
        int bestD = Integer.MAX_VALUE;
        String best = null;
        for (String k : known) {
            int d = Levenshtein.distance(candidate.toLowerCase(), k.toLowerCase());
            if (d < bestD && d <= 3) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }
}
