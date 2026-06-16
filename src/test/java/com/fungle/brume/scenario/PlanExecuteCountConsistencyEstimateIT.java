package com.fungle.brume.scenario;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.report.ReportRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J3' — Plan in {@code ESTIMATE} mode is callable and returns a usable summary.
 *
 * <p>Counterpart of {@code PlanExecuteCountConsistencyIT} (which runs strict
 * {@code EXACT} mode), separated per audit C3 (2026-05-05): the strict assertion
 * lives in EXACT, while ESTIMATE — backed by {@code pg_class.reltuples} which on
 * the small {@code test_brume} dataset can return {@code 0} or wildly off values
 * before autoanalyze runs — gets a softer smoke check here.
 *
 * <p>What this IT proves:
 * <ul>
 *   <li>ESTIMATE plan mode completes without throwing.</li>
 *   <li>The summary contains at least one table.</li>
 *   <li>{@code plannedTotal} is non-negative for every entry — no silent {@code -1}
 *       sentinel leaking out.</li>
 * </ul>
 *
 * <p>What this IT does <em>not</em> prove (intentional):
 * <ul>
 *   <li>That ESTIMATE counts are close to actual — would be flaky on a 5-row table
 *       where {@code reltuples} is 0 until {@code ANALYZE} runs. A tighter
 *       assertion belongs in a perf-scale IT (cf. {@code test-config-perf.yaml} +
 *       {@code test_brume_perf} schema).</li>
 * </ul>
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=NULL",
        "brume.plan.mode=ESTIMATE",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
@DirtiesContext
class PlanExecuteCountConsistencyEstimateIT {

    @MockitoBean
    private SchemaReplicator schemaReplicator;

    @MockitoSpyBean
    private ReportRenderer reportRenderer;

    @Autowired
    private ReplicationAgent agent;

    @Test
    @DisplayName("plan in ESTIMATE mode completes and produces non-negative plannedTotal per table")
    void planEstimateModeReturnsUsableSummary() throws Exception {
        agent.run(CommandEnum.PLAN);

        ArgumentCaptor<PlanSummary> planCaptor = ArgumentCaptor.forClass(PlanSummary.class);
        Mockito.verify(reportRenderer, Mockito.atLeastOnce()).renderPlan(planCaptor.capture());

        PlanSummary plan = planCaptor.getValue();
        assertThat(plan.tableStats())
                .as("ESTIMATE plan must produce at least one table — if empty, "
                        + "PlanEstimator silently failed to read pg_class.reltuples")
                .isNotEmpty();

        for (PlanTableStats stats : plan.tableStats()) {
            assertThat(stats.plannedTotal())
                    .as("table %s : plannedTotal must be non-negative — negative values "
                            + "indicate a sentinel leaked out of PlanEstimator", stats.table())
                    .isGreaterThanOrEqualTo(0L);
        }
    }
}
