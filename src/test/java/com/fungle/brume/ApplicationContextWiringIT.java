package com.fungle.brume;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.audit.QuasiIdDetector;
import com.fungle.brume.audit.anonymity.AnonymityAuditor;
import com.fungle.brume.audit.anonymity.AuditRunner;
import com.fungle.brume.checkpoint.CheckpointService;
import com.fungle.brume.command.BrumeCommand;
import com.fungle.brume.command.BrumeExecutionExceptionHandler;
import com.fungle.brume.config.ConfigLoader;
import com.fungle.brume.config.BrumePropertiesValidator;
import com.fungle.brume.config.ReplicationPropertiesValidator;
import com.fungle.brume.config.SchemaConfigValidator;
import com.fungle.brume.preflight.PreflightCheckRunner;
import com.fungle.brume.shutdown.CancellationRegistry;
import com.fungle.brume.timeout.BoundedQueryExecutor;
import com.fungle.brume.timeout.TimedReplicationRunner;
import com.fungle.brume.timeout.TimeoutWarner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full Spring application context without touching any database
 * — regression guard for the class of wiring bugs exemplified by #23i
 * (BoundedQueryExecutor missing {@code @Autowired} on its public constructor,
 * making it uninstantiable via the packaged jar even though all unit tests passed).
 *
 * <p>This test relies on the Hikari lazy initialization posed by ADR-0031 (#19):
 * {@code initializationFailTimeout=-1} lets {@code sourceDataSource} and
 * {@code targetDataSource} be created without an actual JDBC connection, so the
 * entire bean graph resolves in &lt;5s even when the JDBC URLs point to nothing.
 *
 * <p>If any bean fails to wire (missing annotation, missing dependency, cycle,
 * ambiguous qualifier), Spring fails fast at context startup and this test
 * fails before the first assertion is even evaluated.
 *
 * <p>CLAUDE.md project rule 4 — this IT must stay green for any ticket that
 * touches Spring wiring, Picocli, {@code @ConfigurationProperties}, or
 * packaging. See {@code BoundedQueryExecutorTest#publicConstructorIsAutowired}
 * for the matching unit-level regression on the original #23i bug.
 */
@SpringBootTest(args = {})
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config.yaml",
        "brume.hmac-secret=test-secret-16bytes",
        "brume.fpe-key=test-fpe-key-16b",
        "replication.source.url=jdbc:postgresql://127.0.0.1:9999/none",
        "replication.source.username=test",
        "replication.source.password=test",
        "replication.target.url=jdbc:postgresql://127.0.0.1:9999/none",
        "replication.target.username=test",
        "replication.target.password=test",
        "replication.schema=test_schema"
})
class ApplicationContextWiringIT {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("all critical Spring beans resolve at boot — regression guard for #23i and similar wiring bugs")
    void allCriticalBeansResolve() {
        // Pipeline orchestration
        assertThat(ctx.getBean(ReplicationAgent.class)).isNotNull();
        assertThat(ctx.getBean(TimedReplicationRunner.class)).isNotNull();

        // Timeout subsystem (#23 / A21, ADR-0033) — caught #23i originally
        assertThat(ctx.getBean(BoundedQueryExecutor.class)).isNotNull();
        assertThat(ctx.getBean(TimeoutWarner.class)).isNotNull();

        // Preflight / lifecycle (#19, #24)
        assertThat(ctx.getBean(PreflightCheckRunner.class)).isNotNull();
        assertThat(ctx.getBean(CancellationRegistry.class)).isNotNull();

        // Config validation chain (#15c, #21)
        assertThat(ctx.getBean(BrumePropertiesValidator.class)).isNotNull();
        assertThat(ctx.getBean(ReplicationPropertiesValidator.class)).isNotNull();
        assertThat(ctx.getBean(SchemaConfigValidator.class)).isNotNull();
        assertThat(ctx.getBean(ConfigLoader.class)).isNotNull();

        // Quasi-identifier audit (#21c, ADR-0035)
        assertThat(ctx.getBean(QuasiIdDetector.class)).isNotNull();

        // K-anonymity audit subcommand (#73, ADR-0036)
        assertThat(ctx.getBean(AnonymityAuditor.class)).isNotNull();
        assertThat(ctx.getBean(AuditRunner.class)).isNotNull();

        // Crash-resume (#25, ADR-0037)
        assertThat(ctx.getBean(CheckpointService.class)).isNotNull();

        // CLI surface
        assertThat(ctx.getBean(BrumeCommand.class)).isNotNull();

        // The @SpringBootApplication itself
        assertThat(ctx.getBean(BrumeApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("BrumeExecutionExceptionHandler is instantiable — required by main() wiring (#18b)")
    void executionExceptionHandlerIsInstantiable() {
        // BrumeExecutionExceptionHandler is not a Spring bean — it's instantiated
        // directly in BrumeApplication.main(). The test asserts the class loads
        // cleanly under the same classloader the rest of the context uses,
        // ruling out classpath/shading bugs that would only show in the packaged jar.
        assertThat(new BrumeExecutionExceptionHandler()).isNotNull();
    }
}
