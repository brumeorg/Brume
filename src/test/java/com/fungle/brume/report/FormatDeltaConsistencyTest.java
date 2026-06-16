package com.fungle.brume.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for #79h — {@code formatDelta(double)} returned divergent strings between
 * {@link ReportRenderer} and {@link ReportTemplateModelFactory} on the same value.
 *
 * <p>Pre-fix : {@code ReportRenderer.formatDelta(0.0) = " 0%"} (with a leading space, for
 * tabular alignment) ; {@code ReportTemplateModelFactory.formatDelta(0.0) = "0%"}. Same input
 * → two different output strings depending on the rendering surface. The console row's
 * {@code String.format("%5s", ...)} padding already right-aligned the value, so the
 * leading space was redundant + caused the divergence.
 *
 * <p>Post-fix : both surfaces return {@code "0%"} ; console alignment is handled exclusively
 * by the {@code %5s} format specifier at the call sites.
 *
 * <p>The {@link ReportTemplateModelFactory#formatDelta(double)} method is private, so we
 * access it via reflection rather than make it package-visible just for the test.
 */
class FormatDeltaConsistencyTest {

    @Test
    @DisplayName("#79h — formatDelta(0.0) returns '0%' on both ReportRenderer and ReportTemplateModelFactory")
    void zeroDeltaConsistentAcrossSurfaces() throws Exception {
        // Console-side
        assertThat(ReportRenderer.formatDelta(0.0))
                .as("ReportRenderer.formatDelta(0.0) post-fix")
                .isEqualTo("0%");

        // HTML-side (private — reflection)
        Method m = ReportTemplateModelFactory.class.getDeclaredMethod("formatDelta", double.class);
        m.setAccessible(true);
        String htmlOutput = (String) m.invoke(null, 0.0);
        assertThat(htmlOutput)
                .as("ReportTemplateModelFactory.formatDelta(0.0) (private, reflected)")
                .isEqualTo("0%");

        // The two surfaces MUST agree on the same value.
        assertThat(ReportRenderer.formatDelta(0.0))
                .as("console and HTML surfaces must agree on the formatted delta")
                .isEqualTo(htmlOutput);
    }

    @Test
    @DisplayName("#79h — formatDelta on other values stays unchanged on both surfaces")
    void nonZeroDeltaUnchanged() throws Exception {
        Method m = ReportTemplateModelFactory.class.getDeclaredMethod("formatDelta", double.class);
        m.setAccessible(true);

        for (double v : new double[]{1.0, -1.0, 12.5, -8.0, 100.0, -200.0, Double.NaN}) {
            String console = ReportRenderer.formatDelta(v);
            String html = (String) m.invoke(null, v);
            assertThat(console)
                    .as("formatDelta(%f) must agree between console and HTML surfaces", v)
                    .isEqualTo(html);
        }
    }

    @Test
    @DisplayName("#79h — sub-rounding deltas still resolve to ±0% sign (pre-existing behavior)")
    void subRoundingDeltaKeepsSign() {
        // The sub-rounding heuristic (d != 0 but round(d) == 0) preserves sign for the
        // operator's visual cue. Pre-fix and post-fix agree on this.
        assertThat(ReportRenderer.formatDelta(0.4)).isEqualTo("+0%");
        assertThat(ReportRenderer.formatDelta(-0.4)).isEqualTo("-0%");
    }
}
