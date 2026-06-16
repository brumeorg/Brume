package com.fungle.brume.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.regex.Pattern;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.origin;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaConstructor.CONSTRUCTOR_NAME;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Project-wide architectural rules enforced at build time via ArchUnit.
 *
 * <p>Add new rules here as the project grows — this is the single point of evolution
 * for cross-cutting structural constraints. Adding rules in this file is preferred
 * over scattering them across feature packages.
 */
@AnalyzeClasses(
        packages = "com.fungle.brume",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /**
     * Jackson mapper instances ({@link ObjectMapper}, {@code YAMLMapper}, {@code JsonMapper},
     * {@code XmlMapper}, {@code CBORMapper}, …) must be obtained via Spring DI from
     * {@code com.fungle.brume.config.JacksonConfig} or {@code com.fungle.brume.config.ConfigLoader}.
     * Instantiating a fresh mapper per call is expensive and breaks shared configuration
     * (registered modules, naming strategies). #8, B5.
     *
     * <p>The {@code com.fungle.brume.config..} package is exempt — it owns the lifecycle
     * of the singleton beans.
     */
    @ArchTest
    static final ArchRule jackson_mappers_only_instantiated_in_config =
            noClasses()
                    .that().resideOutsideOfPackage("..config..")
                    .should().callCodeUnitWhere(
                            target(name(CONSTRUCTOR_NAME))
                                    .and(target(owner(assignableTo(ObjectMapper.class)))))
                    .as("Jackson mapper subclasses must be Spring-injected from JacksonConfig "
                            + "or ConfigLoader (B5, anti-regression). Add a new mapper bean rather "
                            + "than constructing one ad-hoc.");

    /**
     * {@link Pattern#compile(String)} (and its overload with flags) is expensive: it
     * parses the regex and builds an automaton. To guarantee one-shot compilation per
     * pattern, the call must originate from a static initializer ({@code <clinit>}),
     * which is what {@code private static final Pattern X = Pattern.compile(...)}
     * compiles to. Calls in instance constructors or hot-path methods are forbidden.
     * #9, B6.
     */
    @ArchTest
    static final ArchRule pattern_compile_only_in_static_initializers =
            noClasses()
                    .should().callCodeUnitWhere(
                            target(name("compile"))
                                    .and(target(owner(equivalentTo(Pattern.class))))
                                    .and(not(origin(name("<clinit>")))))
                    .as("Pattern.compile must only run in static initializers (B6, anti-regression). "
                            + "Use a private static final Pattern field rather than compiling per call.");
}
