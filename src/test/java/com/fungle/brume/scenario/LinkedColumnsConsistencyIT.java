package com.fungle.brume.scenario;

import com.fungle.brume.BrumeApplication;
import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J7 — {@code linked_columns} cross-table consistency (#6e C1, audit 2026-05-05).
 *
 * <p>The audit flagged that the {@code linked_columns} guarantee — same source email
 * produces the same FAKE EMAIL wherever it appears, across {@code users.email} and
 * {@code audit_logs.user_email} — was only verified by a manual procedure in
 * {@code TEST_PROTOCOL.md §5.4}. No automated test covered it: {@code SemanticKeyResolver}
 * is mocked in unit tests, and every existing IT excludes the {@code audit_logs}
 * table from its snapshots.
 *
 * <p>This IT closes the gap by running the pipeline on a dedicated config
 * ({@code test-config-linked-columns.yaml}) that activates {@code linked_columns
 * user_email} across {@code users.email} and {@code audit_logs.user_email}, then
 * verifies a set-theoretic invariant that is robust to FAKE entropy (does not
 * depend on knowing which fake value alice gets — only that whatever fake alice
 * gets in users.email is the same fake she gets in audit_logs.user_email).
 *
 * <h2>Cardinality invariant</h2>
 *
 * <p>Source data (cf. {@code scripts/02_data.sql}):
 * <ul>
 *   <li>{@code users.email}: 5 distinct values (alice / bob / claire / david / emma)</li>
 *   <li>{@code audit_logs.user_email}: 4 distinct values (alice / bob / claire / unknown@external.com)</li>
 *   <li>Intersection: 3 (alice / bob / claire)</li>
 *   <li>Union: 6</li>
 * </ul>
 *
 * <p>If {@code linked_columns} works → target preserves the cardinalities exactly
 * (6 in union, 3 in intersection) because the same source email maps to the same
 * fake whether it appears in users or audit_logs.
 *
 * <p>If {@code linked_columns} breaks (each column anonymized in isolation) →
 * {@code users.email} fakes share no overlap with {@code audit_logs.user_email}
 * fakes, intersection collapses to 0, union jumps to 9. The assertion fails loudly.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 */
class LinkedColumnsConsistencyIT {

    private static final Map<String, Object> PROPS = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("brume.config-path", "src/test/resources/test-config-linked-columns.yaml"),
            Map.entry("brume.sink.type", "JDBC"),
            Map.entry("replication.source.url", "jdbc:postgresql://localhost:5432/postgres"),
            Map.entry("replication.source.username", "postgres"),
            Map.entry("replication.source.password", "postgres"),
            Map.entry("replication.target.url", "jdbc:postgresql://localhost:5460/postgres"),
            Map.entry("replication.target.username", "postgres"),
            Map.entry("replication.target.password", "postgres"),
            Map.entry("replication.schema", "test_brume"),
            Map.entry("replication.pgdump-path", "docker exec -e PGPASSWORD=postgres brume-source pg_dump"),
            Map.entry("replication.pool-size", "3")
    ));

    @Test
    @DisplayName("linked_columns user_email preserves email cardinality across users + audit_logs in target")
    void linkedColumnsPreserveCardinalityAcrossUsersAndAuditLogs() throws Exception {
        try (ConfigurableApplicationContext ctx = bootstrap()) {
            JdbcTemplate source = new JdbcTemplate((DataSource) ctx.getBean("sourceDataSource"));
            JdbcTemplate target = new JdbcTemplate((DataSource) ctx.getBean("targetDataSource"));

            // Sanity baseline on source — if this drifts, scripts/02_data.sql was rewritten and the
            // assertions below need recalibrating.
            EmailCardinalities src = cardinalities(source);
            assertThat(src.unionCount)
                    .as("source baseline: union(users.email, audit_logs.user_email) must be 6 "
                            + "(5 unique users + 1 'unknown@external.com' only in audit_logs)")
                    .isEqualTo(6L);
            assertThat(src.intersectionCount)
                    .as("source baseline: intersection must be 3 (alice/bob/claire shared)")
                    .isEqualTo(3L);

            target.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
            ctx.getBean(ReplicationAgent.class).run(CommandEnum.EXECUTE);

            EmailCardinalities tgt = cardinalities(target);

            assertThat(tgt.usersCount)
                    .as("target users row count must equal source — anonymization preserves cardinality per row")
                    .isEqualTo(src.usersCount);
            assertThat(tgt.auditCount)
                    .as("target audit_logs row count must equal source")
                    .isEqualTo(src.auditCount);

            assertThat(tgt.unionCount)
                    .as("linked_columns guarantee: target union(users.email, audit_logs.user_email) "
                            + "must equal source union (= 6). If 9, each column was anonymized in isolation "
                            + "— linked_columns user_email is broken or misconfigured.")
                    .isEqualTo(src.unionCount);
            assertThat(tgt.intersectionCount)
                    .as("linked_columns guarantee: target intersection must equal source intersection (= 3). "
                            + "If 0, the same source email (e.g. alice) produced different fakes in users vs "
                            + "audit_logs.")
                    .isEqualTo(src.intersectionCount);
        }
    }

    private static ConfigurableApplicationContext bootstrap() {
        String[] args = PROPS.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(BrumeApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run(args);
    }

    private static EmailCardinalities cardinalities(JdbcTemplate db) {
        Long users = db.queryForObject(
                "SELECT COUNT(*) FROM test_brume.users", Long.class);
        Long audit = db.queryForObject(
                "SELECT COUNT(*) FROM test_brume.audit_logs", Long.class);
        Long unionCount = db.queryForObject(
                "SELECT COUNT(*) FROM ("
                        + "  SELECT email FROM test_brume.users"
                        + "  UNION"
                        + "  SELECT user_email FROM test_brume.audit_logs"
                        + ") AS u", Long.class);
        Long intersectionCount = db.queryForObject(
                "SELECT COUNT(*) FROM ("
                        + "  SELECT email FROM test_brume.users"
                        + "  INTERSECT"
                        + "  SELECT user_email FROM test_brume.audit_logs"
                        + ") AS i", Long.class);
        return new EmailCardinalities(
                nonNull(users), nonNull(audit), nonNull(unionCount), nonNull(intersectionCount));
    }

    private static long nonNull(Long value) {
        return value == null ? 0L : value;
    }

    private record EmailCardinalities(long usersCount, long auditCount,
                                       long unionCount, long intersectionCount) {
    }
}
