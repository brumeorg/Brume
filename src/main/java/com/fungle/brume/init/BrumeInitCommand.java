package com.fungle.brume.init;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "init",
        description = "Bootstrap a new Brume project : checks pg_dump " + PgDumpChecker.REQUIRED_MAJOR_VERSION
                + " and creates brume.yml and .env from bundled templates."
)
public final class BrumeInitCommand implements Callable<Integer> {

    static final String BRUME_YML = "brume.yml";
    static final String ENV_FILE = ".env";
    static final String BRUME_YML_RESOURCE = "/init/brume.example.yml";
    static final String ENV_RESOURCE = "/init/.env.template";

    private final PgDumpChecker pgDumpChecker;
    private final OsDetector osDetector;
    private final InitFileWriter fileWriter;
    private final InitPrompter prompter;

    public BrumeInitCommand() {
        this(new PgDumpChecker(),
                new OsDetector(),
                new InitFileWriter(Path.of(".").toAbsolutePath().normalize()),
                InitPrompter.forConsole(System.console(), System.out, System.err));
    }

    BrumeInitCommand(PgDumpChecker pgDumpChecker,
                     OsDetector osDetector,
                     InitFileWriter fileWriter,
                     InitPrompter prompter) {
        this.pgDumpChecker = pgDumpChecker;
        this.osDetector = osDetector;
        this.fileWriter = fileWriter;
        this.prompter = prompter;
    }

    @Override
    public Integer call() {
        prompter.info("Brume init — bootstrap a new project");
        prompter.info("");
        checkPgDump();
        prompter.info("");
        try {
            writeFile(BRUME_YML_RESOURCE, BRUME_YML);
            writeFile(ENV_RESOURCE, ENV_FILE);
        } catch (IOException e) {
            prompter.warn("[INIT_IO_ERROR] " + e.getMessage());
            return 1;
        }
        prompter.info("");
        prompter.info("Next steps :");
        prompter.info("  1. Edit .env with your database credentials and secrets.");
        prompter.info("  2. Edit brume.yml to declare tables and anonymization strategies.");
        prompter.info("  3. Run  brume plan  to preview the extraction (read-only).");
        return 0;
    }

    private void checkPgDump() {
        PgDumpChecker.Result result = pgDumpChecker.probe();
        if (result.meetsRequirement()) {
            prompter.info("✓ pg_dump " + result.majorVersion().orElseThrow() + " detected.");
            return;
        }
        OsDetector.Family family = osDetector.detect();
        String installCmd = osDetector.installCommandFor(family);
        if (!result.found()) {
            prompter.warn("⚠ pg_dump not found on PATH.");
        } else {
            int detected = result.majorVersion().orElse(0);
            prompter.warn("⚠ pg_dump " + detected + " detected — Brume expects "
                    + PgDumpChecker.REQUIRED_MAJOR_VERSION + ".");
        }
        prompter.warn("  Install command for your OS :");
        prompter.warn("    " + installCmd);
        prompter.warn("  init continues — pg_dump is only required at run time.");
    }

    private void writeFile(String resource, String filename) throws IOException {
        boolean exists = fileWriter.exists(filename);
        Path target = fileWriter.pathOf(filename);
        String status = exists ? "exists" : "missing";
        prompter.info(filename + " (" + status + " at " + target + ")");

        boolean overwrite;
        if (!exists) {
            InitPrompter.Answer answer = prompter.ask("  Create " + filename + "?", true);
            if (answer == InitPrompter.Answer.NO) {
                prompter.info("  → skipped.");
                return;
            }
            if (answer == InitPrompter.Answer.NO_TTY) {
                prompter.info("  → non-interactive shell : creating " + filename + ".");
            }
            overwrite = false;
        } else {
            InitPrompter.Answer answer = prompter.ask("  Overwrite " + filename + "?", false);
            if (answer == InitPrompter.Answer.YES) {
                overwrite = true;
            } else if (answer == InitPrompter.Answer.NO_TTY) {
                prompter.info("  → non-interactive shell : keeping existing " + filename + ".");
                return;
            } else {
                prompter.info("  → kept existing file.");
                return;
            }
        }

        InitFileWriter.Outcome outcome = fileWriter.writeFromClasspath(resource, filename, overwrite);
        switch (outcome) {
            case CREATED -> prompter.info("  → created " + filename + ".");
            case OVERWRITTEN -> prompter.info("  → overwritten " + filename + ".");
            case SKIPPED -> prompter.info("  → skipped " + filename + ".");
        }
    }

    public static boolean isInitInvocation(List<String> args) {
        return args.contains("init");
    }
}
