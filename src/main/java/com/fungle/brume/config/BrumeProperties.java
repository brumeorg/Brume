package com.fungle.brume.config;

import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.output.OutputMode;
import com.fungle.brume.plan.PlanMode;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.SinkType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Typed configuration properties for the {@code brume.*} YAML prefix.
 *
 * <p>Centralises all Brume-specific secrets and settings: HMAC secret for
 * deterministic Faker seeding, FPE key for numeric ID encryption, and locale.
 *
 * <p>Registered via {@code @EnableConfigurationProperties} in
 * {@link com.fungle.brume.BrumeApplication}.
 *
 * @param configPath            path to the external {@code config.yaml} anonymization rules file
 * @param hmacSecret            secret used to seed the HMAC for deterministic Faker output
 * @param fpeKey                AES key for FF1/FPE numeric ID encryption (≥16 UTF-8 bytes)
 * @param hmacAlgorithm         HMAC algorithm name (default: {@code HmacSHA256})
 * @param fakerLocale           locale code used by Datafaker (default: {@code fr})
 * @param maxBatchErrorRate     maximum allowed batch error rate per table (default: {@code 0.0} = no errors tolerated)
 * @param maxTargetRows         hard guardrail on the planned total number of rows to write (default: {@code 0} = disabled)
 * @param heapWarningThresholdPercent warning threshold for JVM heap usage (default: {@code 85})
 * @param pipelineMode          extract/anonymize/write orchestration mode (default: {@code STREAMING})
 * @param substitutionDict      substitution dictionary configuration (default: max 1M entries)
 * @param report                report output configuration (default: empty, JSON output disabled)
 */
