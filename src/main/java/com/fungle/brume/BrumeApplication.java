package com.fungle.brume;

import com.fungle.brume.command.BrumeCommand;
import com.fungle.brume.command.BrumeExecutionExceptionHandler;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.init.BrumeInitCommand;
import com.fungle.brume.output.OutputMode;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ImportRuntimeHints;
import picocli.CommandLine;

import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@SpringBootApplication
@EnableConfigurationProperties({BrumeProperties.class, ReplicationProperties.class})
@ImportRuntimeHints(BrumeRuntimeHints.class)
public class BrumeApplication{

    private static final Logger log = LoggerFactory.getLogger(BrumeApplication.class);

    /**
     * Known picocli subcommands. When none of these tokens is present in the CLI args,
     * picocli would fall back to {@link BrumeCommand#call()} which merely prints the
     * usage — booting Spring for that is wasted work and, worse, requires valid DB
     * config the user hasn't set yet (they may be calling {@code brume -v} to inspect
     * the tool, not to run a pipeline). See pre-boot short-circuit below.
     */
    static final Set<String> SUBCOMMANDS =
            Set.of("execute", "plan", "dry-run", "diag", "audit", "init");

    public static void main(String[] args) {
        // Intercept --help / -h / --version / -V before Spring starts so the output
        // is clean (no HikariCP/Spring startup logs). Safe because picocli's mixin
        // never invokes call() or any subcommand method for these flags.
        var argList = Arrays.asList(args);
        if (argList.contains("--help") || argList.contains("-h")
                || argList.contains("--version") || argList.contains("-V")) {
            System.exit(new CommandLine(new BrumeCommand(null, null, null)).execute(args));
        }

        // `init` runs standalone : no Spring context, no .env.brume, no DB (ADR-0045).
        // It creates brume.yml + .env.brume from classpath templates, so booting Spring
        // (which requires BRUME_* env vars) would defeat the bootstrap purpose.
        // Pass only the args AFTER `init` so picocli routes to the matching subcommand
        // (jdbc / dump) — treating BrumeInitCommand as the root command here, not as
        // a subcommand of BrumeCommand.
        if (BrumeInitCommand.isInitInvocation(argList)) {
            int initIdx = argList.indexOf("init");
            String[] subArgs = Arrays.copyOfRange(args, initIdx + 1, args.length);
            System.exit(new CommandLine(new BrumeInitCommand()).execute(subArgs));
        }

        // No subcommand → picocli's fallback is BrumeCommand.call() which prints
        // usage. Booting Spring here would (a) log a screen of Spring/HikariCP DEBUG
        // lines for nothing under `brume -v`, and (b) crash on ReplicationPropertiesValidator
        // when the user hasn't configured replication.target.url yet. Short-circuit
        // to picocli-only invocation, mirroring the --help path.
        //
        // Exception : `spring-boot-maven-plugin:process-aot` invokes this main with
        // empty args and expects SpringApplication.run() to run (so it can intercept
        // and generate AOT sources under target/spring-aot/main/sources). Skip the
        // short-circuit in that phase — the marker `spring.aot.processing=true` is
        // set by Spring's AbstractAotProcessor before it hands off to the app main.
        if (!hasKnownSubcommand(argList) && !isAotProcessingPhase()) {
            System.exit(new CommandLine(new BrumeCommand(null, null, null)).execute(args));
        }

        // Friendly guard against the "I just cloned the repo and ran `brume plan`" trap.
        // Without this, Spring would boot and crash on a BindException over BRUME_HMAC_SECRET
        // — unactionable for a first-time user. Short-circuit with a message that names the
        // actual fix (`brume init`). Skipped during AOT so native builds that inject properties
        // via Maven system properties still work.
        if (!isAotProcessingPhase()) {
            guardEnvOrFail();
        }

        ConfigurableApplicationContext ctx = bootContext(args);

        // #24 / A22 — SIGTERM gracieux. Installé APRÈS bootContext donc s'exécute AVANT le
        // shutdown hook auto de Spring (LIFO). Notre hook signale le cancel + abort des
        // resources bloquantes (CopyManager.copyIn, Process.waitFor, etc.) via le registry,
        // ce qui débloque le thread main ; picocli rend la main, on retourne 130
        // (POSIX 128 + SIGINT) avant que Spring ferme proprement le context.
        // ADR-0032.
        com.fungle.brume.shutdown.CancellationRegistry registry =
                ctx.getBean(com.fungle.brume.shutdown.CancellationRegistry.class);
        Runtime.getRuntime().addShutdownHook(new Thread(registry::requestCancelAll,
                "brume-sigterm-hook"));

        CommandLine commandLine = new CommandLine(ctx.getBean(BrumeCommand.class))
                .setExecutionExceptionHandler(new BrumeExecutionExceptionHandler());
        System.exit(commandLine.execute(args));
    }

