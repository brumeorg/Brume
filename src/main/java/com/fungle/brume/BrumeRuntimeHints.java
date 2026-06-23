package com.fungle.brume;

import com.fungle.brume.audit.anonymity.AnonymityReport;
import com.fungle.brume.audit.anonymity.EquivalenceClassDistribution;
import com.fungle.brume.audit.anonymity.Recommendation;
import com.fungle.brume.audit.anonymity.SingletonRow;
import com.fungle.brume.audit.anonymity.TableAuditResult;
import com.fungle.brume.checkpoint.CheckpointState;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ColumnReference;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.JsonPathConfig;
import com.fungle.brume.config.model.LinkedColumnsConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.output.BrumeJsonOutput;
import com.fungle.brume.report.ComparisonRow;
import com.fungle.brume.report.ComparisonSummary;
import com.fungle.brume.report.DdlExecutionResult;
import com.fungle.brume.report.DdlFailure;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.HeapStats;
import com.fungle.brume.report.Insight;
import com.fungle.brume.report.BrumeRuntimeContext;
import com.fungle.brume.report.PhaseTimings;
import com.fungle.brume.report.PiiWarning;
import com.fungle.brume.report.PkStructureStats;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.report.QuasiIdWarning;
import com.fungle.brume.report.ReportTemplateModelFactory;
import com.fungle.brume.report.StrategyUsage;
import com.fungle.brume.report.SubstitutionDictStats;
import com.fungle.brume.report.TableStats;
import com.fungle.brume.state.ColumnState;
import com.fungle.brume.state.ExecutionState;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.stream.Stream;

/**
 * GraalVM native-image reflection hints for classes not reachable by Spring AOT.
 *
 * <p>Spring AOT handles {@code @Component}, {@code @ConfigurationProperties}, and Spring
 * beans automatically. This registrar covers the gaps:
 * <ul>
 *   <li>Jackson YAML deserialization targets ({@code config.model.*} — not Spring beans)</li>
 *   <li>Jackson JSON serialization targets ({@code report.*}, {@code output.*}) emitted
 *       on stdout or written to files</li>
 *   <li>JSON state and checkpoint files ({@code state.*}, {@code checkpoint.CheckpointState})</li>
 *   <li>k-anonymity audit report classes ({@code audit.anonymity.*})</li>
 *   <li>Enums used as Jackson fields ({@code Strategy}, {@code SemanticType},
 *       {@code CommandEnum}, inner enums of report records)</li>
 *   <li>Thymeleaf/OGNL accessors on the nested {@code *View} records of
 *       {@link ReportTemplateModelFactory}, plus the HTML templates and CSS they
 *       reference, plus the standard Thymeleaf utility objects
 *       ({@code org.thymeleaf.expression.*} — {@code #lists}, {@code #strings}, …)
 *       (Brume uses raw {@code org.thymeleaf:thymeleaf}, not the Spring Boot
 *       starter, so no Thymeleaf hints are contributed automatically)</li>
 *   <li>Datafaker locale YAML files (jar-root resources like {@code en.yml})
 *       and provider classes ({@code Name.lastName()} et al.) used by
 *       {@code FakeStrategy} — Datafaker 2.x ships no AOT metadata of its own</li>
 * </ul>
 *
 * <p>Activated via {@code @ImportRuntimeHints(BrumeRuntimeHints.class)} on
 * {@link BrumeApplication} and processed by {@code spring-boot:process-aot} during
 * the native build ({@code -Pnative}).
 *
 * <p>Replaces the hand-maintained {@code reflect-config.json} which had incorrect
 * {@code com.fungle.brume.report.*} package names for classes actually in
 * {@code com.fungle.brume.config.model.*}.
 */