@ConfigurationProperties(prefix = "brume")
public record BrumeProperties(
        String configPath,
        String hmacSecret,
        String fpeKey,
        @DefaultValue("HmacSHA256") String hmacAlgorithm,
        @DefaultValue("fr") String fakerLocale,
        @DefaultValue("0.0") double maxBatchErrorRate,
        @DefaultValue("0") long maxTargetRows,
        @DefaultValue("85") int heapWarningThresholdPercent,
        @DefaultValue("STREAMING") PipelineMode pipelineMode,
        @DefaultValue SubstitutionDictProperties substitutionDict,
        @DefaultValue("") ReportProperties report,
        @DefaultValue SinkProperties sink,
        @DefaultValue PlanProperties plan,
        @DefaultValue("false") boolean strictConfig,
        @DefaultValue OutputProperties output,
        @DefaultValue TimeoutsProperties timeouts,
        @DefaultValue AuditProperties audit,
        @DefaultValue PreflightProperties preflight,
        @DefaultValue CheckpointProperties checkpoint
) {

    @ConstructorBinding
    public BrumeProperties {
    }

    /**
     * Custom toString that masks {@code hmacSecret} and {@code fpeKey}.
     *
     * <p>Without this override, the record's auto-generated toString would print every field
     * verbatim. Any incidental {@code log.info("config: {}", props)} or stack trace
     * containing this object would leak the HMAC secret and the FPE key into the log
     * stream. The masked form preserves the rest of the config (useful for debugging
     * pipeline mode, sink type, etc.) while hiding the two secrets.
     *
     * <p>Tracked under #15 / ADR-0025.
     */
    @Override
    public String toString() {
        return "BrumeProperties[configPath=" + configPath
                + ", hmacSecret=***"
                + ", fpeKey=***"
                + ", hmacAlgorithm=" + hmacAlgorithm
                + ", fakerLocale=" + fakerLocale
                + ", maxBatchErrorRate=" + maxBatchErrorRate
                + ", maxTargetRows=" + maxTargetRows
                + ", heapWarningThresholdPercent=" + heapWarningThresholdPercent
                + ", pipelineMode=" + pipelineMode
                + ", substitutionDict=" + substitutionDict
                + ", report=" + report
                + ", sink=" + sink
                + ", plan=" + plan
                + ", strictConfig=" + strictConfig
                + ", output=" + output
                + ", timeouts=" + timeouts
                + ", audit=" + audit
                + ", preflight=" + preflight
                + ", checkpoint=" + checkpoint
                + "]";
    }

    /**
     * 18-arg overload kept for tests written before {@code checkpoint} was added
     * (#25 / ADR-0037). Defaults {@code checkpoint} to opt-in {@code false}.
     */
    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            long maxTargetRows,
            int heapWarningThresholdPercent,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report,
            SinkProperties sink,
            PlanProperties plan,
            boolean strictConfig,
            OutputProperties output,
            TimeoutsProperties timeouts,
            AuditProperties audit,
            PreflightProperties preflight) {
        this(
                configPath, hmacSecret, fpeKey, hmacAlgorithm, fakerLocale,
                maxBatchErrorRate, maxTargetRows, heapWarningThresholdPercent,
                pipelineMode, substitutionDict, report, sink, plan, strictConfig,
                output, timeouts, audit, preflight,
                CheckpointProperties.defaults()
        );
    }

    /**
     * 17-arg overload kept for tests written before {@code preflight} was added (ADR-0036, #73).
     * Defaults {@code preflight} to {@link PreflightProperties#defaults()} (FULL mode).
     */
    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            long maxTargetRows,
            int heapWarningThresholdPercent,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report,
            SinkProperties sink,
            PlanProperties plan,
            boolean strictConfig,
            OutputProperties output,
            TimeoutsProperties timeouts,
            AuditProperties audit) {
        this(
                configPath, hmacSecret, fpeKey, hmacAlgorithm, fakerLocale,
                maxBatchErrorRate, maxTargetRows, heapWarningThresholdPercent,
                pipelineMode, substitutionDict, report, sink, plan, strictConfig,
                output, timeouts, audit,
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    /**
     * 16-arg overload kept for tests written before {@code audit} was added (ADR-0035, #21c).
     * Defaults {@code audit} to enabled + builtin fr+en patterns + {FAKE, NULLIFY, MASK}
     * neutralizing strategies via {@link AuditProperties#defaults()}.
     */
    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            long maxTargetRows,
            int heapWarningThresholdPercent,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report,
            SinkProperties sink,
            PlanProperties plan,
            boolean strictConfig,
            OutputProperties output,
            TimeoutsProperties timeouts) {
        this(
                configPath, hmacSecret, fpeKey, hmacAlgorithm, fakerLocale,
                maxBatchErrorRate, maxTargetRows, heapWarningThresholdPercent,
                pipelineMode, substitutionDict, report, sink, plan, strictConfig,
                output, timeouts,
                AuditProperties.defaults()
        );
    }

    /**
     * 15-arg overload kept for tests written before {@code timeouts} was added (ADR-0033).
     * Defaults {@code timeouts} to disabled-statement + disabled-total via
     * {@link TimeoutsProperties#defaults()} and {@code audit} to its production defaults.
     */
    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            long maxTargetRows,
            int heapWarningThresholdPercent,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report,
            SinkProperties sink,
            PlanProperties plan,
            boolean strictConfig,
            OutputProperties output) {
        this(
                configPath, hmacSecret, fpeKey, hmacAlgorithm, fakerLocale,
                maxBatchErrorRate, maxTargetRows, heapWarningThresholdPercent,
                pipelineMode, substitutionDict, report, sink, plan, strictConfig,
                output,
                TimeoutsProperties.defaults(),
                AuditProperties.defaults(),
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    /**
     * 14-arg overload kept for tests written before {@code output} was added (ADR-0030).
     * Defaults {@code output} to TEXT mode.
     */
    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            long maxTargetRows,
            int heapWarningThresholdPercent,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report,
            SinkProperties sink,
            PlanProperties plan,
            boolean strictConfig) {
        this(
                configPath, hmacSecret, fpeKey, hmacAlgorithm, fakerLocale,
                maxBatchErrorRate, maxTargetRows, heapWarningThresholdPercent,
                pipelineMode, substitutionDict, report, sink, plan, strictConfig,
                new OutputProperties(OutputMode.TEXT),
                TimeoutsProperties.defaults(),
                AuditProperties.defaults(),
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    /**
     * 13-arg overload kept for tests written before {@code strictConfig} was added.
     * Defaults {@code strictConfig} to {@code false} (the production-default behavior)
     * and {@code output} to TEXT mode.
     */
    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            long maxTargetRows,
            int heapWarningThresholdPercent,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report,
            SinkProperties sink,
            PlanProperties plan) {
        this(
                configPath, hmacSecret, fpeKey, hmacAlgorithm, fakerLocale,
                maxBatchErrorRate, maxTargetRows, heapWarningThresholdPercent,
                pipelineMode, substitutionDict, report, sink, plan, false,
                new OutputProperties(OutputMode.TEXT),
                TimeoutsProperties.defaults(),
                AuditProperties.defaults(),
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            long maxTargetRows,
            int heapWarningThresholdPercent,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report) {
        this(
                configPath,
                hmacSecret,
                fpeKey,
                hmacAlgorithm,
                fakerLocale,
                maxBatchErrorRate,
                maxTargetRows,
                heapWarningThresholdPercent,
                pipelineMode,
                substitutionDict,
                report,
                new SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT),
                false,
                new OutputProperties(OutputMode.TEXT),
                TimeoutsProperties.defaults(),
                AuditProperties.defaults(),
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report) {
        this(
                configPath,
                hmacSecret,
                fpeKey,
                hmacAlgorithm,
                fakerLocale,
                maxBatchErrorRate,
                0L,
                85,
                PipelineMode.STREAMING,
                substitutionDict,
                report,
                new SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT),
                false,
                new OutputProperties(OutputMode.TEXT),
                TimeoutsProperties.defaults(),
                AuditProperties.defaults(),
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            PipelineMode pipelineMode,
            SubstitutionDictProperties substitutionDict,
            ReportProperties report) {
        this(
                configPath,
                hmacSecret,
                fpeKey,
                hmacAlgorithm,
                fakerLocale,
                maxBatchErrorRate,
                0L,
                85,
                pipelineMode,
                substitutionDict,
                report,
                new SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT),
                false,
                new OutputProperties(OutputMode.TEXT),
                TimeoutsProperties.defaults(),
                AuditProperties.defaults(),
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    public BrumeProperties(
            String configPath,
            String hmacSecret,
            String fpeKey,
            String hmacAlgorithm,
            String fakerLocale,
            double maxBatchErrorRate,
            ReportProperties report) {
        this(
                configPath,
                hmacSecret,
                fpeKey,
                hmacAlgorithm,
                fakerLocale,
                maxBatchErrorRate,
                0L,
                85,
                PipelineMode.STREAMING,
                new SubstitutionDictProperties(1_000_000L),
                report,
                new SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT),
                false,
                new OutputProperties(OutputMode.TEXT),
                TimeoutsProperties.defaults(),
                AuditProperties.defaults(),
                PreflightProperties.defaults(),
                CheckpointProperties.defaults()
        );
    }

    /**
     * Substitution dictionary configuration.
     *
     * <p>The dictionary is used to maintain cross-table consistency: the same real value
     * always produces the same anonymized output across all tables (linked columns).
     *
     * <p>No eviction strategy is implemented: evicting entries would break the
     * cross-table determinism guarantee. Instead, a hard limit is enforced to
     * prevent OOM on datasets with millions of unique values.
     *
     * @param maxEntries maximum number of distinct values that can be stored in the dictionary (default: 1,000,000)
     */
    public record SubstitutionDictProperties(
            @DefaultValue("1000000") long maxEntries
    ) {}

    /**
     * Report output configuration.
     *
     * @param jsonFile      path to write the JSON execution report; blank to disable JSON output
     * @param htmlFile      path to write the HTML execution report; blank to disable HTML output
     * @param planHtmlFile  path to write the HTML preflight plan report; blank to disable
     */
    public record ReportProperties(
            @DefaultValue("") String jsonFile,
            @DefaultValue("") String htmlFile,
            @DefaultValue("") String planHtmlFile
    ) {}

    /**
     * Sink configuration — selects the write target at runtime.
     *
     * @param type            sink implementation to use (default: {@link SinkType#JDBC})
     * @param outputPath      path to the dump file when {@code type=DUMP}; required in DUMP mode,
     *                        ignored in JDBC mode
     * @param compression     output compression to apply when {@code type=DUMP} (default:
     *                        {@link CompressionType#GZIP}); ignored in JDBC mode
     * @param jdbc            JDBC-specific tuning (only effective when {@code type=JDBC})
     * @param stripTimestamps when {@code true} (default {@code false}), omits the
     *                        {@code -- generated_at: ...} line from the dump header so two
     *                        deterministic runs produce byte-identical files (useful for
     *                        {@code diff}, CI gating, audit workflows). Tracked under #25d
     *                        — couples with #25c table-order determinism. Can be flipped
     *                        pre-boot via the {@code --strip-timestamps} CLI flag.
     */
    public record SinkProperties(
            @DefaultValue("JDBC") SinkType type,
            String outputPath,
            @DefaultValue("GZIP") CompressionType compression,
            @DefaultValue JdbcSinkProperties jdbc,
            @DefaultValue("false") boolean stripTimestamps
    ) {
        /** Canonical compact ctor — annotated so Spring binds via this 5-arg signature
         * rather than the 4-arg compat overload below. */
        @ConstructorBinding
        public SinkProperties {
        }

        /** 4-arg overload kept for tests written before {@code stripTimestamps} was added (#25d). */
        public SinkProperties(SinkType type, String outputPath, CompressionType compression,
                              JdbcSinkProperties jdbc) {
            this(type, outputPath, compression, jdbc, false);
        }
    }

    /**
     * Tuning specific to {@code JdbcSink}.
     *
     * @param copyMode write strategy: NEVER (INSERT only), PREFER (COPY with INSERT
     *                 fallback, default) or FORCE (COPY only)
     */
    public record JdbcSinkProperties(
            @DefaultValue("PREFER") CopyMode copyMode
    ) {}

    /**
     * Tuning specific to {@code PlanEstimator}.
     *
     * @param mode plan strategy: EXACT (default — accurate {@code COUNT(*)}, can be
     *             slow on large databases) or ESTIMATE (fast {@code pg_class.reltuples}
     *             approximation, ignores filters and FK references — upper bound, ±20%)
     */
    public record PlanProperties(
            @DefaultValue("EXACT") PlanMode mode
    ) {}

    /**
     * Console output configuration — selects how the run result and logs are formatted.
     *
     * <p>Bound from {@code brume.output.mode}; can also be set pre-boot via the
     * {@code --json} CLI flag (handled in {@link com.fungle.brume.BrumeApplication}).
     *
     * @param mode {@link OutputMode#TEXT} for ASCII tables + plain logs (default),
     *             {@link OutputMode#JSON} for a machine-readable JSON wrapper on stdout
     *             plus structured JSON logs on stderr
     */
    public record OutputProperties(
            @DefaultValue("TEXT") OutputMode mode
    ) {}

    /**
     * Timeouts configuration — guards against indefinite hangs on bounded source queries
     * and on the overall pipeline run.
     *
     * <p>Tracked under #23 (A21) / ADR-0033. Cf. {@code analyse/03-timeouts-cadrage-option-A-2026-05-12.md}.
     *
     * @param statementSeconds Statement timeout applied via {@code SET LOCAL statement_timeout}
     *                         on bounded source queries — {@code COUNT(*)}, FK closure,
     *                         schema introspection. Excludes cursor scans and COPY FROM stdin
     *                         (those are unbounded by design and reaching the limit would be
     *                         a false positive). {@code 0} disables. Default {@code 600s} (10 min).
     * @param totalRunSeconds  Hard cap on the full {@code ReplicationAgent.run()} wall-clock
     *                         time. When fired, the pipeline thread is cancelled, every
     *                         registered JDBC {@link java.sql.Connection} is aborted, every
     *                         tracked subprocess is destroyed forcibly (reuses {@code #24}
     *                         infrastructure), and a {@code RunTimeoutException} surfaces
     *                         with exit code {@code 7}. After a 5s grace period the JVM is
     *                         halted to prevent zombie processes in CI/cron. {@code 0}
     *                         disables. Default {@code 0} (disabled).
     */
    public record TimeoutsProperties(
            @DefaultValue("600") int statementSeconds,
            @DefaultValue("0") int totalRunSeconds
    ) {
        /** Compact constructor — rejects negative values fail-fast at boot. */
        public TimeoutsProperties {
            if (statementSeconds < 0) {
                throw new IllegalArgumentException(
                        "brume.timeouts.statement-seconds must be >= 0 (0 disables), was " + statementSeconds);
            }
            if (totalRunSeconds < 0) {
                throw new IllegalArgumentException(
                        "brume.timeouts.total-run-seconds must be >= 0 (0 disables), was " + totalRunSeconds);
            }
        }

        public static TimeoutsProperties defaults() {
            return new TimeoutsProperties(600, 0);
        }
    }

    /**
     * Audit configuration — pre-execution heuristic checks that surface re-identification
     * risks in the plan report. Tracked under #21c / ADR-0035.
     *
     * <p>Today the only audit performed is quasi-identifier detection by name pattern.
     * When a column matches a quasi-id heuristic AND its effective anonymization strategy
     * does not break correlation (i.e. is not in {@link #neutralizingStrategies}), a
     * {@link com.fungle.brume.report.QuasiIdWarning} is emitted in the plan section
     * {@code [QUASI-ID]}. Warnings are never fatal — the operator may choose KEEP/HASH
     * deliberately (e.g. for k-anonymity-validated datasets).
     *
     * <p>Rationale (ADR-0035): HASH and FPE_* are deterministic by construction (HMAC
     * seeded). Two records with the same source value produce the same anonymized output,
     * preserving the correlation a re-identification attack relies on. Only randomized or
     * destructive strategies (FAKE seeded per-row, NULLIFY, MASK) break that correlation.
     *
     * @param quasiIdEnabled       master switch. When {@code false}, the detector is
     *                             skipped entirely (no [QUASI-ID] section in the plan).
     *                             Default {@code true}. Can be flipped pre-boot via the
     *                             {@code --no-quasi-id-warn} CLI flag.
     * @param quasiIdPatterns      lower-case substring patterns matched against column
     *                             names. When the YAML provides a non-empty list, it
     *                             <strong>replaces</strong> the built-in defaults
     *                             ({@link #DEFAULT_QUASI_ID_PATTERNS}, fr+en) — there is
     *                             no merge semantics. If the user wants to extend, the
     *                             README documents the default list to copy.
     * @param neutralizingStrategies strategies whose presence on a quasi-id column
     *                               suppresses the warning. Default {@code {FAKE, NULLIFY,
     *                               MASK}}; {@code HASH}, {@code FPE_ID}, {@code FPE_UUID}
     *                               and {@code KEEP} are intentionally excluded because
     *                               their deterministic nature preserves correlation.
     *                               Configurable so an operator who has validated
     *                               k-anonymity downstream can whitelist HASH.
     */
    public record AuditProperties(
            @DefaultValue("true") boolean quasiIdEnabled,
            List<String> quasiIdPatterns,
            Set<Strategy> neutralizingStrategies) {

        /**
         * Built-in quasi-identifier patterns. Lower-case substrings matched against the
         * lower-cased column name (same convention as {@link com.fungle.brume.plan.PiiDetector}).
         *
         * <p>The list deliberately excludes patterns already covered by {@code PiiDetector}
         * for text columns (e.g. "birth" is in both because {@code PiiDetector} skips
         * non-text types — a {@code birth_date date} column needs the quasi-id detector
         * to be flagged at all).
         *
         * <p>Bilingual fr+en since Brume's user base is primarily French-speaking
         * (locale default {@code fr}, AGENTS.md in French, partner schemas in French).
         */
        public static final List<String> DEFAULT_QUASI_ID_PATTERNS = List.of(
                // Demographics — date of birth and age
                "birth", "dob", "naissance", "age",
                // Geography — postal codes
                "postal", "zip",
                // Identity attributes
                "gender", "sex", "sexe", "genre",
                "nationality", "nationalite", "citizenship",
                "ethnicity", "ethnie", "race",
                "religion",
                "marital", "situation_familiale", "civilite",
                // Socio-economic
                "salary", "salaire", "income", "revenu",
                "profession", "occupation", "metier",
                "disability", "handicap"
        );

        /** Default neutralizing strategies — randomized or destructive only. */
        public static final Set<Strategy> DEFAULT_NEUTRALIZING_STRATEGIES =
                EnumSet.of(Strategy.FAKE, Strategy.NULLIFY, Strategy.MASK);

        /**
         * Compact constructor — substitutes built-in defaults when the YAML omits the
         * list/set. Spring Boot binds an absent collection property to {@code null}; the
         * defaults kick in here rather than via {@code @DefaultValue} (which only
         * supports primitives and strings).
         *
         * <p>Defensive copy of provided collections so the configuration is immutable
         * after binding.
         */
        public AuditProperties {
            quasiIdPatterns = (quasiIdPatterns == null || quasiIdPatterns.isEmpty())
                    ? DEFAULT_QUASI_ID_PATTERNS
                    : List.copyOf(quasiIdPatterns);
            neutralizingStrategies = (neutralizingStrategies == null || neutralizingStrategies.isEmpty())
                    ? DEFAULT_NEUTRALIZING_STRATEGIES
                    : EnumSet.copyOf(neutralizingStrategies);
        }

        /** Returns the production defaults (audit enabled, built-in lists). */
        public static AuditProperties defaults() {
            return new AuditProperties(true, DEFAULT_QUASI_ID_PATTERNS, DEFAULT_NEUTRALIZING_STRATEGIES);
        }
    }

    /**
     * Preflight configuration — selects which subset of {@link
     * com.fungle.brume.preflight.PreflightCheckRunner} checks runs at boot.
     *
     * <p>The {@link Mode#FULL} default is the historical behavior used by {@code
     * execute}, {@code plan}, {@code dry-run} : 7 sub-checks covering pg_dump, source
     * connectivity, USAGE on source schema, and target CREATE/ownership.
     *
     * <p>{@link Mode#AUDIT} is selected pre-boot by {@code BrumeApplication} when the
     * {@code audit} subcommand is detected (#73 / ADR-0036). In this mode the runner
     * skips the source-side checks (1-5) and the target write-side checks (ownership
     * for {@code DROP SCHEMA CASCADE}), keeping only the target reachability probe.
     * Rationale : audit only reads the target ; the source may be down post-migration
     * and {@code DROP SCHEMA CASCADE} is never invoked.
     *
     * @param mode preflight intensity — defaults to {@link Mode#FULL}
     */
    public record PreflightProperties(
            @DefaultValue("FULL") Mode mode) {

        /** Preflight intensity. */
        public enum Mode {
            /** Historical 7 sub-checks (pg_dump + source + target write privileges). */
            FULL,
            /** Audit-only : skip source-side checks + ownership probe ; keep target reachability. */
            AUDIT
        }

        /** Returns the production default ({@link Mode#FULL}). */
        public static PreflightProperties defaults() {
            return new PreflightProperties(Mode.FULL);
        }
    }

    /**
     * Crash-resume configuration (#25 / ADR-0037).
     *
     * <p>Opt-in V1 ({@code enabled=false} default) — Brume does not write a
     * checkpoint file unless explicitly asked. Either set
     * {@code brume.checkpoint.enabled=true} in YAML to enable preventive
     * checkpointing during normal runs, or pass {@code --resume <path>} on the
     * CLI which auto-enables checkpointing AND uses {@code <path>} as both the
     * read source and the write destination.
     *
     * <p>V1 scope : STREAMING pipeline mode + JDBC sink only. BATCH and DUMP
     * resume are tracked in {@code #25h} and {@code #25f} respectively.
     *
     * @param enabled when {@code true}, {@code CheckpointService} writes the
     *                checkpoint after each completed table. Default {@code false}.
     * @param path    location of the checkpoint JSON file (relative to the JVM
     *                cwd unless absolute). Default {@code "brume-checkpoint.json"}.
     */
    public record CheckpointProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("brume-checkpoint.json") String path) {

        /** Returns the production default (opt-in disabled, standard file name). */
        public static CheckpointProperties defaults() {
            return new CheckpointProperties(false, "brume-checkpoint.json");
        }
    }
}
