package com.fungle.brume.scenario;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.PlanSummary;
import com.fungle.brume.report.PlanTableStats;
import com.fungle.brume.report.ReportRenderer;
import com.fungle.brume.report.TableStats;
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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J3 — Plan and execute report consistent per-table counts.
 *
 * <p>User journey : a user runs {@code brume plan} to preview the extraction
 * (rows planned per table), then commits to {@code brume execute}. The actual
 * extraction must match the plan within a small tolerance — otherwise the
 * planning report is misleading and the user cannot trust the preview.
 *
 * <p>This catches a class of subtle bugs : {@code PlanEstimator} drift when
 * filters are not honored, FK depth miscounts, ordering non-determinism, or
 * race conditions between plan and execute (e.g. autovacuum updating
 * {@code pg_class.reltuples}).
 *
 * <p>The comparison is done table by table : for every table the plan declares,
 * the actual {@code extracted + fkParents} count must equal the planned total
 * <strong>exactly</strong> in {@code EXACT} plan mode (which is the case here).
 * A ±10% tolerance was historically applied but masked a class of subtle drift
 * bugs (audit C3, 2026-05-05) — see {@code PlanExecuteCountConsistencyEstimateIT}
 * for the {@code ESTIMATE} mode counterpart where tolerance is legitimate.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose
 * up -d}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=NULL",
        "brume.plan.mode=EXACT",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
@DirtiesContext
class PlanExecuteCountConsistencyIT {

    /** Mocked so this IT does not depend on a local pg_dump binary. */
    @MockitoBean
    private SchemaReplicator schemaReplicator;

    /** Spy lets us capture the actual {@link PlanSummary} and {@link ExecutionSummary} arguments. */
    @MockitoSpyBean
    private ReportRenderer reportRenderer;

    @Autowired
    private ReplicationAgent agent;

    @Test
    @DisplayName("execute actual rows equal plan summary exactly per table (EXACT mode, zero tolerance)")
    void executeMatchesPlanExactlyInExactMode() throws Exception {
        // Single execute run captures both : pre-flight PlanSummary AND post-execute ExecutionSummary
        agent.run(CommandEnum.EXECUTE);

        ArgumentCaptor<PlanSummary> planCaptor = ArgumentCaptor.forClass(PlanSummary.class);
        ArgumentCaptor<ExecutionSummary> execCaptor = ArgumentCaptor.forClass(ExecutionSummary.class);
        Mockito.verify(reportRenderer, Mockito.atLeastOnce()).renderPlan(planCaptor.capture());
        Mockito.verify(reportRenderer, Mockito.atLeastOnce()).render(execCaptor.capture());

        PlanSummary plan = planCaptor.getValue();
        ExecutionSummary exec = execCaptor.getValue();

        Map<String, Long> plannedByTable = plan.tableStats().stream()
                .filter(s -> s.plannedTotal() >= 0)
                .collect(Collectors.toMap(PlanTableStats::table, PlanTableStats::plannedTotal));
        Map<String, Long> actualByTable = exec.tableStats().stream()
                .collect(Collectors.toMap(TableStats::table, t -> t.extracted() + t.fkParents()));

        assertThat(plannedByTable)
                .as("plan must produce at least one table stats entry")
                .isNotEmpty();
        assertThat(actualByTable)
                .as("execute must produce at least one table stats entry")
                .isNotEmpty();

        for (Map.Entry<String, Long> e : plannedByTable.entrySet()) {
            String table = e.getKey();
            long planned = e.getValue();
            Long actual = actualByTable.get(table);

            assertThat(actual)
                    .as("table %s present in plan but missing from execution summary", table)
                    .isNotNull();

            assertThat(actual)
                    .as("table %s : EXACT plan mode must match execution exactly — planned=%d actual=%d. "
                            + "Any drift indicates PlanEstimator and the extraction path are not in sync "
                            + "(filter not honored, double-counting of FK parents, race with autovacuum on reltuples, …).",
                            table, planned, actual)
                    .isEqualTo(planned);
        }
    }

    @Test
    @DisplayName("plan invoked twice produces the same per-table totals (deterministic preview)")
    void planIsDeterministicAcrossRuns() throws Exception {
        agent.run(CommandEnum.PLAN);
        agent.run(CommandEnum.PLAN);

        ArgumentCaptor<PlanSummary> planCaptor = ArgumentCaptor.forClass(PlanSummary.class);
        Mockito.verify(reportRenderer, Mockito.atLeast(2)).renderPlan(planCaptor.capture());
        List<PlanSummary> plans = planCaptor.getAllValues();

        Map<String, Long> first = totalsByTable(plans.get(0));
        Map<String, Long> last = totalsByTable(plans.get(plans.size() - 1));
        assertThat(last)
                .as("plan totals must be byte-stable across consecutive runs against the same source")
                .containsExactlyInAnyOrderEntriesOf(first);
    }

    private static Map<String, Long> totalsByTable(PlanSummary plan) {
        return plan.tableStats().stream()
                .collect(Collectors.toMap(PlanTableStats::table,
                        PlanTableStats::plannedTotal,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