public class BrumeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        Stream.of(
                // --- config.model : Jackson YAML deserialization of config.yaml ---
                AnonymizerConfig.class,
                ExtractionConfig.class,
                AnonymizationConfig.class,
                ColumnConfig.class,
                ColumnReference.class,
                JsonPathConfig.class,
                LinkedColumnsConfig.class,
                SemanticType.class,
                Strategy.class,
                TableAnonymizationConfig.class,
                TableExtractionConfig.class,

                // --- state : Jackson JSON for brume-state.json (written by execute, read by audit) ---
                ExecutionState.class,
                ColumnState.class,

                // --- checkpoint : Jackson JSON for brume-checkpoint.json ---
                CheckpointState.class,

                // --- output : top-level JSON wrapper emitted on stdout (--json flag) ---
                BrumeJsonOutput.class,

                // --- report : records included in BrumeJsonOutput and the HTML/plan reports ---
                ComparisonRow.class,
                ComparisonSummary.class,
                DdlExecutionResult.class,
                DdlFailure.class,
                ExecutionSummary.class,
                HeapStats.class,
                Insight.class,
                Insight.Level.class,             // inner enum — Jackson serialises by name
                BrumeRuntimeContext.class,
                PhaseTimings.class,
                PiiWarning.class,
                PkStructureStats.class,
                PlanSummary.class,
                PlanTableStats.class,
                QuasiIdWarning.class,
                StrategyUsage.class,
                SubstitutionDictStats.class,
                SubstitutionDictStats.TopContributor.class,  // inner record
                TableStats.class,

                // --- audit.anonymity : k-anonymity report serialisation ---
                AnonymityReport.class,
                TableAuditResult.class,
                EquivalenceClassDistribution.class,
                Recommendation.class,
                Recommendation.Severity.class,  // inner enum
                SingletonRow.class,

                // --- enums used as Jackson fields in serialised records ---
                CommandEnum.class               // referenced in BrumeRuntimeContext JSON field
        ).forEach(type -> hints.reflection().registerType(type, MemberCategory.values()));

        // --- Thymeleaf/OGNL view records: auto-discovered so new *View additions stay
        // covered without touching this file. OGNL reflects on the record accessors at
        // runtime; without these hints the native image fails with NoSuchPropertyException. ---
        for (Class<?> nested : ReportTemplateModelFactory.class.getDeclaredClasses()) {
            if (nested.isRecord()) {
                hints.reflection().registerType(nested, MemberCategory.values());
            }
        }

        // --- HTML templates + CSS loaded by classpath (ClassLoaderTemplateResolver prefix
        // "templates/", and HtmlReportRenderer's getResourceAsStream("report/report.css")). ---
        hints.resources().registerPattern("templates/report/*.html");
        hints.resources().registerPattern("templates/report/fragments/*.html");
        hints.resources().registerPattern("report/report.css");

        // --- Thymeleaf standard expression utility objects (#lists, #strings, #dates, …).
        // OGNL resolves these via runtime reflection on method signatures, so each utility
        // class must expose its public methods. Registered as a fixed-name list of strings
        // (rather than .class literals) so a missing class in a future Thymeleaf version
        // degrades gracefully — registerType ignores unknown type names. ---
        String[] thymeleafUtilities = {
                "org.thymeleaf.expression.Aggregates",
                "org.thymeleaf.expression.Arrays",
                "org.thymeleaf.expression.Bools",
                "org.thymeleaf.expression.Calendars",
                "org.thymeleaf.expression.Conversions",
                "org.thymeleaf.expression.Dates",
                "org.thymeleaf.expression.ExecutionInfo",
                "org.thymeleaf.expression.Ids",
                "org.thymeleaf.expression.Lists",
                "org.thymeleaf.expression.Maps",
                "org.thymeleaf.expression.Messages",
                "org.thymeleaf.expression.Numbers",
                "org.thymeleaf.expression.Objects",
                "org.thymeleaf.expression.Sets",
                "org.thymeleaf.expression.Strings",
                "org.thymeleaf.expression.Temporals",
                "org.thymeleaf.expression.Uris"
        };
        for (String typeName : thymeleafUtilities) {
            hints.reflection().registerType(
                    org.springframework.aot.hint.TypeReference.of(typeName),
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }

        // --- Datafaker: locale YAML files at the jar root (en.yml, fr.yml, _US.yml, en/dune.yml…)
        // are loaded by FakeValuesService via classpath. Spring translates * to a glob that
        // does NOT cross "/" so we need both "*.yml" (root locales) AND "**/*.yml" (nested
        // topic files like en/dune.yml) to cover the full Datafaker resource layout. ---
        hints.resources().registerPattern("*.yml");
        hints.resources().registerPattern("**/*.yml");

        // --- Datafaker providers: FakeValuesService dispatches reflectively to method-name
        // accessors on these classes (Name.lastName, Address.fullAddress, …). Each provider
        // used by FakeStrategy needs its public methods reachable. AbstractProvider and the
        // service classes are required for the resolution machinery itself. ---
        String[] datafakerTypes = {
                "net.datafaker.Faker",
                "net.datafaker.providers.base.BaseFaker",
                "net.datafaker.providers.base.BaseProviders",
                "net.datafaker.providers.base.AbstractProvider",
                "net.datafaker.providers.base.Name",
                "net.datafaker.providers.base.PhoneNumber",
                "net.datafaker.providers.base.Address",
                "net.datafaker.providers.base.Finance",
                "net.datafaker.providers.base.Internet",
                // FakeValuesService + its inner resolver classes are walked reflectively
                // by Datafaker's resolution chain. Inner classes must be registered
                // explicitly — registering the outer doesn't cover them in AOT.
                "net.datafaker.service.FakeValuesService",
                "net.datafaker.service.FakeValuesService$MethodResolver",
                "net.datafaker.service.FakeValuesService$ConstantResolver",
                "net.datafaker.service.FakeValuesService$SafeFetchResolver",
                "net.datafaker.service.FakeValuesService$MethodAndCoercedArgs",
                "net.datafaker.service.FakeValuesService$MethodAndCoercedArgsResolver",
                "net.datafaker.service.FakeValuesService$RegExpContext",
                "net.datafaker.service.FakeValuesService$ValueResolver",
                "net.datafaker.service.FakeValues",
                "net.datafaker.service.FakeValuesContext",
                "net.datafaker.service.RandomService"
        };
        for (String typeName : datafakerTypes) {
            hints.reflection().registerType(
                    org.springframework.aot.hint.TypeReference.of(typeName),
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }

        // --- Datafaker shaded snakeyaml: parses the locale YAMLs at runtime. Without
        // reflection on the YAML/Constructor/Representer classes the parse silently produces
        // an empty Map and FakeValuesService.resolve returns "<key> resulted in null
        // expression". Datafaker 2.x ships snakeyaml shaded — registering the upstream
        // org.yaml.snakeyaml.* is not enough; the shaded copy under
        // net.datafaker.shaded.snakeyaml.* is what's actually invoked. ---
        String[] snakeyamlTypes = {
                "net.datafaker.shaded.snakeyaml.Yaml",
                "net.datafaker.shaded.snakeyaml.LoaderOptions",
                "net.datafaker.shaded.snakeyaml.DumperOptions",
                "net.datafaker.shaded.snakeyaml.constructor.BaseConstructor",
                "net.datafaker.shaded.snakeyaml.constructor.SafeConstructor",
                "net.datafaker.shaded.snakeyaml.constructor.Constructor",
                "net.datafaker.shaded.snakeyaml.representer.Representer",
                "net.datafaker.shaded.snakeyaml.resolver.Resolver",
                // Node types instantiated by snakeyaml's parser via reflection during YAML
                // tree construction. Without these the parse silently produces empty maps.
                "net.datafaker.shaded.snakeyaml.nodes.MappingNode",
                "net.datafaker.shaded.snakeyaml.nodes.ScalarNode",
                "net.datafaker.shaded.snakeyaml.nodes.SequenceNode",
                "net.datafaker.shaded.snakeyaml.nodes.Tag"
        };
        for (String typeName : snakeyamlTypes) {
            hints.reflection().registerType(
                    org.springframework.aot.hint.TypeReference.of(typeName),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }
}
