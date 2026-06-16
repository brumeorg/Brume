package com.fungle.brume.timeout;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.RunTimeoutException;
import com.fungle.brume.shutdown.CancellationRegistry;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.SinkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TimedReplicationRunner} (#23 / A21, ADR-0033) — covers (a)
 * pass-through when {@code total-run-seconds=0}, (b) propagation of agent exceptions,
 * (c) timeout firing surfaces {@link RunTimeoutException} with code
 * {@link BrumeErrorCode#RUN_TIMEOUT_TOTAL} and calls
 * {@link CancellationRegistry#requestCancelAll()}.
 *
 * <p>The {@code Runtime.halt(7)} branch (grace period exceeded) is deliberately not
 * tested in JVM-process scope — halting would kill the test runner. The branch is
 * exercised indirectly by the ITs in CI when a runaway pipeline is forced.
 */
@ExtendWith(MockitoExtension.class)
class TimedReplicationRunnerTest {

    @Mock
    private ReplicationAgent agent;

    @Mock
    private CancellationRegistry registry;

    @Test
    @DisplayName("total-run-seconds=0 → direct delegation, no executor, no registry interaction")
    void passthrough_when_disabled() throws Exception {
        TimedReplicationRunner runner = new TimedReplicationRunner(agent, registry, propsTotal(0));

        runner.run(CommandEnum.EXECUTE);

        verify(agent).run(CommandEnum.EXECUTE);
        verify(registry, never()).requestCancelAll();
    }

    @Test
    @DisplayName("agent exceptions propagate unchanged when total-run timeout is disabled")
    void propagates_agent_exception_when_disabled() throws Exception {
        org.mockito.Mockito.doThrow(new IOException("pipeline failed"))
                .when(agent).run(CommandEnum.EXECUTE);

        TimedReplicationRunner runner = new TimedReplicationRunner(agent, registry, propsTotal(0));

        assertThatThrownBy(() -> runner.run(CommandEnum.EXECUTE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pipeline failed");
        verify(registry, never()).requestCancelAll();
    }

    @Test
    @DisplayName("agent exceptions propagate unchanged when timeout is enabled (timeout not fired)")
    void propagates_agent_exception_when_enabled() throws Exception {
        org.mockito.Mockito.doThrow(new IOException("pipeline crashed"))
                .when(agent).run(CommandEnum.EXECUTE);

        TimedReplicationRunner runner = new TimedReplicationRunner(agent, registry, propsTotal(60));

        assertThatThrownBy(() -> runner.run(CommandEnum.EXECUTE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pipeline crashed");
        verify(registry, never()).requestCancelAll();
    }

    @Test
    @DisplayName("timeout fires → RunTimeoutException(RUN_TIMEOUT_TOTAL) + registry.requestCancelAll()")
    void timeout_surfaces_typed_exception_and_aborts_resources() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // Pipeline that ignores Thread.interrupt unless we explicitly poll — simulates a
        // blocking JDBC call that only responds to Connection.abort(). The registry mock
        // doesn't actually abort anything, so we use a long sleep + InterruptedException
        // handling to let the future complete after grace.
        org.mockito.Mockito.doAnswer(inv -> {
            long deadline = System.currentTimeMillis() + 4_000;
            while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    cancelled.set(true);
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }).when(agent).run(CommandEnum.EXECUTE);

        TimedReplicationRunner runner = new TimedReplicationRunner(agent, registry, propsTotal(1));

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> runner.run(CommandEnum.EXECUTE))
                .isInstanceOf(RunTimeoutException.class)
                .matches(e -> ((RunTimeoutException) e).code() == BrumeErrorCode.RUN_TIMEOUT_TOTAL)
                .hasMessageContaining("total-run-seconds=1");
        long elapsed = System.currentTimeMillis() - started;

        verify(registry, atLeastOnce()).requestCancelAll();
        assertThat(elapsed).as("timeout should fire near the configured 1s, not full pipeline 4s")
                .isLessThan(3_000L);
        assertThat(cancelled).as("pipeline thread should have been interrupted on cancel").isTrue();
    }

    private static BrumeProperties propsTotal(int totalRunSeconds) {
        return new BrumeProperties(
                "config.yaml", "secret", "fpekey1234567890",
                "HmacSHA256", "fr",
                0.0, 0L, 85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""),
                new BrumeProperties.SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new BrumeProperties.JdbcSinkProperties(CopyMode.NEVER)),
                new BrumeProperties.PlanProperties(com.fungle.brume.plan.PlanMode.EXACT),
                false,
                new BrumeProperties.OutputProperties(com.fungle.brume.output.OutputMode.TEXT),
                new BrumeProperties.TimeoutsProperties(600, totalRunSeconds)
        );
    }
}
