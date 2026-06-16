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
 * J6 — FK auto-propagation produces a referentially consistent target.
 *
 * <p>User journey: a user declares anonymization strategies on PKs only, leaving FK columns
 * undeclared. {@code FkStrategyPropagator} must inherit the PK strategy onto each FK so that
 * joins in the target still resolve — no orphan child rows, no broken references.
 *
 * <p>Why this matters: before the propagator existed, the user had to declare every FK
 * explicitly. A missed declaration silently produced a dump where DELETE on parent rows
 * succeeded in target (no children pointed at the new anonymized PK) but failed in source
 * (real FKs still referenced the original PK). This IT prevents that regression.
 *
 * <p>Implementation: bootstrap a Spring context against {@code test-config-fk-propagation.yaml}
 * (where {@code orders.user_id}, {@code order_items.order_id} and {@code order_items.product_id}
 * are intentionally undeclared), run the full pipeline JDBC-side, then assert that joining
 * children to parents on the FK columns yields zero orphans.
 *
 * <p><strong>Requires Docker infrastructure</strong> — {@code docker-compose up -d}.
 */
class FkStrategyPropagationIT {

    private static final Map<String, Object> PROPS = Map.of(
            "brume.config-path", "src/test/resources/test-config-fk-propagation.yaml",
            "brume.sink.type", "JDBC",
            "replication.source.url", "jdbc:postgresql://localhost:5432/postgres",
            "replication.target.url", "jdbc:postgresql://localhost:5460/postgres",
            "replication.schema", "test_brume",
            "replication.pgdump-path", "docker exec -e PGPASSWORD=postgres brume-source pg_dump",
            "replication.pool-size", "3"
    );

    @Test
    @DisplayName("Undeclared FK columns inherit their parent PK strategy and stay consistent in target")
    void undeclaredFkColumnsInheritParentPkStrategy() throws Exception {
        try (ConfigurableApplicationContext ctx = bootstrap()) {
            JdbcTemplate target = jdbc(ctx);
            target.execute("DROP SCHEMA IF EXISTS test_brume CASCADE");
            ctx.getBean(ReplicationAgent.class).run(CommandEnum.EXECUTE);

            // Pipeline must have written rows; a zero-row target tells nothing about consistency.
            Long orderRowCount = target.queryForObject(
                    "SELECT COUNT(*) FROM test_brume.orders", Long.class);
            assertThat(orderRowCount).as("orders must contain rows for the assertion to be meaningful").isPositive();

            // 1. orders.user_id (undeclared) must point to an existing users.id (anonymized FPE_ID).
            Long ordersOrphans = target.queryForObject(
                    "SELECT COUNT(*) FROM test_brume.orders o "
                            + "LEFT JOIN test_brume.users u ON u.id = o.user_id "
                            + "WHERE o.user_id IS NOT NULL AND u.id IS NULL",
                    Long.class);
            assertThat(ordersOrphans)
                    .as("orders.user_id was undeclared but must inherit FPE_ID from users.id — "
                            + "any orphan means propagation failed and the target is referentially broken")
                    .isZero();

            // 2. order_items.order_id (undeclared) must point to an existing orders.id.
            Long itemsOrderOrphans = target.queryForObject(
                    "SELECT COUNT(*) FROM test_brume.order_items oi "
                            + "LEFT JOIN test_brume.orders o ON o.id = oi.order_id "
                            + "WHERE oi.order_id IS NOT NULL AND o.id IS NULL",
                    Long.class);
            assertThat(itemsOrderOrphans).as("order_items.order_id orphans (FPE_ID propagation)").isZero();

            // 3. order_items.product_id (undeclared) must point to an existing products.id.
            Long itemsProductOrphans = target.queryForObject(
                    "SELECT COUNT(*) FROM test_brume.order_items oi "
                            + "LEFT JOIN test_brume.products p ON p.id = oi.product_id "
                            + "WHERE oi.product_id IS NOT NULL AND p.id IS NULL",
                    Long.class);
            assertThat(itemsProductOrphans).as("order_items.product_id orphans (FPE_ID propagation)").isZero();
        }
    }

    private static ConfigurableApplicationContext bootstrap() {
        Map<String, Object> props = new LinkedHashMap<>(PROPS);
        String[] args = props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(BrumeApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run(args);
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext ctx) {
        DataSource ds = (DataSource) ctx.getBean("targetDataSource");
        return new JdbcTemplate(ds);
    }
}
