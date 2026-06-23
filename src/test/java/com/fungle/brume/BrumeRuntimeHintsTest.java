package com.fungle.brume;

import com.fungle.brume.report.ReportTemplateModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the GraalVM AOT hints contributed by {@link BrumeRuntimeHints} for the
 * Thymeleaf report pipeline. Regression guard for the native-image bug where
 * OGNL failed on {@code PlanMastheadView.reportTitle} because the nested view
 * records were not registered for reflection, and the HTML/CSS resources were
 * missing from the image.
 */
class BrumeRuntimeHintsTest {

    @Test
    @DisplayName("every nested *View record of ReportTemplateModelFactory is registered for reflection (Thymeleaf/OGNL accessors)")
    void allReportViewRecordsAreReflectivelyAccessible() {
        RuntimeHints hints = new RuntimeHints();
        new BrumeRuntimeHints().registerHints(hints, getClass().getClassLoader());

        var viewRecords = Arrays.stream(ReportTemplateModelFactory.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .toList();

        assertThat(viewRecords)
                .as("ReportTemplateModelFactory must expose at least one nested *View record")
                .isNotEmpty();

        for (Class<?> view : viewRecords) {
            assertThat(RuntimeHintsPredicates.reflection().onType(view).test(hints))
                    .as("nested view record %s must be registered for AOT reflection", view.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Thymeleaf utility classes used in templates (#lists, #strings) are registered for OGNL reflection")
    void thymeleafUtilityClassesAreReflectivelyAccessible() {
        RuntimeHints hints = new RuntimeHints();
        new BrumeRuntimeHints().registerHints(hints, getClass().getClassLoader());

        // #lists and #strings are the only utilities currently used by the templates;
        // locking them here catches a regression on the exact #lists.size(...) call site
        // that failed in the native image.
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of("org.thymeleaf.expression.Lists")).test(hints))
                .as("org.thymeleaf.expression.Lists must be registered for OGNL method resolution")
                .isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of("org.thymeleaf.expression.Strings")).test(hints))
                .as("org.thymeleaf.expression.Strings must be registered for OGNL method resolution")
                .isTrue();
    }

    @Test
    @DisplayName("HTML templates and CSS loaded via classpath are registered as native-image resources")
    void reportClasspathResourcesAreRegistered() {
        RuntimeHints hints = new RuntimeHints();
        new BrumeRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource("templates/report/plan.html").test(hints))
                .as("plan.html template must be reachable")
                .isTrue();
        assertThat(RuntimeHintsPredicates.resource().forResource("templates/report/fragments/plan-masthead-v2.html").test(hints))
                .as("plan-masthead-v2 fragment must be reachable")
                .isTrue();
        assertThat(RuntimeHintsPredicates.resource().forResource("report/report.css").test(hints))
                .as("report.css must be reachable")
                .isTrue();
    }

    @Test
    @DisplayName("Datafaker provider classes and locale YAML resources used by FakeStrategy are registered")
    void datafakerProvidersAndLocaleYamlsAreRegistered() {
        RuntimeHints hints = new RuntimeHints();
        new BrumeRuntimeHints().registerHints(hints, getClass().getClassLoader());

        // The exact lookup that failed in the native image: Name.lastName resolves
        // "name.last_name" via reflection + a locale YAML. Both must be reachable.
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of("net.datafaker.providers.base.Name")).test(hints))
                .as("net.datafaker.providers.base.Name must be registered (Name.lastName reflective dispatch)")
                .isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of("net.datafaker.service.FakeValuesService")).test(hints))
                .as("FakeValuesService (expression resolver) must be registered")
                .isTrue();
        assertThat(RuntimeHintsPredicates.resource().forResource("en.yml").test(hints))
                .as("Datafaker locale YAML at jar root must be reachable")
                .isTrue();
    }
}
