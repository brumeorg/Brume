package com.fungle.brume.report;

import com.fungle.brume.config.BrumeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
/**
 * Spring component that generates a self-contained HTML execution report from an
 * {@link ExecutionSummary}.
 *
 * <p>The output is a single {@code .html} file with all CSS embedded — no external
 * dependencies, no JavaScript, no CDN. The file opens correctly offline in any
 * modern browser and in CI artifact viewers.
 *
 * <p>The visual style is inspired by JUnit/Surefire and Cucumber HTML reports:
 * status badge, per-table statistics table, proportional phase timing bar, and
 * color-coded strategy pills.
 *
 * <p>If {@code brumeProperties.report().htmlFile()} is blank, this component does nothing.
 * Any {@link IOException} during file write is logged as WARN and never propagated —
 * the pipeline must never fail for a reporting error.
 */
@Component
public class HtmlReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(HtmlReportRenderer.class);

    private final BrumeProperties brumeProperties;
    private final ThymeleafReportEngine thymeleafReportEngine;
    private final ReportTemplateModelFactory reportTemplateModelFactory;

    /**
     * Creates a new {@code HtmlReportRenderer}.
     *
     * @param brumeProperties application properties, used to read the optional HTML output path
     */
    public HtmlReportRenderer(BrumeProperties brumeProperties) {
        this.brumeProperties = brumeProperties;
        this.thymeleafReportEngine = new ThymeleafReportEngine();
        this.reportTemplateModelFactory = new ReportTemplateModelFactory();
    }

    /**
     * Generates a self-contained HTML report file from the given execution summary.
     * Does nothing if {@code brumeProperties.report().htmlFile()} is blank.
     *
     * @param summary the immutable execution snapshot to render
     */
    public void render(ExecutionSummary summary) {
        String htmlFile = brumeProperties.report().htmlFile();
        if (htmlFile == null || htmlFile.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(htmlFile);
            ensureParentDirs(path);
            String html = renderHtml(summary);
            Files.writeString(path, html, StandardCharsets.UTF_8);
            log.info("HTML report written to {}", htmlFile);
        } catch (IOException e) {
            log.warn("Failed to write HTML report to {}: {}", htmlFile, e.getMessage());
        }
    }

    /**
     * Produces the full HTML document string from the given execution summary.
     * Can be called standalone in unit tests (no file written, no Spring context needed).
     *
     * @param summary the immutable execution snapshot to render
     * @return a complete, self-contained HTML document
     */
    public String renderHtml(ExecutionSummary summary) {
        return renderExecutionDocument(summary);
    }

    /**
     * Appends a "Plan vs Réel" comparison section to the existing HTML report file.
     *
     * <p>If the HTML file does not yet exist or the path is blank, this method does nothing.
     * Any {@link IOException} is logged as WARN and never propagated.
     *
     * @param comparison the immutable comparison snapshot to render
     */
    public void renderComparison(ComparisonSummary comparison) {
        String htmlFile = brumeProperties.report().htmlFile();
        if (htmlFile == null || htmlFile.isBlank()) {
            return;
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(htmlFile);
            ensureParentDirs(path);
            String html = renderExecutionDocument(comparison.execution(), comparison);
            java.nio.file.Files.writeString(path, html, StandardCharsets.UTF_8);
            log.info("HTML comparison report written to {}", htmlFile);
        } catch (IOException e) {
            log.warn("Failed to append HTML comparison to {}: {}", htmlFile, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Plan HTML report
    // -------------------------------------------------------------------------

    /**
     * Generates a self-contained HTML preflight plan report and writes it to the path
     * configured in {@code brume.report.plan-html-file}.
     * Does nothing if that path is blank.
     *
     * @param plan the immutable pre-execution plan snapshot to render
     */
    public void renderPlan(PlanSummary plan) {
        String planFile = brumeProperties.report().planHtmlFile();
        if (planFile == null || planFile.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(planFile);
            ensureParentDirs(path);
            String html = renderPlanHtml(plan);
            Files.writeString(path, html, StandardCharsets.UTF_8);
            log.info("HTML plan report written to {}", planFile);
        } catch (IOException e) {
            log.warn("Failed to write HTML plan report to {}: {}", planFile, e.getMessage());
        }
    }

    /**
     * Produces the full HTML document string for the preflight plan.
     * Can be called standalone in unit tests.
     *
     * @param plan the immutable pre-execution plan snapshot to render
     * @return a complete, self-contained HTML document
     */
    public String renderPlanHtml(PlanSummary plan) {
        return renderPlanDocument(plan);
    }

    /**
     * Produces the HTML snippet for the "Plan vs Réel" section.
     *
     * <p>Can be called standalone in unit tests (no file written, no Spring context needed).
     *
     * @param comparison the immutable comparison snapshot to render
     * @return an HTML {@code <section>} element string
     */
    public String renderComparisonSection(ComparisonSummary comparison) {
        return thymeleafReportEngine.render(
                "report/fragments/comparison",
                reportTemplateModelFactory.comparisonSection(comparison)
        );
    }

    /**
     * Renders the execution HTML document using structured Thymeleaf models.
     *
     * @param summary execution summary to expose to the template
     * @return complete HTML document
     */
    private String renderExecutionDocument(ExecutionSummary summary) {
        return thymeleafReportEngine.render(
                "report/execution",
                reportTemplateModelFactory.executionDocument(buildInlineCss(), summary)
        );
    }

    /**
     * Renders the execution HTML document using structured Thymeleaf models.
     *
     * @param summary execution summary to expose to the template
     * @param comparison optional comparison summary rendered by the same document template
     * @return complete HTML document
     */
    private String renderExecutionDocument(ExecutionSummary summary, ComparisonSummary comparison) {
        return thymeleafReportEngine.render(
                "report/execution",
                reportTemplateModelFactory.executionDocument(buildInlineCss(), summary, comparison)
        );
    }

    /**
     * Renders the complete HTML plan document using the dedicated Thymeleaf template.
     *
     * @param plan immutable pre-execution plan snapshot to render
     * @return complete plan HTML document
     */
    private String renderPlanDocument(PlanSummary plan) {
        return thymeleafReportEngine.render(
                "report/plan",
                reportTemplateModelFactory.planDocument(buildInlineCss(), plan)
        );
    }

    /**
     * Returns the current inline CSS as a raw string so it can be injected into Thymeleaf
     * templates while preserving the current self-contained report behaviour.
     *
     * @return CSS content without surrounding {@code <style>} tags
     */
    private String buildInlineCss() {
        return loadReportCss();
    }

    // -------------------------------------------------------------------------
    // CSS
    // -------------------------------------------------------------------------

    /**
     * Loads the shared report CSS from the classpath so all reports reuse the same source
     * while still inlining it in the final HTML document.
     *
     * @return resource content as UTF-8 text, or an empty string if missing/unreadable
     */
    private String loadReportCss() {
        String resourcePath = "report/report.css";
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                log.warn("Classpath resource not found: {}", resourcePath);
                return "";
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read classpath resource {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Creates the parent directories of {@code path} if they do not already exist.
     * This ensures that report files can be written to sub-folders like {@code reports/}.
     *
     * @param path the target file path whose parent directories should be created
     * @throws IOException if directory creation fails
     */
    private static void ensureParentDirs(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

}