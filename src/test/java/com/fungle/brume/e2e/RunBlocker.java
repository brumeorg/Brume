package com.fungle.brume.e2e;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Holds a run of the binary at a chosen table, so a test can signal it or let a timeout
 * fire (ADR-0062 Q4, lot B).
 *
 * <p>The table's extraction filter gets {@code AND (SELECT pg_advisory_lock_shared(KEY)) IS
 * NOT NULL} : always true, so the extracted rows — and the golden dataset — do not change, but
 * the extraction query waits as long as this session holds the exclusive advisory lock.
 * {@code EXPLAIN} (filter check) does not execute it ; the plan must run in ESTIMATE mode,
 * otherwise its {@code COUNT(*)} would block first. Closing releases the lock.
 */
final class RunBlocker implements AutoCloseable {

    static final long KEY = 4242;
    /** To append to a filter (or to use as the whole filter). */
    static final String CONDITION = "(SELECT pg_advisory_lock_shared(" + KEY + ")) IS NOT NULL";

    private static final String ORDER_ITEMS_FILTER =
            "      filter: \"order_id IN (SELECT id FROM e2e_en.orders WHERE created_at >= '2025-01-01')\"";

    /**
     * The e2e journey config ({@code brume.yml}) with {@code order_items} blockable : the
     * last table processed (after {@code audit_logs} and {@code orders}), so an interrupted
     * run has written tables to keep.
     */
    static String blockOrderItems(String yaml) {
        String normalized = yaml.replace("\r\n", "\n");
        if (!normalized.contains(ORDER_ITEMS_FILTER + "\n")) {
            throw new IllegalStateException("brume.yml no longer has the order_items filter this test relies on");
        }
        return normalized.replace(ORDER_ITEMS_FILTER + "\n",
                ORDER_ITEMS_FILTER.substring(0, ORDER_ITEMS_FILTER.length() - 1) + " AND " + CONDITION + "\"\n");
    }

    private final Connection connection;

    private RunBlocker(Connection connection) {
        this.connection = connection;
    }

    /** Takes the exclusive lock on the source database. */
    static RunBlocker hold() {
        return hold(E2eSettings.source());
    }

    /** Takes the exclusive lock on {@code db} (advisory locks are per database). */
    static RunBlocker hold(E2eSettings.Db db) {
        try {
            Connection c = DriverManager.getConnection(
                    "jdbc:postgresql://" + db.host() + ":" + db.port() + "/" + db.database(), db.user(), db.password());
            try (Statement st = c.createStatement()) {
                st.execute("SELECT pg_advisory_lock(" + KEY + ")");
            }
            return new RunBlocker(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot take the advisory lock", e);
        }
    }

    /** Waits until a session of the binary waits on the lock, i.e. the run reached the blocked table. */
    void awaitBlocked(Duration timeout, Supplier<String> diagnostics) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity "
                         + "WHERE wait_event_type = 'Lock' AND wait_event = 'advisory' AND pid <> pg_backend_pid()")) {
                if (rs.next() && rs.getInt(1) > 0) return;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("the run never reached the blocked table within " + timeout + "\n" + diagnostics.get());
    }

    /**
     * Makes every write of {@code table} on the target wait for the lock : an {@code ENABLE
     * ALWAYS} trigger created on the source, replicated to the target by pg_dump, fires even
     * under the {@code session_replication_role = replica} of Brume's writes. Gone with the next
     * {@link E2eSettings#seedSource()}, which recreates the schemas.
     */
    static void blockWritesOf(String schema, String table) {
        org.springframework.jdbc.core.JdbcTemplate source = E2eSettings.source().jdbc();
        source.execute("CREATE OR REPLACE FUNCTION " + schema + ".e2e_wait_for_lock() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_lock_shared(" + KEY + "); RETURN NEW; END $$");
        source.execute("CREATE TRIGGER e2e_wait_for_lock BEFORE INSERT ON " + schema + "." + table
                + " FOR EACH ROW EXECUTE FUNCTION " + schema + ".e2e_wait_for_lock()");
        source.execute("ALTER TABLE " + schema + "." + table + " ENABLE ALWAYS TRIGGER e2e_wait_for_lock");
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // the lock goes with the session
        }
    }
}
