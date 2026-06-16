package com.fungle.brume.audit.anonymity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link KAnonymityCalculator} — the SQL string builder and the
 * recommendation logic. The actual JDBC execution is exercised by the IT.
 */
class KAnonymityCalculatorTest {

    @Test
    @DisplayName("buildGroupBySql produces quoted identifiers and no TABLESAMPLE at sampleRate=1.0")
    void buildSqlFullScan() {
        KAnonymityCalculator c = new KAnonymityCalculator(mock(JdbcTemplate.class), "test_brume");
        String sql = c.buildGroupBySql("users", List.of("birth_date", "postal_code"), 1.0);
        assertThat(sql).isEqualTo(
                "SELECT \"birth_date\", \"postal_code\", COUNT(*) AS k "
                        + "FROM \"test_brume\".\"users\" "
                        + "GROUP BY \"birth_date\", \"postal_code\"");
    }

    @Test
    @DisplayName("buildGroupBySql appends TABLESAMPLE SYSTEM when sampleRate < 1.0")
    void buildSqlSampled() {
        KAnonymityCalculator c = new KAnonymityCalculator(mock(JdbcTemplate.class), "test_brume");
        String sql = c.buildGroupBySql("users", List.of("birth_date"), 0.1);
        assertThat(sql).contains("FROM \"test_brume\".\"users\" TABLESAMPLE SYSTEM (10.0000) GROUP BY");
    }

    @Test
    @DisplayName("recommendationsFor flags CRITICAL when singletons exist")
    void recommendationsCritical() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(1L, 1L, 7L));
        List<Recommendation> recs = KAnonymityCalculator.recommendationsFor(
                "users", List.of("birth_date"), d);
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).severity()).isEqualTo(Recommendation.Severity.CRITICAL);
        assertThat(recs.get(0).message()).contains("2 row(s)").contains("users").contains("birth_date");
    }

    @Test
    @DisplayName("recommendationsFor flags WARNING when k_min between 2 and 4 (no singletons)")
    void recommendationsWarning() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(2L, 8L, 100L));
        List<Recommendation> recs = KAnonymityCalculator.recommendationsFor(
                "users", List.of("birth_date"), d);
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).severity()).isEqualTo(Recommendation.Severity.WARNING);
        assertThat(recs.get(0).message()).contains("Sweeney").contains("CNIL");
    }

    @Test
    @DisplayName("recommendationsFor reports INFO when k_min >= 5")
    void recommendationsInfo() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(5L, 9L, 100L));
        List<Recommendation> recs = KAnonymityCalculator.recommendationsFor(
                "users", List.of("birth_date"), d);
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).severity()).isEqualTo(Recommendation.Severity.INFO);
    }

    @Test
    @DisplayName("recommendationsFor reports INFO for empty tables")
    void recommendationsEmpty() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.empty();
        List<Recommendation> recs = KAnonymityCalculator.recommendationsFor(
                "users", List.of("birth_date"), d);
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).severity()).isEqualTo(Recommendation.Severity.INFO);
        assertThat(recs.get(0).message()).contains("empty");
    }
}
