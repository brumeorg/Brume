package com.fungle.brume.scenario;

import com.fungle.brume.BrumeApplication;
import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.ConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * J7 — Boot proceeds without a {@code replication.target} block when the sink doesn't
 * need a target (#6b A18b, ADR-0028).
 *
 * <p>User journey covered: a user wants to produce a {@code .sql.gz} dump or just preview
 * the plan, without provisioning a target Postgres. Today (pre-#6b) the boot fails because
 * (a) {@code DataSourceConfig.targetDataSource} is unconditional so HikariCP tests the
 * connection at startup and (b) {@code ReplicationPropertiesValidator} requires a
 * non-blank {@code replication.target.url}. After #6b, the target beans are gated on
 * {@code brume.sink.type=JDBC} and the validator only checks {@code target.url} in JDBC mode.
 *
 * <p><strong>Requires Docker source only</strong> — {@code docker-compose up -d}.
 * The target container is intentionally not referenced in the test props; that's the point.
 */
class BootWithoutTargetIT {

    private static final String SOURCE_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String SOURCE_USER = "postgres";
    private static final String SOURCE_PASS = "postgres";
    private static final String SCHEMA = "test_brume";

    private Path tempDump;

    @AfterEach
    void cleanup() throws IOException {
        // Avoid leaking the system property set by bootstrapCli into sibling tests.
        System.clearProperty("brume.sink.type");
        if (tempDump != null) {
            Files.deleteIfExists(tempDump);
            tempDump = null;
        }
    }

    @Test
    @DisplayName("(a) execute sink.type=DUMP boots and produces a dump file without any replication.target block")
    void executeDumpModeBootsWithoutTarget() throws Exception {
        // SqlFileSink rejects paths outside the working directory (ADR-0020 path-traversal guard),
        // so the tempfile must live under the project, not under %TEMP%.
        Path dumpsDir = Path.of("target", "brume-it-dumps");
        Files.createDirectories(dumpsDir);
        tempDump = Files.createTempFile(dumpsDir, "brume-no-target-", ".sql.gz");
        Files.deleteIfExists(tempDump); // SqlFileSink opens lazily and writes from scratch

        Map<String, Object> props = baseProps();
        props.put("brume.sink.type", "DUMP");
        props.put("brume.sink.output-path", tempDump.toString());
        props.put("brume.sink.compression", "GZIP");
        // Intentionally no replication.target.* keys.

        try (ConfigurableApplicationContext ctx = bootstrap(props)) {
            ctx.getBean(ReplicationAgent.class).run(CommandEnum.EXECUTE);
        }

        assertThat(tempDump)
                .as("execute in DUMP mode must produce the configured dump file even without a target DB")
                .exists();
        assertThat(Files.size(tempDump))
                .as("dump must contain at least the DDL header — empty file would mean a silent skip")
                .isPositive();
        // Quick sanity: gzipped stream must decode without error.
        try (var in = new GZIPInputStream(Files.newInputStream(tempDump))) {
            int firstByte = in.read();
            assertThat(firstByte).as("gzip stream must contain decodable bytes").isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("(b) plan boots and renders preview without any replication.target block")
    void planBootsWithoutTarget() {
        Map<String, Object> props = baseProps();
        // plan triggers applyReadOnlySinkOverride → sink.type=NULL. No need to set sink.type.

        assertThatCode(() -> {
            try (ConfigurableApplicationContext ctx = bootstrapCli(props, "plan")) {
                ctx.getBean(ReplicationAgent.class).run(CommandEnum.PLAN);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(c) dry-run boots and runs the pipeline without any replication.target block")
    void dryRunBootsWithoutTarget() {
        Map<String, Object> props = baseProps();
        // dry-run triggers applyReadOnlySinkOverride → sink.type=NULL.

        assertThatCode(() -> {
            try (ConfigurableApplicationContext ctx = bootstrapCli(props, "dry-run")) {
                ctx.getBean(ReplicationAgent.class).run(CommandEnum.DRY_RUN);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(d) execute sink.type=JDBC with a blank target.url fails fast at boot with an actionable message")
    void executeJdbcWithBlankTargetFailsFastAtBoot() {
        Map<String, Object> props = baseProps();
        props.put("brume.sink.type", "JDBC");
        // Override the application.yaml default to a blank URL — what an unfilled config
        // file looks like in practice. The validator should reject before any DB connection
        // is opened (@DependsOn on DataSource beans guarantees the order, ADR-0028).
        props.put("replication.target.url", "");
        props.put("replication.target.username", "");
        props.put("replication.target.password", "");

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = bootstrap(props)) {
                // not reached
            }
        })
                .as("The actionable message must surface even if other DB connections would also fail "
                        + "(source unreachable, target unreachable) — the validator runs first")
                .hasStackTraceContaining("replication.target.url")
                .hasStackTraceContaining("must not be blank")
                .hasRootCauseInstanceOf(ConfigurationException.class);
    }

    private static Map<String, Object> baseProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("brume.config-path", "src/test/resources/test-config-integration.yaml");
        props.put("replication.source.url", SOURCE_URL);
        props.put("replication.source.username", SOURCE_USER);
        props.put("replication.source.password", SOURCE_PASS);
        props.put("replication.schema", SCHEMA);
        props.put("replication.pgdump-path", "docker exec -e PGPASSWORD=postgres brume-source pg_dump");
        props.put("replication.pool-size", "3");
        return props;
    }

    private static ConfigurableApplicationContext bootstrap(Map<String, Object> props) {
        String[] args = props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(BrumeApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run(args);
    }

    /**
     * Boots with a CLI subcommand argument prepended so that
     * {@code BrumeApplication.applyReadOnlySinkOverride} fires.
     */
    private static ConfigurableApplicationContext bootstrapCli(Map<String, Object> props, String subcommand) {
        String[] propArgs = props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        String[] args = new String[propArgs.length + 1];
        args[0] = subcommand;
        System.arraycopy(propArgs, 0, args, 1, propArgs.length);
        // Manually trigger what BrumeApplication.main does pre-boot. The system property
        // is cleared in @AfterEach to avoid leaking into sibling tests — clearing it here
        // in a finally block would happen *before* the caller uses the context, but Spring
        // has already bound and frozen the value by then so leakage to other tests is the
        // actual concern.
        if ("plan".equals(subcommand) || "dry-run".equals(subcommand)) {
            System.setProperty("brume.sink.type", "NULL");
        }
        return new SpringApplicationBuilder(BrumeApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run(args);
    }
}
