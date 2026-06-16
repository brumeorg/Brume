package com.fungle.brume.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for #79c — composite strategy-counter key on {@link ExecutionReport}.
 *
 * <p>Pre-fix : {@code strategyCounts} was keyed by {@code "table|column|strategy"} concat
 * with a {@code split("\\|", 3)} in {@code toSummary}. Identifiers containing {@code "|"}
 * (PostgreSQL quoted identifier — legal under {@code "col|name"}) silently collided or
 * produced wrong parts after the split.
 *
 * <p>Post-fix : a private {@code record StrategyKey(String, String, String)} replaces the
 * concat. Records have value-based equals/hashCode → distinct identifiers always hash to
 * distinct buckets and the split is gone.
 */
class ExecutionReportStrategyKeyTest {

    @Test
    @DisplayName("#79c — pipe character inside an identifier no longer breaks counting")
    void pipeInIdentifierDoesNotCollide() {
        ExecutionReport report = new ExecutionReport("schema", "schema");

        // Two distinct (table, column, strategy) tuples that produced the SAME concat
        // string "users|col|name|HASH" pre-fix, splitting into wrong parts.
        // Both calls increment the counter — pre-fix, they would be merged onto a single
        // bucket due to the ambiguous separator.
        report.recordStrategy("users", "col|name", "HASH");
        report.recordStrategy("users|col", "name", "HASH");

        List<StrategyUsage> usages = report.toSummary(new PhaseTimings(0, 0, 0, 0))
                .strategyUsages();

        // Two distinct usages must appear (pre-fix : 1 entry with count 2, with the
        // table/column parts wrongly split).
        assertThat(usages)
                .as("the typed StrategyKey record must distinguish the two tuples")
                .hasSize(2);
        assertThat(usages)
                .anyMatch(u -> u.table().equals("users")
                        && u.column().equals("col|name")
                        && u.strategy().equals("HASH")
                        && u.count() == 1L);
        assertThat(usages)
                .anyMatch(u -> u.table().equals("users|col")
                        && u.column().equals("name")
                        && u.strategy().equals("HASH")
                        && u.count() == 1L);
    }

    @Test
    @DisplayName("#79c — repeated recordStrategy on same key still increments correctly")
    void repeatedRecordsIncrement() {
        ExecutionReport report = new ExecutionReport("schema", "schema");
        report.recordStrategy("users", "email", "FAKE");
        report.recordStrategy("users", "email", "FAKE");
        report.recordStrategy("users", "email", "FAKE");

        List<StrategyUsage> usages = report.toSummary(new PhaseTimings(0, 0, 0, 0))
                .strategyUsages();
        assertThat(usages).hasSize(1);
        assertThat(usages.get(0).count()).isEqualTo(3L);
    }
}
