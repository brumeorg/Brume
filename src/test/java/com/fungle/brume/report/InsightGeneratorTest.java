package com.fungle.brume.report;

import com.fungle.brume.schema.model.DatabaseSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InsightGeneratorTest {

    @Test
    @DisplayName("#29a — rows lost to a UNIQUE collision raise an error insight, not « already in the target »")
    void rowsLostToUniqueCollisionsRaiseAnErrorInsight() {
        ExecutionSummary exec = new ExecutionSummary("src", "dst", true, null,
                new PhaseTimings(100, 50, 200, 400),
                List.of(new TableStats("holiday", 60, 0, 0, 51, 0, 9, 0)),
                List.of(), Instant.now());

        List<Insight> insights = new InsightGenerator(new DatabaseSchema(Map.of())).generate(
                List.of(new ComparisonRow("holiday", 60, 60, 51, 0, 0.0)), exec, List.of(), Map.of());

        assertThat(insights).filteredOn(i -> i.level() == Insight.Level.ERROR)
                .extracting(Insight::message)
                .singleElement().asString()
                .startsWith("holiday : 9 ligne(s) perdue(s)").contains("contrainte UNIQUE");
        assertThat(insights).extracting(Insight::message).noneMatch(m -> m.contains("existent déjà en cible"));
    }
}
