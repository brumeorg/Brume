package com.fungle.brume.report;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight Thymeleaf rendering engine for Brume reports in CLI mode.
 *
 * <p>This engine intentionally uses Thymeleaf standalone (no Spring MVC, no HTTP layer)
 * and resolves templates from the application classpath. It returns rendered HTML as a
 * plain string so that {@link HtmlReportRenderer} can keep its existing public API.
 */
public class ThymeleafReportEngine {

    private final TemplateEngine templateEngine;

    /**
     * Creates a new engine backed by a classpath resolver rooted at {@code templates/}.
     */
    public ThymeleafReportEngine() {
        this(createDefaultTemplateEngine());
    }

    /**
     * Creates a new engine with the given Thymeleaf {@link TemplateEngine}.
     *
     * @param templateEngine configured Thymeleaf engine
     */
    public ThymeleafReportEngine(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Renders the given template with the provided model.
     *
     * @param templateName classpath-relative template name without suffix
     * @param model        template variables
     * @return rendered HTML string
     */
    public String render(String templateName, Map<String, Object> model) {
        Context context = new Context(Locale.FRANCE);
        context.setVariables(model);
        return templateEngine.process(templateName, context);
    }

    private static TemplateEngine createDefaultTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}