    /**
     * Test seam — performs every pre-Spring side effect of {@link #main} ({@code .env}
     * load, {@code applyReadOnlySinkOverride}, {@code applyStrictConfigFlag},
     * {@code applyOutputModeFlags}) followed by the Spring boot, then returns the context
     * without invoking picocli's {@code execute} or {@link System#exit}. Lets integration
     * tests assert the wiring produced by the layer-(a) overrides — see audit gap C2
     * (2026-05-05) and {@code BrumeApplicationCliIT}.
     */
    static ConfigurableApplicationContext bootContext(String[] args) {
        loadDotEnv();
        applyReadOnlySinkOverride(args);
        applyStrictConfigFlag(args);
        applyOutputModeFlags(args);
        applyNoQuasiIdWarnFlag(args);
        applyAuditBootIsolation(args);
        applyStripTimestampsFlag(args);
        applyResumeFlag(args);
        return SpringApplication.run(BrumeApplication.class, args);
    }

    /**
     * Pre-Spring parsing of the three output-mode flags so they can affect Logback boot
     * (ADR-0030) and Spring-bound properties:
     *
     * <ul>
     *   <li>{@code --json} → {@code brume.output.mode=JSON}; routes regular logs to stderr
     *       as line-delimited JSON, leaves stdout free for the result wrapper.</li>
     *   <li>{@code -v} / {@code --verbose} → forces {@code logging.level.root=DEBUG} and
     *       {@code logging.level.com.fungle.brume=DEBUG} regardless of profile defaults.</li>
     *   <li>{@code -q} / {@code --quiet} → forces both levels to {@code ERROR}. The
     *       {@code brume.output} logger remains at INFO via {@code logback-spring.xml}, so
     *       the run result is still produced.</li>
     * </ul>
     *
     * <p>{@code -v} and {@code -q} are mutually exclusive — passing both terminates the
     * process before Spring starts with a clear stderr message and exit code 2 (config
     * error category, matches {@code BrumeErrorCode.CONFIG_*}).
     *
     * <p>System properties win over {@code application*.yaml} per Spring property
     * precedence, so flags always override profile-level log config.
     */
    private static void applyOutputModeFlags(String[] args) {
        var argList = Arrays.asList(args);
        boolean verbose = argList.contains("-v") || argList.contains("--verbose");
        boolean quiet = argList.contains("-q") || argList.contains("--quiet");
        boolean json = argList.contains("--json");

        if (verbose && quiet) {
            System.err.println(
                    "[CONFIG_INVALID_FLAGS] --verbose and --quiet are mutually exclusive.");
            System.err.println("  → Pick one: -v for DEBUG logs, -q for ERROR only.");
            System.err.println("  → Exit code: 2");
            System.exit(2);
        }

        if (json) {
            System.setProperty(OutputMode.SYSTEM_PROPERTY, OutputMode.JSON.name());
        }
        if (verbose) {
            System.setProperty("logging.level.root", "DEBUG");
            System.setProperty("logging.level.com.fungle.brume", "DEBUG");
        } else if (quiet) {
            System.setProperty("logging.level.root", "ERROR");
            System.setProperty("logging.level.com.fungle.brume", "ERROR");
        }
    }

    /**
     * Forces {@code brume.strict-config=true} when {@code --strict-config} is present in the
     * CLI args, before {@link SpringApplication#run} starts so that
     * {@link com.fungle.brume.config.BrumeProperties} binds the flag at boot.
     *
     * <p>System property has higher precedence than {@code application.yaml}, so the flag
     * overrides whatever the user has declared in YAML.
     */
    private static void applyStrictConfigFlag(String[] args) {
        for (String arg : args) {
            if ("--strict-config".equals(arg)) {
                System.setProperty("brume.strict-config", "true");
                return;
            }
        }
    }

