package com.fungle.brume.scan;

import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.init.InitPrompter;
import com.fungle.brume.scan.ScanConfigBuilder.ScanResult;
import com.fungle.brume.schema.SchemaAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Entry point for the {@code brume scan} subcommand. Reads the configured source schema,
 * runs {@link ScanConfigBuilder} to turn it into a suggestion tree, prompts the operator
 * if {@code brume.yml} already exists (Q2 of ADR scan-command), then writes the file.
 *
 * <p>Scan is one of the three subcommands that boot Spring but never contact the target
 * database — the sink is forced to {@code NULL} in
 * {@link com.fungle.brume.BrumeApplication#applyReadOnlySinkOverride(String[])}, same
 * as {@code plan} and {@code dry-run}.
 */
@Component
public class ScanRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);
    private static final Logger output = LoggerFactory.getLogger("brume.output");

    static final String BRUME_YML = "brume.yml";

    private final SchemaAnalyzer schemaAnalyzer;
    private final ReplicationProperties replicationProperties;
    private final Path workingDir;
    private final InitPrompter prompter;

    // @Autowired disambiguates for Spring 6 : without it, bean instantiation falls back
    // to looking for a no-arg constructor and fails at boot (symptom shape of #23i).
    @Autowired
    public ScanRunner(SchemaAnalyzer schemaAnalyzer, ReplicationProperties replicationProperties) {
        this(schemaAnalyzer, replicationProperties,
                Path.of(".").toAbsolutePath().normalize(),
                InitPrompter.forConsole(System.console(), System.out, System.err));
    }

    ScanRunner(SchemaAnalyzer schemaAnalyzer,
               ReplicationProperties replicationProperties,
               Path workingDir,
               InitPrompter prompter) {
        this.schemaAnalyzer = schemaAnalyzer;
        this.replicationProperties = replicationProperties;
        this.workingDir = workingDir;
        this.prompter = prompter;
    }

    /**
     * Runs the scan. Returns an exit code suitable for {@code System.exit}.
     *
     * @param force when {@code true}, overwrite an existing {@code brume.yml} without prompting
     * @return 0 on success (file written or kept), 1 on IO failure
     */
    public int run(boolean force) {
        String schema = replicationProperties.schema();
        output.info("Brume scan — analyzing source schema '{}'", schema);
        DatabaseSchema databaseSchema = schemaAnalyzer.analyze(schema);
        ScanResult result = ScanConfigBuilder.build(databaseSchema);
        output.info("Detected {} rule(s) across {} table(s)",
                result.totalSuggestions(), result.tables().size());

        Path target = workingDir.resolve(BRUME_YML);
        if (Files.exists(target) && !force) {
            InitPrompter.Answer answer = prompter.ask("  Overwrite " + BRUME_YML + "?", false);
            if (answer == InitPrompter.Answer.NO) {
                output.info("  → kept existing {}.", BRUME_YML);
                return 0;
            }
            if (answer == InitPrompter.Answer.NO_TTY) {
                output.warn("  → non-interactive shell : keeping existing {} (pass --force to overwrite).",
                        BRUME_YML);
                return 0;
            }
        }

        try {
            Files.writeString(target, ScanYamlWriter.render(result),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("[SCAN_IO_ERROR] Failed to write {}: {}", target, e.getMessage());
            return 1;
        }
        output.info("  → wrote {}", target);
        output.info("Next steps :");
        output.info("  1. Review each rule in brume.yml and remove or adjust what doesn't fit.");
        output.info("  2. Fill in the `extraction.tables` filters.");
        output.info("  3. Run `brume plan` to preview the extraction.");
        return 0;
    }
}
