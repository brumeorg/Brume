package com.fungle.brume.audit.anonymity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.output.OutputMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime façade for the {@code brume audit --anonymity} subcommand (#73 /
 * ADR-0036). Sibling of {@code DiagRunner} (#75) and
 * {@code TimedReplicationRunner} (#23).
 *
 * <p>Responsibilities :
 * <ol>
 *   <li>Delegate audit computation to {@link AnonymityAuditor}.</li>
 *   <li>Emit the report via the three formatters (text always, JSON on stdout
 *       when {@code --json}, file outputs when paths are set).</li>
 *   <li>Return the exit code : {@code 0} on success, {@code 8} on policy
 *       violation when {@code --strict} is set. {@link
 *       com.fungle.brume.command.BrumeExecutionExceptionHandler}'s grid stays
 *       exception-driven (codes 1-7, 127, 130) — code 8 lives outside the grid
 *       and is returned directly from this method (cf. ADR-0036 §3).</li>
 * </ol>
 */
@Component
public class AuditRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditRunner.class);
    private static final Logger output = LoggerFactory.getLogger("brume.output");

    /** Exit code for a strict policy violation (k_min &lt; --k-min threshold). */
    public static final int EXIT_POLICY_VIOLATED = 8;

    private final AnonymityAuditor auditor;
    private final ObjectMapper objectMapper;

    public AuditRunner(AnonymityAuditor auditor, ObjectMapper objectMapper) {
        this.auditor = auditor;
        this.objectMapper = objectMapper;
    }

    public int run(AnonymityAuditSpec spec) {
        AnonymityReport report = auditor.audit(spec);
        emit(report, spec);
        if (report.policyViolated()) {
            if (report.overallKMin() < 0) {
                // #79e — distinguish the empty-audit case so the operator sees the real
                // problem (schema vide / tables vides) instead of "k_min < kMin" trompeur.
                log.warn("Audit policy violated: no equivalence class observed "
                        + "(every audited table is empty). Strict mode rejects an empty audit "
                        + "— Brume cannot certify k-anonymity on zero rows.");
            } else {
                log.warn("Audit policy violated: overall k_min={} < --k-min={}",
                        report.overallKMin(), report.kMin());
            }
            return EXIT_POLICY_VIOLATED;
        }
        return 0;
    }

    private void emit(AnonymityReport report, AnonymityAuditSpec spec) {
        // Text always on the dedicated output logger — the format the user sees by
        // default. When --json is on, the text is suppressed (per OutputMode.JSON
        // convention) and the JSON wrapper takes the stdout line.
        OutputMode mode = OutputMode.current();
        if (mode == OutputMode.JSON) {
            String json = AnonymityJsonRenderer.render(report, objectMapper);
            System.out.println(json);
        } else {
            String text = AnonymityTextRenderer.render(report);
            for (String line : text.split("\n")) {
                output.info("{}", line);
            }
        }

        // File outputs are independent of --json — operator can always request JSON
        // or markdown to file regardless of console mode.
        if (spec.outputJsonPath() != null) {
            writeFile(spec.outputJsonPath(),
                    AnonymityJsonRenderer.render(report, objectMapper));
        }
        if (spec.outputMarkdownPath() != null) {
            writeFile(spec.outputMarkdownPath(),
                    AnonymityMarkdownRenderer.render(report));
        }
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Non-fatal — the console output is the authoritative result. Log and continue.
            log.warn("Failed to write audit report to {}: {}", path, e.getMessage());
        }
    }
}
