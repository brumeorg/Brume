package com.fungle.brume.report;

import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.ReplicationProperties;

/**
 * Immutable snapshot of the runtime context that surrounds a Brume run.
 *
 * <p>Captures four pieces of meta-information needed by the HTML / JSON reports that
 * are <em>not</em> in {@link ExecutionSummary} or {@link PlanSummary} themselves :
 *
 * <ul>
 *   <li>{@code brumeVersion} — the version string read from the JAR manifest
 *       ({@code Implementation-Version}), or {@code "dev"} when running from sources.</li>
 *   <li>{@code configPath} — the path of the anonymization rules file
 *       ({@code brume.config-path}). Surfaced in the masthead so a reader can trace
 *       which rules drove the run.</li>
 *   <li>{@code fakerLocale} — the locale code used by Datafaker for synthetic values
 *       ({@code brume.faker-locale}, default {@code fr}).</li>
 *   <li>{@code ddlErrorMode} — {@code STRICT} or {@code LENIENT}, from
 *       {@link ReplicationProperties#ddlErrorMode()}.</li>
 *   <li>{@code command} — the CLI sub-command that produced this report
 *       ({@code "execute"}, {@code "dry-run"}, {@code "plan"}, {@code "audit"}, or {@code ""}).
 *       Surfaced as a pill in the report masthead so a reader can tell at a glance
 *       whether the report comes from a real run, a dry-run, or a plan-only invocation.</li>
 * </ul>
 *
 * <p>Assembled once at the start of a run by {@link com.fungle.brume.agent.ReplicationAgent}
 * (or {@link com.fungle.brume.plan.PlanEstimator} for plan-only runs), then carried by
 * {@link ExecutionSummary#runtimeContext()} and {@link PlanSummary#runtimeContext()}.
 *
 * <p>Exposed in the JSON report as a sibling object (additive change, ADR-0030 v1
 * schema unchanged for pre-existing fields — Q6 of the V1 integration plan).
 *
 * @param brumeVersion   version string from the JAR manifest, or {@code "dev"}
 * @param configPath     path of the anonymization rules file
 * @param fakerLocale    locale code used by Datafaker
 * @param ddlErrorMode   {@code STRICT} or {@code LENIENT}
 * @param command        CLI sub-command that produced the report (lowercase, with hyphens)
 */
public record BrumeRuntimeContext(
        String brumeVersion,
        String configPath,
        String fakerLocale,
        String ddlErrorMode,
        String command
) {

    private static final String DEV_VERSION = "dev";

    /**
     * Returns an empty context with safe defaults — used by tests and by paths that do
     * not have the runtime properties wired (e.g. unit tests building a summary by hand).
     *
     * @return a context with {@code "dev"}, blank paths, {@code "STRICT"} and blank command
     */
    public static BrumeRuntimeContext empty() {
        return new BrumeRuntimeContext(DEV_VERSION, "", "fr", "STRICT", "");
    }

    /**
     * Assembles a context from the live properties and the CLI sub-command. The Brume
     * version is resolved from the JAR manifest at call time.
     *
     * @param brumeProperties        application-wide Brume properties (locale, config path)
     * @param replicationProperties  replication properties (DDL error mode)
     * @param command                CLI sub-command that produced this report (may be {@code null})
     * @return a fully-populated runtime context
     */
    public static BrumeRuntimeContext of(
            BrumeProperties brumeProperties,
            ReplicationProperties replicationProperties,
            CommandEnum command
    ) {
        return new BrumeRuntimeContext(
                resolveBrumeVersion(),
                brumeProperties.configPath(),
                brumeProperties.fakerLocale(),
                replicationProperties.ddlErrorMode().name(),
                commandLabel(command)
        );
    }

    /**
     * Renders the {@link CommandEnum} as a lowercase, hyphenated label suitable for the
     * report masthead pill: {@code "execute"}, {@code "dry-run"}, {@code "plan"},
     * {@code "audit"}, or {@code ""} when no command is known.
     */
    private static String commandLabel(CommandEnum command) {
        if (command == null) {
            return "";
        }
        return command.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /**
     * Resolves the Brume version from the JAR manifest.
     *
     * <p>Returns {@code "dev"} when the implementation version is absent (typical when
     * running from an IDE or unit tests).
     *
     * @return the version string or {@code "dev"}
     */
    private static String resolveBrumeVersion() {
        String version = BrumeRuntimeContext.class.getPackage().getImplementationVersion();
        return (version == null || version.isBlank()) ? DEV_VERSION : version;
    }
}