    /**
     * Forces {@code brume.audit.quasi-id-enabled=false} when {@code --no-quasi-id-warn}
     * is present in the CLI args, before {@link SpringApplication#run} so that
     * {@link com.fungle.brume.config.BrumeProperties.AuditProperties} binds the flag
     * at boot. Same precedence pattern as {@link #applyStrictConfigFlag(String[])}.
     *
     * <p>Tracked under #21c / ADR-0035.
     */
    private static void applyNoQuasiIdWarnFlag(String[] args) {
        for (String arg : args) {
            if ("--no-quasi-id-warn".equals(arg)) {
                System.setProperty("brume.audit.quasi-id-enabled", "false");
                return;
            }
        }
    }

    /**
     * Boot isolation for the {@code audit} subcommand (#73 / ADR-0036) :
     * <ul>
     *   <li>Forces {@code brume.sink.type=JDBC} so {@code targetDataSource} is wired
     *       (audit reads the target via JDBC ; user-configured DUMP/NULL would skip
     *       target wiring per ADR-0028).</li>
     *   <li>Forces {@code brume.preflight.mode=AUDIT} so {@link
     *       com.fungle.brume.preflight.PreflightCheckRunner} skips source-side checks
     *       and ownership probes — audit only reads the target.</li>
     * </ul>
     *
     * <p>System properties win over {@code application*.yaml} per Spring precedence,
     * matching the pattern of {@link #applyReadOnlySinkOverride(String[])}.
     */
    private static void applyAuditBootIsolation(String[] args) {
        var argList = Arrays.asList(args);
        if (argList.contains("audit")) {
            System.setProperty("brume.sink.type", "JDBC");
            System.setProperty("brume.preflight.mode", "AUDIT");
        }
    }

    /**
     * Forces {@code brume.sink.strip-timestamps=true} when {@code --strip-timestamps}
     * is present in the CLI args, so a {@code DUMP} run produces a byte-deterministic
     * dump (no {@code -- generated_at: ...} header line). Couples with the table-order
     * determinism posed in {@code SchemaAnalyzer} (#25c) — together they enable
     * byte-identical diff between two runs with the same config and HMAC secret.
     *
     * <p>Tracked under #25d.
     */
    private static void applyStripTimestampsFlag(String[] args) {
        for (String arg : args) {
            if ("--strip-timestamps".equals(arg)) {
                System.setProperty("brume.sink.strip-timestamps", "true");
                return;
            }
        }
    }

