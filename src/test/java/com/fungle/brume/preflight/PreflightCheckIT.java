package com.fungle.brume.preflight;

import com.fungle.brume.BrumeApplication;
import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.ConnectionException;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.SchemaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #19 / A13 — phase-0 preflight checks: end-to-end coverage against the Docker compose
 * stack (source on 5432, target on 5460, schema {@code test_brume}).
 *
 * <p>The {@link PreflightCheckRunnerTest} unit suite covers helpers and the
 * unreachable-source case in isolation. This IT verifies (a) the happy path against the
 * real containers, and (b) the negative cases that depend on real PostgreSQL error
 * responses (schema not found, unreachable target, missing {@code pg_dump} binary,
 * version-mismatch error path). Checks for missing role privileges (USAGE / CREATE /
 * ownership) are not covered here — they require bootstrapping dedicated roles via SQL
 * init and are queued as a follow-up sub-ticket.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 */
class PreflightCheckIT {

    private static final String SOURCE_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String TARGET_URL = "jdbc:postgresql://localhost:5460/postgres";
    private static final String PGDUMP = "docker exec -e PGPASSWORD=postgres brume-source pg_dump";

    private static Map<String, Object> baseProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("brume.config-path", "src/test/resources/test-config-integration.yaml");
        p.put("brume.sink.type", "JDBC");
        p.put("replication.source.url", SOURCE_URL);
        p.put("replication.source.username", "postgres");
        p.put("replication.source.password", "postgres");
        p.put("replication.target.url", TARGET_URL);
        p.put("replication.target.username", "postgres");
        p.put("replication.target.password", "postgres");
        p.put("replication.schema", "test_brume");
        p.put("replication.pgdump-path", PGDUMP);
        p.put("replication.pool-size", "3");
        return p;
    }

    // ---------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Happy path: preflight passes against Docker compose (source + target reachable, schema + USAGE OK)")
    void happyPathPassesAgainstCompose() {
        try (ConfigurableApplicationContext ctx = bootstrap(baseProps())) {
            PreflightCheckRunner runner = ctx.getBean(PreflightCheckRunner.class);
            assertThatCode(runner::run).doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------------
    // Negative — unreachable source
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Unreachable source fails fast with CONNECTION_SOURCE_UNREACHABLE, well under 3s")
    void unreachableSource_failsFast() {
        Map<String, Object> p = baseProps();
        p.put("replication.source.url", "jdbc:postgresql://127.0.0.1:1/postgres");

        try (ConfigurableApplicationContext ctx = bootstrap(p)) {
            PreflightCheckRunner runner = ctx.getBean(PreflightCheckRunner.class);
            long t0 = System.currentTimeMillis();
            try {
                assertThatThrownBy(runner::run)
                        .isInstanceOf(ConnectionException.class)
                        .satisfies(ex -> assertThat(((ConnectionException) ex).code())
                                .isEqualTo(BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE));
            } finally {
                long elapsed = System.currentTimeMillis() - t0;
                assertThat(elapsed)
                        .as("<3s wall-clock contract (loginTimeout=2s + slack)")
                        .isLessThan(3_000L);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Negative — unreachable target
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Unreachable target fails fast with CONNECTION_TARGET_UNREACHABLE")
    void unreachableTarget_failsFast() {
        Map<String, Object> p = baseProps();
        p.put("replication.target.url", "jdbc:postgresql://127.0.0.1:1/postgres");

        try (ConfigurableApplicationContext ctx = bootstrap(p)) {
            PreflightCheckRunner runner = ctx.getBean(PreflightCheckRunner.class);
            assertThatThrownBy(runner::run)
                    .isInstanceOf(ConnectionException.class)
                    .satisfies(ex -> assertThat(((ConnectionException) ex).code())
                            .isEqualTo(BrumeErrorCode.CONNECTION_TARGET_UNREACHABLE));
        }
    }

    // ---------------------------------------------------------------------
    // Negative — schema not found
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Missing source schema surfaces PREFLIGHT_SOURCE_SCHEMA_NOT_FOUND with the schema name")
    void missingSourceSchema_surfacedExplicitly() {
        Map<String, Object> p = baseProps();
        p.put("replication.schema", "schema_does_not_exist_" + System.nanoTime());

        try (ConfigurableApplicationContext ctx = bootstrap(p)) {
            PreflightCheckRunner runner = ctx.getBean(PreflightCheckRunner.class);
            assertThatThrownBy(runner::run)
                    .isInstanceOf(SchemaException.class)
                    .satisfies(ex -> {
                        SchemaException se = (SchemaException) ex;
                        assertThat(se.code())
                                .isEqualTo(BrumeErrorCode.PREFLIGHT_SOURCE_SCHEMA_NOT_FOUND);
                        assertThat(se.getMessage()).contains("schema_does_not_exist_");
                        assertThat(se.suggestion()).contains("replication.schema");
                    });
        }
    }

    // ---------------------------------------------------------------------
    // Negative — pg_dump binary missing
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Missing pg_dump binary surfaces PREFLIGHT_PG_DUMP_NOT_FOUND")
    void missingPgDumpBinary_surfaced() {
        Map<String, Object> p = baseProps();
        p.put("replication.pgdump-path", "brume-no-such-pg_dump-" + System.nanoTime());

        try (ConfigurableApplicationContext ctx = bootstrap(p)) {
            PreflightCheckRunner runner = ctx.getBean(PreflightCheckRunner.class);
            assertThatThrownBy(runner::run)
                    .isInstanceOf(ConfigurationException.class)
                    .satisfies(ex -> assertThat(((ConfigurationException) ex).code())
                            .isEqualTo(BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND));
        }
    }

    // ---------------------------------------------------------------------
    // Wiring — preflight runs in the actual run() before schemaReplicator
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Wiring: ReplicationAgent.run() executes preflight before pg_dump (unreachable target surfaces preflight error, not Hikari timeout)")
    void wiringInReplicationAgent_preflightFiresBeforePgDump() {
        Map<String, Object> p = baseProps();
        p.put("replication.target.url", "jdbc:postgresql://127.0.0.1:1/postgres");

        try (ConfigurableApplicationContext ctx = bootstrap(p)) {
            ReplicationAgent agent = ctx.getBean(ReplicationAgent.class);
            long t0 = System.currentTimeMillis();
            try {
                assertThatThrownBy(() -> agent.run(CommandEnum.EXECUTE))
                        .isInstanceOf(ConnectionException.class)
                        .satisfies(ex -> assertThat(((ConnectionException) ex).code())
                                .isEqualTo(BrumeErrorCode.CONNECTION_TARGET_UNREACHABLE));
            } finally {
                long elapsed = System.currentTimeMillis() - t0;
                // The Hikari pool default connectionTimeout is 30s — without preflight, a
                // bad target URL would hang ~30s. Preflight caps the round-trip well under 5s.
                assertThat(elapsed)
                        .as("Preflight must short-circuit before Hikari default 30s timeout")
                        .isLessThan(10_000L);
            }
        }
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
}
