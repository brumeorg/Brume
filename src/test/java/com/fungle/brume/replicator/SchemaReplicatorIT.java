package com.fungle.brume.replicator;

import com.fungle.brume.config.ReplicationProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link SchemaReplicator} DDL error handling modes.
 * <p>
 * These tests directly invoke {@code executeDdl} via reflection to avoid loading
 * the full Spring context (which would trigger {@code ReplicationAgent} and require pg_dump).
 */
class SchemaReplicatorIT {

    private static DataSource targetDataSource;
    private static JdbcTemplate targetJdbc;

    @BeforeAll
    static void setUpDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5460/postgres");
        config.setUsername("postgres");
        config.setPassword("postgres");
        config.setMaximumPoolSize(5);
        targetDataSource = new HikariDataSource(config);
        targetJdbc = new JdbcTemplate(targetDataSource);
    }

    @AfterAll
    static void tearDownDataSource() {
        if (targetDataSource instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    @Test
    void shouldFailFastInStrictModeOnInvalidDdl() {
        //Given: a SchemaReplicator in STRICT mode (default)
        var props = new ReplicationProperties(
                "test_ddl_strict",
                "pg_dump",
                300,
                ReplicationProperties.DdlErrorMode.STRICT,
                20,
                3,
                new ReplicationProperties.Source("jdbc:postgresql://localhost:5432/postgres", "postgres", "postgres"),
                new ReplicationProperties.Target("jdbc:postgresql://localhost:5460/postgres", "postgres", "postgres")
        );
        var replicator = new SchemaReplicator(props, new com.fungle.brume.shutdown.CancellationRegistry(new com.fungle.brume.shutdown.CancellationToken()));

        // Given: DDL containing an invalid statement
        String invalidDdl = """
                CREATE SCHEMA test_ddl_strict;
                CREATE TABLE test_ddl_strict.good_table (id BIGINT PRIMARY KEY);
                CREATE TABLE test_ddl_strict.bad_table (col INVALID_TYPE);
                CREATE TABLE test_ddl_strict.another_good_table (id BIGINT PRIMARY KEY);
                """;

        // When/Then: executing the DDL should throw SQLException immediately at the bad statement
        assertThatThrownBy(() -> executeDdlDirectly(replicator, invalidDdl))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("DDL statement #3 failed")
                .hasMessageContaining("INVALID_TYPE")
                .hasMessageContaining("replication.ddl-error-mode=LENIENT");

        // And: the schema should exist (created by statement #1)
        Integer schemaCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class,
                "test_ddl_strict"
        );
        assertThat(schemaCount).isEqualTo(1);

        // And: the first table should exist (statement #2 succeeded)
        Integer goodTableCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class,
                "test_ddl_strict", "good_table"
        );
        assertThat(goodTableCount).isEqualTo(1);

        // And: the table after the error should NOT exist (statement #4 was not executed)
        Integer anotherGoodTableCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class,
                "test_ddl_strict", "another_good_table"
        );
        assertThat(anotherGoodTableCount).isZero();

        // Clean up
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_ddl_strict CASCADE");
    }

    @Test
    void shouldContinueInLenientModeOnInvalidDdl() {
        // Given: a SchemaReplicator in LENIENT mode
        var props = new ReplicationProperties(
                "test_ddl_lenient",
                "pg_dump",
                300,
                ReplicationProperties.DdlErrorMode.LENIENT,
                20,
                3,
                new ReplicationProperties.Source("jdbc:postgresql://localhost:5432/postgres", "postgres", "postgres"),
                new ReplicationProperties.Target("jdbc:postgresql://localhost:5460/postgres", "postgres", "postgres")
        );
        var replicator = new SchemaReplicator(props, new com.fungle.brume.shutdown.CancellationRegistry(new com.fungle.brume.shutdown.CancellationToken()));

        // Given: DDL containing an invalid statement
        String invalidDdl = """
                CREATE SCHEMA test_ddl_lenient;
                CREATE TABLE test_ddl_lenient.good_table (id BIGINT PRIMARY KEY);
                CREATE TABLE test_ddl_lenient.bad_table (col INVALID_TYPE);
                CREATE TABLE test_ddl_lenient.another_good_table (id BIGINT PRIMARY KEY);
                """;

        // When: executing the DDL in LENIENT mode
        assertThatNoException().isThrownBy(() -> executeDdlDirectly(replicator, invalidDdl));

        // Then: the schema should exist
        Integer schemaCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class,
                "test_ddl_lenient"
        );
        assertThat(schemaCount).isEqualTo(1);

        // And: the first table should exist (statement #2 succeeded)
        Integer goodTableCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class,
                "test_ddl_lenient", "good_table"
        );
        assertThat(goodTableCount).isEqualTo(1);

        // And: the table after the error SHOULD exist (statement #4 was executed despite #3 failing)
        Integer anotherGoodTableCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class,
                "test_ddl_lenient", "another_good_table"
        );
        assertThat(anotherGoodTableCount).isEqualTo(1);

        // Clean up
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_ddl_lenient CASCADE");
    }

    @Test
    void shouldSucceedInStrictModeWithValidDdl() {
        // Given: a SchemaReplicator in STRICT mode
        var props = new ReplicationProperties(
                "test_ddl_valid",
                "pg_dump",
                300,
                ReplicationProperties.DdlErrorMode.STRICT,
                20,
                3,
                new ReplicationProperties.Source("jdbc:postgresql://localhost:5432/postgres", "postgres", "postgres"),
                new ReplicationProperties.Target("jdbc:postgresql://localhost:5460/postgres", "postgres", "postgres")
        );
        var replicator = new SchemaReplicator(props, new com.fungle.brume.shutdown.CancellationRegistry(new com.fungle.brume.shutdown.CancellationToken()));

        // Given: valid DDL
        String validDdl = """
                CREATE SCHEMA test_ddl_valid;
                CREATE TABLE test_ddl_valid.users (id BIGINT PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE test_ddl_valid.orders (id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES test_ddl_valid.users(id));
                """;

        // When: executing valid DDL
        assertThatNoException().isThrownBy(() -> executeDdlDirectly(replicator, validDdl));

        // Then: the schema should exist
        Integer schemaCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class,
                "test_ddl_valid"
        );
        assertThat(schemaCount).isEqualTo(1);

        // And: both tables should exist
        Integer tableCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?",
                Integer.class,
                "test_ddl_valid"
        );
        assertThat(tableCount).isEqualTo(2);

        // Clean up
        targetJdbc.execute("DROP SCHEMA IF EXISTS test_ddl_valid CASCADE");
    }

    /**
     * Helper to invoke the package-private executeDdl method via reflection.
     * This avoids needing to run full pg_dump for every test.
     */
    private void executeDdlDirectly(SchemaReplicator replicator, String ddl) throws SQLException {
        try {
            var method = SchemaReplicator.class.getDeclaredMethod("executeDdl", DataSource.class, String.class);
            method.setAccessible(true);
            method.invoke(replicator, targetDataSource, ddl);
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof SQLException sqlEx) {
                throw sqlEx;
            }
            throw new RuntimeException(e);
        }
    }
}












