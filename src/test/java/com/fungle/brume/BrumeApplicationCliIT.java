package com.fungle.brume;

import com.fungle.brume.writer.NullSink;
import com.fungle.brume.writer.Sink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the layer-(a) pre-Spring sink override (#6c, ADR-0015): when the user
 * invokes {@code plan} or {@code dry-run}, {@code applyReadOnlySinkOverride} forces
 * {@code brume.sink.type=NULL} via {@link System#setProperty} <em>before</em>
 * {@link org.springframework.boot.SpringApplication#run} starts, so the wired
 * {@link Sink} resolves to {@link NullSink} and the boot does not require a
 * {@code replication.target.url}.
 *
 * <p>Audit gap C2 (2026-05-05) flagged that
 * {@code com.fungle.brume.scenario.PlanModePostExecuteIT} (J1) calls
 * {@code agent.run(CommandEnum.PLAN)} directly and so bypasses
 * {@link BrumeApplication#main} where the override lives — meaning a future deletion
 * of the override would leave J1 green while real CLI users hit #6c (plan with sink
 * DUMP would truncate a previously-produced dump). This IT closes that gap by
 * exercising the full pre-Spring boot via the {@link BrumeApplication#bootContext}
 * package-private seam.
 *
 * <p>Note on Spring property precedence: command-line {@code --brume.sink.type=X}
 * args have <em>higher</em> precedence than {@link System#setProperty}. The tests
 * therefore never pass {@code --brume.sink.type} for read-only commands — that
 * would defeat the override's mechanism and is not the realistic production scenario
 * (real users declare sink type via {@code .env} / OS env / {@code application.yaml},
 * all of which sit below System properties).
 *
 * <p><strong>Requires Docker source</strong> — {@code docker-compose up -d}. The
 * target container is not needed thanks to #6b (sink {@code NULL} skips target
 * DataSource creation).
 */
class BrumeApplicationCliIT {

    private static final String[] BASE_DB_ARGS = {
            "--brume.config-path=src/test/resources/test-config-integration.yaml",
            "--replication.source.url=jdbc:postgresql://localhost:5432/postgres",
            "--replication.source.username=postgres",
            "--replication.source.password=postgres",
            "--replication.schema=test_brume",
            "--replication.pool-size=3"
    };

    private ConfigurableApplicationContext ctx;

    @BeforeEach
    void clearStateBeforeTest() {
        System.clearProperty("brume.sink.type");
        System.clearProperty("brume.strict-config");
    }

    @AfterEach
    void teardown() {
        if (ctx != null) {
            ctx.close();
            ctx = null;
        }
        System.clearProperty("brume.sink.type");
        System.clearProperty("brume.strict-config");
    }

    @Test
    @DisplayName("(a) 'plan' arg fires applyReadOnlySinkOverride → System property = NULL → NullSink wired")
    void planArgFiresOverrideAndWiresNullSink() {
        String[] args = compose("plan");

        ctx = BrumeApplication.bootContext(args);

        assertThat(System.getProperty("brume.sink.type"))
                .as("applyReadOnlySinkOverride must set the system property to NULL when args contains 'plan' "
                        + "— if null, the layer-(a) override was deleted or its call site removed from main() (#6c regression)")
                .isEqualTo("NULL");
        assertThat(ctx.getBean(Sink.class))
                .as("Spring must wire NullSink when brume.sink.type=NULL is set via System property "
                        + "— if SqlFileSink/JdbcSink, the override fired but Spring did not pick up the System property")
                .isInstanceOf(NullSink.class);
    }

    @Test
    @DisplayName("(b) 'dry-run' arg fires applyReadOnlySinkOverride → System property = NULL → NullSink wired")
    void dryRunArgFiresOverrideAndWiresNullSink() {
        String[] args = compose("dry-run");

        ctx = BrumeApplication.bootContext(args);

        assertThat(System.getProperty("brume.sink.type")).isEqualTo("NULL");
        assertThat(ctx.getBean(Sink.class)).isInstanceOf(NullSink.class);
    }

    @Test
    @DisplayName("(c) 'execute' arg does NOT fire applyReadOnlySinkOverride — System property untouched")
    void executeArgDoesNotFireOverride() {
        // Declare sink=NULL via --args so this negative control doesn't need a target DB.
        // The assertion is exclusively about whether the override fires; sink wiring is
        // covered by tests (a) and (b).
        String[] args = compose("execute", "--brume.sink.type=NULL");

        ctx = BrumeApplication.bootContext(args);

        assertThat(System.getProperty("brume.sink.type"))
                .as("applyReadOnlySinkOverride must only fire for read-only subcommands (plan, dry-run). "
                        + "Non-null here means the gate widened to commands that write to the target.")
                .isNull();
    }

    private static String[] compose(String subcommand, String... extras) {
        String[] all = new String[1 + extras.length + BASE_DB_ARGS.length];
        all[0] = subcommand;
        System.arraycopy(extras, 0, all, 1, extras.length);
        System.arraycopy(BASE_DB_ARGS, 0, all, 1 + extras.length, BASE_DB_ARGS.length);
        return all;
    }
}
