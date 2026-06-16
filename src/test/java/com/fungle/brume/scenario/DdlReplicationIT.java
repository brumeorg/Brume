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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #27 / A16 — verifies that {@code pg_dump --schema-only} output is correctly
 * parsed and rejoué on the target for exotic DDL objects beyond plain tables :
 * <ol>
 *   <li>{@code CREATE TYPE ... AS ENUM}</li>
 *   <li>Explicit {@code CREATE SEQUENCE} (not the implicit identity one)</li>
 *   <li>Table with custom defaults referencing the enum and the sequence</li>
 *   <li>{@code COMMENT ON TABLE} and {@code COMMENT ON COLUMN}</li>
 *   <li>Partial index ({@code CREATE INDEX ... WHERE ...})</li>
 *   <li>Simple {@code VIEW}</li>
 *   <li>{@code MATERIALIZED VIEW}</li>
 *   <li>PL/pgSQL {@code FUNCTION} with {@code $$ ... $$} dollar-quoted body</li>
 *   <li>{@code TRIGGER} attached to a function</li>
 * </ol>
 *
 * <p>The source fixture schema {@code test_brume_ddl_edge} is created once per
 * test class (lifecycle PER_CLASS) and dropped at teardown. The target schema
 * is recreated by Brume at every run via {@code SchemaReplicator}. The test
 * then introspects {@code pg_catalog} on the target to verify each object kind
 * is present.
 *
 * <p>Note : {@code CREATE EXTENSION} (e.g. {@code pgcrypto}) is intentionally
 * absent — extensions are database-global objects, {@code pg_dump --schema=X}
 * does not emit them, so they fall outside {@link
 * com.fungle.brume.replicator.SchemaReplicator}'s remit.
 *
 * <p><strong>Requires Docker</strong> : {@code brume-source} (5432) and
 * {@code brume-target} (5460), cf. {@code docker-compose.yml}.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-ddl-edge.yaml",
        "brume.sink.type=JDBC",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.schema=test_brume_ddl_edge",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
        "replication.pool-size=3"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DdlReplicationIT {

    private static final String SCHEMA = "test_brume_ddl_edge";

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

    // -------------------------------------------------------------------------
    // Source fixture lifecycle
    // -------------------------------------------------------------------------

    @BeforeAll
    void createSourceFixture() {
        dropSourceSchema();
        sourceJdbc.execute("CREATE SCHEMA " + SCHEMA);

        // 1. ENUM type
        sourceJdbc.execute("CREATE TYPE " + SCHEMA + ".order_status AS ENUM "
                + "('pending', 'shipped', 'delivered', 'cancelled')");

        // 2. Named sequence (explicit, separate from any identity column)
        sourceJdbc.execute("CREATE SEQUENCE " + SCHEMA + ".invoice_number_seq "
                + "START 1000 INCREMENT 1");

        // 3. Table that uses the enum and the sequence as a default
        sourceJdbc.execute("""
                CREATE TABLE %s.invoices (
                    id              BIGINT PRIMARY KEY
                                    DEFAULT nextval('%s.invoice_number_seq'),
                    customer_email  VARCHAR(255) NOT NULL,
                    status          %s.order_status NOT NULL DEFAULT 'pending',
                    amount          NUMERIC(10,2) NOT NULL,
                    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """.formatted(SCHEMA, SCHEMA, SCHEMA));

        // 4. Comments
        sourceJdbc.execute("COMMENT ON TABLE " + SCHEMA + ".invoices IS "
                + "'DDL edge-case fixture for #27 / A16'");
        sourceJdbc.execute("COMMENT ON COLUMN " + SCHEMA + ".invoices.status IS "
                + "'Custom enum status'");

        // 5. Partial index
        sourceJdbc.execute("CREATE INDEX idx_invoices_pending ON "
                + SCHEMA + ".invoices (created_at) WHERE status = 'pending'");

        // 6. Simple view
        sourceJdbc.execute("CREATE VIEW " + SCHEMA + ".pending_invoices AS "
                + "SELECT id, customer_email, amount FROM " + SCHEMA + ".invoices "
                + "WHERE status = 'pending'");

        // 7. Materialized view
        sourceJdbc.execute("""
                CREATE MATERIALIZED VIEW %s.invoice_summary AS
                SELECT status, COUNT(*) AS n, COALESCE(SUM(amount), 0) AS total
                FROM %s.invoices
                GROUP BY status
                """.formatted(SCHEMA, SCHEMA));

        // 8. PL/pgSQL function with $$ ... $$ body
        sourceJdbc.execute("""
                CREATE FUNCTION %s.compute_total(p_status %s.order_status)
                RETURNS NUMERIC AS $$
                DECLARE
                    v_total NUMERIC;
                BEGIN
                    SELECT COALESCE(SUM(amount), 0) INTO v_total
                    FROM %s.invoices
                    WHERE status = p_status;
                    RETURN v_total;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(SCHEMA, SCHEMA, SCHEMA));

        // 9. Trigger (uses its own function)
        sourceJdbc.execute("""
                CREATE FUNCTION %s.touch_invoice() RETURNS TRIGGER AS $$
                BEGIN
                    NEW.created_at = NOW();
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(SCHEMA));
        sourceJdbc.execute("CREATE TRIGGER trg_invoices_touch "
                + "BEFORE UPDATE ON " + SCHEMA + ".invoices "
                + "FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".touch_invoice()");

        // Seed data so extraction has rows
        sourceJdbc.execute("INSERT INTO " + SCHEMA + ".invoices "
                + "(customer_email, status, amount) VALUES "
                + "('alice@example.com', 'pending', 100.50), "
                + "('bob@example.com',   'shipped', 250.00)");
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
        // CASCADE catches the enum type, sequence, views, functions, triggers
        sourceJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    // -------------------------------------------------------------------------
    // The actual test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("execute replicates all 9 DDL edge-case object kinds onto the target")
    void executeReplicatesAllDdlEdgeCases() throws Exception {
        agent.run(CommandEnum.EXECUTE);

        // 1. ENUM type — pg_type entry of typtype='e' in the target schema
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = ? AND t.typname = 'order_status' AND t.typtype = 'e'
                """, SCHEMA))
                .as("ENUM type 'order_status' must be present on target")
                .isEqualTo(1L);

        // 2. Sequence — pg_class kind 'S'
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = 'invoice_number_seq' AND c.relkind = 'S'
                """, SCHEMA))
                .as("named sequence 'invoice_number_seq' must be present on target")
                .isEqualTo(1L);

        // 3. Table with the custom default and the enum type as column type
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = 'invoices' AND c.relkind = 'r'
                """, SCHEMA))
                .as("base table 'invoices' must be present on target")
                .isEqualTo(1L);

        // 4. Comment on the table — pg_description
        assertThat(targetJdbc.queryForObject("""
                SELECT obj_description(c.oid, 'pg_class')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = 'invoices'
                """, String.class, SCHEMA))
                .as("table comment must be preserved on target")
                .contains("DDL edge-case fixture");

        // 5. Partial index — pg_index with non-null indpred
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = 'idx_invoices_pending'
                  AND i.indpred IS NOT NULL
                """, SCHEMA))
                .as("partial index must be present on target with WHERE predicate preserved")
                .isEqualTo(1L);

        // 6. View — pg_class kind 'v'
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = 'pending_invoices' AND c.relkind = 'v'
                """, SCHEMA))
                .as("view 'pending_invoices' must be present on target")
                .isEqualTo(1L);

        // 7. Materialized view — pg_class kind 'm'
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = 'invoice_summary' AND c.relkind = 'm'
                """, SCHEMA))
                .as("materialized view 'invoice_summary' must be present on target")
                .isEqualTo(1L);

        // 8. PL/pgSQL function — pg_proc with prolang = plpgsql
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                JOIN pg_language l ON l.oid = p.prolang
                WHERE n.nspname = ? AND p.proname IN ('compute_total', 'touch_invoice')
                  AND l.lanname = 'plpgsql'
                """, SCHEMA))
                .as("both PL/pgSQL functions (compute_total + touch_invoice) must be present on target")
                .isEqualTo(2L);

        // 9. Trigger — pg_trigger
        assertThat(queryForLong("""
                SELECT COUNT(*) FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND t.tgname = 'trg_invoices_touch'
                  AND NOT t.tgisinternal
                """, SCHEMA))
                .as("user-defined trigger 'trg_invoices_touch' must be present on target")
                .isEqualTo(1L);
    }

    private long queryForLong(String sql, Object... args) {
        Long v = targetJdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }
}
