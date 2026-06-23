package com.fungle.brume.scenario;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #81b / ADR-0042 — end-to-end validation of composite primary-key and composite
 * foreign-key support.
 *
 * <p>Source fixture: a parent {@code accounts} with a composite PK {@code (tenant_id,
 * account_id)} holding all four {@code {1,2} × {1,2}} combinations, and a child
 * {@code transactions} with a composite FK {@code (tenant_id, account_id) → accounts}.
 * Only two transactions pass the {@code amount >= 100} extraction filter, referencing the
 * exact tuples {@code (1,1)} and {@code (2,2)}.
 *
 * <p>The test proves that {@code FkParentResolver} resolves the parents by the full tuple
 * via PostgreSQL row-values — pulling exactly {@code (1,1)} and {@code (2,2)} and
 * <strong>not</strong> the cross product (e.g. {@code (1,2)} or {@code (2,1)}), which the
 * pre-#81b {@code information_schema} cartesian introspection would have over-fetched.
 *
 * <p><strong>Requires Docker</strong>: {@code brume-source} (5432) and {@code brume-target}
 * (5460), cf. {@code docker-compose.yml}.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-composite-pk.yaml",
        "brume.sink.type=JDBC",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.schema=test_brume_composite",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
        "replication.pool-size=3"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompositePrimaryKeyIT {

    private static final String SCHEMA = "test_brume_composite";

    @Autowired
    private ReplicationAgent agent;

    private JdbcTemplate sourceJdbc;
    private JdbcTemplate targetJdbc;

    @Autowired
    public void setSourceJdbc(@Qualifier("sourceDataSource") DataSource ds) {
        this.sourceJdbc = new JdbcTemplate(ds);
    }

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    @BeforeAll
    void createSourceFixture() {
        dropSourceSchema();
        sourceJdbc.execute("CREATE SCHEMA " + SCHEMA);
        sourceJdbc.execute("""
                CREATE TABLE %s.accounts (
                    tenant_id  INT NOT NULL,
                    account_id INT NOT NULL,
                    name       VARCHAR(128) NOT NULL,
                    PRIMARY KEY (tenant_id, account_id)
                )
                """.formatted(SCHEMA));
        sourceJdbc.execute("""
                CREATE TABLE %s.transactions (
                    id         BIGINT PRIMARY KEY,
                    tenant_id  INT NOT NULL,
                    account_id INT NOT NULL,
                    amount     NUMERIC(10,2) NOT NULL,
                    FOREIGN KEY (tenant_id, account_id) REFERENCES %s.accounts (tenant_id, account_id)
                )
                """.formatted(SCHEMA, SCHEMA));

        // All four (tenant, account) combinations exist as parents.
        sourceJdbc.execute("INSERT INTO " + SCHEMA + ".accounts (tenant_id, account_id, name) VALUES "
                + "(1,1,'A11'), (1,2,'A12'), (2,1,'A21'), (2,2,'A22')");
        // Two transactions above the filter reference the exact tuples (1,1) and (2,2);
        // one below the filter references (1,2) and must NOT pull account (1,2).
        sourceJdbc.execute("INSERT INTO " + SCHEMA + ".transactions (id, tenant_id, account_id, amount) VALUES "
                + "(1, 1, 1, 150.00), (2, 2, 2, 200.00), (3, 1, 2, 50.00)");
    }

    @AfterAll
    void dropSourceFixture() {
        dropSourceSchema();
    }

    @BeforeEach
    void cleanTarget() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private void dropSourceSchema() {
        sourceJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("composite FK parents are resolved by tuple — exact pairs only, no cross product")
    void compositeForeignKeyResolvesExactTuples() throws Exception {
        agent.run(CommandEnum.EXECUTE);

        // Only the two filtered transactions are extracted.
        assertThat(count("transactions"))
                .as("transactions WHERE amount >= 100 → exactly 2 rows")
                .isEqualTo(2);

        // FkParentResolver pulls exactly the two referenced composite tuples — not the
        // cross product (1,2)/(2,1) that a single-column match would have over-fetched.
        List<String> accountKeys = targetJdbc.queryForList(
                "SELECT tenant_id || ',' || account_id FROM " + SCHEMA + ".accounts ORDER BY 1",
                String.class);
        assertThat(accountKeys)
                .as("only the exact referenced (tenant_id, account_id) tuples are resolved")
                .containsExactly("1,1", "2,2");

        // Referential integrity on the target: no transaction points at a missing account.
        Long orphans = targetJdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".transactions t "
                        + "WHERE NOT EXISTS (SELECT 1 FROM " + SCHEMA + ".accounts a "
                        + "WHERE a.tenant_id = t.tenant_id AND a.account_id = t.account_id)",
                Long.class);
        assertThat(orphans)
                .as("every extracted transaction has its composite FK parent present")
                .isZero();
    }

    private long count(String table) {
        Long n = targetJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + "." + table, Long.class);
        return n == null ? 0L : n;
    }
}
