package com.fungle.brume.command;

import com.fungle.brume.audit.anonymity.AnonymityAuditSpec;
import com.fungle.brume.audit.anonymity.AuditRunner;
import com.fungle.brume.diag.DiagRunner;
import com.fungle.brume.timeout.TimedReplicationRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.fungle.brume.command.CommandEnum.DRY_RUN;
import static com.fungle.brume.command.CommandEnum.EXECUTE;
import static com.fungle.brume.command.CommandEnum.PLAN;

@CommandLine.Command(
        name = "brume",
        mixinStandardHelpOptions = true,  // --help et --version auto
        version = "1.0.0",
        description = "Brume"
)
@Component
public class BrumeCommand implements Callable<Integer> {

    /*
     * Output-mode flags are declared here so picocli lists them in `--help`, but their
     * actual effect is applied pre-boot by BrumeApplication.applyOutputModeFlags()
     * (Logback needs the system property set before it configures). The fields below
     * are not read at runtime — they exist solely to register the options with picocli.
     */
    @SuppressWarnings("unused")
    @CommandLine.Option(names = {"-v", "--verbose"}, scope = CommandLine.ScopeType.INHERIT,
            description = "Verbose output (DEBUG-level logs). Mutually exclusive with --quiet.")
    private boolean verbose;

    @SuppressWarnings("unused")
    @CommandLine.Option(names = {"-q", "--quiet"}, scope = CommandLine.ScopeType.INHERIT,
            description = "Quiet output (only ERROR-level logs); the run result is still emitted.")
    private boolean quiet;

    @SuppressWarnings("unused")
    @CommandLine.Option(names = {"--json"}, scope = CommandLine.ScopeType.INHERIT,
            description = "Emit a single JSON wrapper on stdout for the run result and route logs to stderr as JSON.")
    private boolean json;

    private final TimedReplicationRunner runner;
    private final DiagRunner diagRunner;
    private final AuditRunner auditRunner;

    public BrumeCommand(TimedReplicationRunner runner, DiagRunner diagRunner,
                        AuditRunner auditRunner) {
        this.runner = runner;
        this.diagRunner = diagRunner;
        this.auditRunner = auditRunner;
    }

    @CommandLine.Command(name = "execute", description = "Execute BRUME")
    public Integer execute() throws Exception {
        runner.run(EXECUTE);
        return 0;
    }

    @CommandLine.Command(name = "plan", description = "describe BRUME action")
    public Integer plan() throws Exception {
        runner.run(PLAN);
        return 0;
    }

    @CommandLine.Command(name = "dry-run",
            description = "Run the full pipeline without writing to the target (uses NullSink)")
    public Integer dryRun() throws Exception {
        runner.run(DRY_RUN);
        return 0;
    }

    @CommandLine.Command(name = "diag",
            description = "Boot the application context and report wiring/config/pool status. "
                    + "Does NOT contact the source or target databases. Suitable as a Docker HEALTHCHECK "
                    + "or pre-flight smoke check before a real run. Exit code 0 on healthy boot.")
    public Integer diag() {
        return diagRunner.run();
    }

    @CommandLine.Command(name = "audit",
            description = "Audit the target dataset's anonymity quality. Currently supports "
                    + "k-anonymity (--anonymity). Reads from the target database only — never "
                    + "writes. Pair with --strict --k-min N for CI gating.")
    public Integer audit(
            @CommandLine.Option(names = "--anonymity",
                    description = "Run the k-anonymity audit (required in V1 — the only audit kind).",
                    defaultValue = "false") boolean anonymity,
            @CommandLine.Option(names = "--quasi-id",
                    description = "Quasi-identifier columns to audit, format 'table:col1,col2'. "
                            + "Repeatable for multiple tables. Overrides --auto-detect-quasi-id.",
                    paramLabel = "TABLE:COLS") List<String> quasiId,
            @CommandLine.Option(names = "--auto-detect-quasi-id",
                    description = "Auto-detect quasi-id columns via QuasiIdDetector (#21c heuristic). "
                            + "Ignored when --quasi-id is set.",
                    defaultValue = "false") boolean autoDetect,
            @CommandLine.Option(names = "--strict",
                    description = "Exit with code 8 if any audited table has k_min < --k-min.",
                    defaultValue = "false") boolean strict,
            @CommandLine.Option(names = "--k-min",
                    description = "Minimum acceptable k value when --strict is set. Default 5 "
                            + "(Sweeney 2002 / CNIL 2020).",
                    defaultValue = "5") long kMin,
            @CommandLine.Option(names = "--sample-rate",
                    description = "Fraction in (0, 1] of each table to scan via TABLESAMPLE SYSTEM. "
                            + "Default 1.0 (full scan). Smaller values trade accuracy for speed.",
                    defaultValue = "1.0") double sampleRate,
            @CommandLine.Option(names = "--output-json",
                    description = "Path to write the JSON report (in addition to the console).",
                    paramLabel = "PATH") Path outputJson,
            @CommandLine.Option(names = "--output-markdown",
                    description = "Path to write the Markdown DPO report (in addition to the console).",
                    paramLabel = "PATH") Path outputMarkdown
    ) {
        if (!anonymity) {
            System.err.println("[AUDIT_KIND_REQUIRED] V1 only supports k-anonymity.");
            System.err.println("  → Pass --anonymity to enable.");
            return 1;
        }
        Map<String, List<String>> explicit = parseQuasiIdFlags(quasiId);
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                explicit, autoDetect, strict, kMin, sampleRate, outputJson, outputMarkdown);
        return auditRunner.run(spec);
    }

    /**
     * Parses the {@code --quasi-id} multi-value flag into an ordered map. Each entry
     * has the shape {@code "table:col1,col2,..."}. Empty list → empty map.
     */
    static Map<String, List<String>> parseQuasiIdFlags(List<String> flags) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (flags == null) return out;
        for (String raw : flags) {
            int sep = raw.indexOf(':');
            if (sep <= 0 || sep == raw.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid --quasi-id value: '" + raw + "'. Expected 'table:col1,col2,...'.");
            }
            String table = raw.substring(0, sep).trim();
            String colsRaw = raw.substring(sep + 1);
            List<String> cols = new ArrayList<>();
            for (String c : colsRaw.split(",")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty()) cols.add(trimmed);
            }
            if (cols.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid --quasi-id value: '" + raw + "' has no column after the colon.");
            }
            out.put(table, cols);
        }
        return out;
    }

    @Override
    public Integer call() throws Exception {
        new CommandLine(this).usage(System.out);
        return 0;
    }
}