    /**
     * Forces {@code brume.checkpoint.enabled=true} and {@code brume.checkpoint.path=<path>}
     * when {@code --resume <path>} or {@code --resume=<path>} is present, before Spring
     * binds the properties (#25 / ADR-0037).
     *
     * <p>Unlike the other pre-boot flags, this one carries a value. The parsing supports
     * the two conventional spellings and fails fast on three error cases : missing value,
     * blank value, or the flag being passed twice — each emits an actionable error
     * before Spring starts and exits with code 2.
     */
    private static void applyResumeFlag(String[] args) {
        String found = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--resume".equals(arg)) {
                if (i + 1 >= args.length) {
                    failPreBoot("CHECKPOINT_PATH_MISSING",
                            "--resume requires a path argument.",
                            "Usage: brume execute --resume <path-to-checkpoint.json>");
                }
                String next = args[i + 1];
                if (next == null || next.isBlank() || next.startsWith("--")) {
                    failPreBoot("CHECKPOINT_PATH_MISSING",
                            "--resume requires a non-blank path argument (got '" + next + "').",
                            "Usage: brume execute --resume <path-to-checkpoint.json>");
                }
                if (found != null) {
                    failPreBoot("CHECKPOINT_PATH_MISSING",
                            "--resume was specified more than once.",
                            "Keep a single --resume <path> argument.");
                }
                found = next;
                i++; // consume the value
            } else if (arg != null && arg.startsWith("--resume=")) {
                String value = arg.substring("--resume=".length());
                if (value.isBlank()) {
                    failPreBoot("CHECKPOINT_PATH_MISSING",
                            "--resume= requires a non-blank path after '='.",
                            "Usage: brume execute --resume=<path-to-checkpoint.json>");
                }
                if (found != null) {
                    failPreBoot("CHECKPOINT_PATH_MISSING",
                            "--resume was specified more than once.",
                            "Keep a single --resume=<path> argument.");
                }
                found = value;
            }
        }
        if (found != null) {
            System.setProperty("brume.checkpoint.enabled", "true");
            System.setProperty("brume.checkpoint.path", found);
        }
    }

    /**
     * Returns true when {@code args} contains any of the known picocli subcommand tokens.
     * Used by {@link #main} to skip Spring boot when the user only passes global flags
     * (or nothing at all) — in that case picocli's fallback is {@link BrumeCommand#call()}
     * which only prints usage, so booting Spring is wasted work.
     */
    static boolean hasKnownSubcommand(List<String> args) {
        for (String arg : args) {
            if (SUBCOMMANDS.contains(arg)) return true;
        }
        return false;
    }

    /**
     * True when the Spring Boot AOT processor (spring-boot-maven-plugin:process-aot)
     * is invoking this main to generate AOT sources. Set by
     * {@code org.springframework.context.aot.AbstractAotProcessor} before it hands off
     * to the application main class. In this phase we must let
     * {@link SpringApplication#run} run so Spring can intercept and emit the sources
     * under {@code target/spring-aot/main/sources} — short-circuiting would break the
     * native build.
     */
    static boolean isAotProcessingPhase() {
        return Boolean.parseBoolean(System.getProperty("spring.aot.processing"));
    }

    /**
     * Returns true when at least one source of BRUME_* configuration is available :
     * a {@code .env.brume} file under {@code baseDir}, a {@code BRUME_HMAC_SECRET} in the OS
     * environment, or a matching system property. Fail-fast guard against the "cloned the
     * repo and ran {@code brume plan}" trap where Spring would otherwise crash on a
     * {@code BindException} that doesn't tell the user to run {@code brume init}.
     *
     * <p>Visible as a package-private test seam so {@link BrumeApplicationEnvGuardTest} can
     * inject a {@code @TempDir} and cover each branch without having to call
     * {@link #main(String[])} (which would call {@link System#exit(int)}). The {@code baseDir}
     * parameter is intentional : {@code Paths.get(".env.brume")} resolves via the UnixFileSystem's
     * {@code defaultDirectory} cached at JVM init, which cannot be overridden by setting
     * {@code user.dir} inside a test.
     */
    static boolean hasAnyEnvSource(java.nio.file.Path baseDir) {
        if (Files.exists(baseDir.resolve(".env.brume"))) return true;
        String hmacEnv = System.getenv("BRUME_HMAC_SECRET");
        if (hmacEnv != null && !hmacEnv.isBlank()) return true;
        String hmacSysProp = System.getProperty("brume.hmac-secret");
        if (hmacSysProp != null && !hmacSysProp.isBlank()) return true;
        String hmacEnvAsSysProp = System.getProperty("BRUME_HMAC_SECRET");
        return hmacEnvAsSysProp != null && !hmacEnvAsSysProp.isBlank();
    }

    private static void guardEnvOrFail() {
        if (hasAnyEnvSource(Paths.get("."))) return;
        failPreBoot("CONFIG_ENV_MISSING",
                "No .env.brume found in the current directory, and BRUME_HMAC_SECRET is "
                        + "not set in the environment.",
                "Run 'brume init jdbc' (or 'brume init dump') to create a .env.brume template, "
                        + "then edit it with your database credentials and secrets. See ADR-0045 / ADR-0047.");
    }

    private static void failPreBoot(String code, String message, String suggestion) {
        System.err.println("[" + code + "] " + message);
        System.err.println("  → " + suggestion);
        System.err.println("  → Exit code: 2");
        System.exit(2);
    }

    /**
     * Forces {@code brume.sink.type=NULL} when a read-only subcommand ({@code plan} or
     * {@code dry-run}) is requested, before {@link SpringApplication#run} starts so that
     * {@code SinkConfig} resolves {@code NullSink} via {@code @ConditionalOnProperty}.
     *
     * <p>This guards against side effects from eager sink wiring — e.g. {@code SqlFileSink}
     * truncating the configured output file at bean creation time, which would clobber
     * a previously produced dump for users running {@code brume plan} as a preview after
     * a real run. The system property has higher precedence than {@code application.yaml},
     * so the user's explicit sink config is temporarily overridden for these read-only
     * commands only.
     */
    private static void applyReadOnlySinkOverride(String[] args) {
        var argList = Arrays.asList(args);
        if (argList.contains("dry-run") || argList.contains("plan")) {
            System.setProperty("brume.sink.type", "NULL");
        }
    }

    private static void loadDotEnv() {
        Dotenv.configure()
                .filename(".env.brume")
                .ignoreIfMissing()
                .load()
                .entries()
                .forEach(e -> System.setProperty(e.getKey(), e.getValue()));
    }
}