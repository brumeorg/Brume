package com.fungle.brume.audit.anonymity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the three audit formatters.
 */
class AnonymityRenderersTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private AnonymityReport sampleReport(boolean strict, long policyViolatingKMin) {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(1L, 1L, 3L));
        List<SingletonRow> singletons = List.of(
                new SingletonRow(List.of("1985-03-15", "75001")),
                new SingletonRow(List.of("1992-07-22", "13002"))
        );
        List<Recommendation> recs = KAnonymityCalculator.recommendationsFor(
                "users", List.of("birth_date", "postal_code"), d);
        TableAuditResult tr = new TableAuditResult(
                "users", List.of("birth_date", "postal_code"), d, singletons, 1.0, recs);
        return new AnonymityReport(
                "test_brume",
                Instant.parse("2026-05-12T22:00:00Z"),
                List.of(tr),
                strict,
                policyViolatingKMin,
                1L);
    }

    // -------------------------------------------------------------------------
    // Text
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("text renderer contains k_min, distribution, singletons, recommendations and methodology")
    void textRendersAllSections() {
        AnonymityReport r = sampleReport(true, 5);
        String text = AnonymityTextRenderer.render(r);
        assertThat(text).contains("Audit anonymité");
        assertThat(text).contains("Schéma cible : test_brume");
        assertThat(text).contains("Table : users");
        assertThat(text).contains("k_min       : 1");
        assertThat(text).contains("Singletons");
        assertThat(text).contains("75001");
        assertThat(text).contains("Recommandations");
        assertThat(text).contains("CRITICAL");
        assertThat(text).contains("Méthodologie & limites");
        assertThat(text).contains("Considérant 26");
    }

    @Test
    @DisplayName("text renderer mentions sampling when sample-rate < 1.0")
    void textMentionsSampling() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(3L, 5L));
        TableAuditResult tr = new TableAuditResult(
                "users", List.of("birth_date"), d, List.of(), 0.1,
                KAnonymityCalculator.recommendationsFor("users", List.of("birth_date"), d));
        AnonymityReport r = new AnonymityReport(
                "test_brume", Instant.now(), List.of(tr), false, 5, 3L);
        String text = AnonymityTextRenderer.render(r);
        assertThat(text).contains("Échantillon : 10.00%");
        assertThat(text).contains("biais possible");
    }

    // -------------------------------------------------------------------------
    // JSON
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("JSON renderer wraps report with top-level policyViolated flag")
    void jsonContainsPolicyViolated() {
        AnonymityReport r = sampleReport(true, 5);
        String json = AnonymityJsonRenderer.render(r, objectMapper);
        assertThat(json).contains("\"policyViolated\" : true");
        assertThat(json).contains("\"schema\" : \"test_brume\"");
        assertThat(json).contains("\"singletons\" : 2");
        assertThat(json).contains("\"kMin\" : 1");
    }

    @Test
    @DisplayName("JSON renderer flags policyViolated=false when strict but k_min >= threshold")
    void jsonPolicyOkWhenStrictAndAboveThreshold() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(7L, 9L));
        TableAuditResult tr = new TableAuditResult(
                "users", List.of("birth_date"), d, List.of(), 1.0,
                KAnonymityCalculator.recommendationsFor("users", List.of("birth_date"), d));
        AnonymityReport r = new AnonymityReport(
                "test_brume", Instant.now(), List.of(tr), true, 5, 7L);
        String json = AnonymityJsonRenderer.render(r, objectMapper);
        assertThat(json).contains("\"policyViolated\" : false");
    }

    // -------------------------------------------------------------------------
    // Markdown DPO
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("markdown renderer produces all 5 DPO sections + RGPD citations")
    void markdownAllSections() {
        AnonymityReport r = sampleReport(true, 5);
        String md = AnonymityMarkdownRenderer.render(r);
        assertThat(md).contains("# Audit anonymité — k-anonymity");
        assertThat(md).contains("## Synthèse par table");
        assertThat(md).contains("## Distribution");
        assertThat(md).contains("## Classes singletons");
        assertThat(md).contains("## Recommandations");
        assertThat(md).contains("## Méthodologie & limites");
        assertThat(md).contains("Considérant 26 RGPD");
        assertThat(md).contains("Article 4.5");
        assertThat(md).contains("Sweeney");
        assertThat(md).contains("CNIL");
    }

    @Test
    @DisplayName("markdown renderer flags POLICY VIOLÉE when strict + policyViolated")
    void markdownFlagsViolation() {
        AnonymityReport r = sampleReport(true, 5);
        String md = AnonymityMarkdownRenderer.render(r);
        assertThat(md).contains("POLICY VIOLÉE");
        assertThat(md).contains(":x:");
    }
}
