package com.fungle.brume.replicator;

import com.fungle.brume.config.ReplicationProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the DDL cache (#13, B14, ADR-0004).
 *
 * <p>Validates the lifecycle:
 * <ol>
 *   <li>First call with cache enabled → cache miss → {@code pg_dump} runs.</li>
 *   <li>Second call without schema change → cache hit → {@code pg_dump} skipped.</li>
 *   <li>After {@code ALTER TABLE ADD COLUMN} → fingerprint changes → cache miss again.</li>
 * </ol>
 *
 * <p>Uses a unique throwaway schema {@code b14_cache_test} on the source DB so the
 * test can DROP/CREATE without interfering with other ITs.
 *
 * <p>The DDL cache directory ({@code .brume/ddl-cache/}) is cleaned in {@code @AfterAll}
 * to leave the workspace tidy.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaReplicatorCacheIT {

    private static final String SCHEMA = "b14_cache_test";
    private static final Path CACHE_DIR = Path.of(".brume", "ddl-cache");

    private static DataSource sourceDataSource;
    private static JdbcTemplate sourceJdbc;

    @BeforeAll
    static void setUpDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
        config.setUsername("postgres");
        config.setPassword("postgres");
        config.setMaximumPoolSize(3);
        sourceDataSource = new HikariDataSource(config);
        sourceJdbc = new JdbcTemplate(sourceDataSource);
    }

    @AfterAll
    static void tearDown() throws IOException {
        try {
            sourceJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } finally {
            if (sourceDataSource instanceof HikariDataSource hds) {
                hds.close();
            }
            cleanCacheDir();
        }
    }

    @BeforeEach
    void resetSchema() {
        sourceJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        sourceJdbc.execute("CREATE SCHEMA " + SCHEMA);
        sourceJdbc.execute("CREATE TABLE " + SCHEMA + ".foo (id BIGINT PRIMARY KEY, name TEXT)");
    }

    private SchemaReplicator newReplicator(boolean ddlCache) {
        var props = new ReplicationProperties(
                SCHEMA,
                "docker exec -e PGPASSWORD=postgres brume-source pg_dump",
                60,
                ReplicationProperties.DdlErrorMode.STRICT,
                3,
                3,
                new ReplicationProperties.Source(
                        "jdbc:postgresql://localhost:5432/postgres", "postgres", "postgres"),
                new ReplicationProperties.Target(
                        "jdbc:postgresql://localhost:5460/postgres", "postgres", "postgres"),
                ddlCache);
        return new SchemaReplicator(props, new com.fungle.brume.shutdown.CancellationRegistry(new com.fungle.brume.shutdown.CancellationToken()));
    }

    @Test
    @DisplayName("Cache disabled (default): every call invokes pg_dump, lastDumpWasCached=false")
    void cacheDisabled_alwaysRunsPgDump() throws Exception {
        SchemaReplicator replicator = newReplicator(false);

        String ddl1 = replicator.dumpSchema(SCHEMA);
        assertThat(replicator.lastDumpWasCached())
                .as("with cache disabled, lastDumpWasCached must be false")
                .isFalse();
        assertThat(ddl1).contains("CREATE TABLE");

        String ddl2 = replicator.dumpSchema(SCHEMA);
        assertThat(replicator.lastDumpWasCached()).isFalse();
        assertThat(ddl2).contains("CREATE TABLE");
    }

    @Test
    @DisplayName("Cache enabled: 1st call = miss, 2nd call = hit, ALTER TABLE = miss again")
    void cacheLifecycle_missThenHitThenMissOnSchemaChange() throws Exception {
        SchemaReplicator replicator = newReplicator(true);

        // 1st call — cache miss → pg_dump runs and populates the cache
        String ddl1 = replicator.dumpSchema(SCHEMA);
        assertThat(replicator.lastDumpWasCached())
                .as("first call with cache enabled must be a miss")
                .isFalse();
        assertThat(ddl1).contains("CREATE TABLE");

        // 2nd call — schema unchanged → cache hit, pg_dump skipped, identical content
        String ddl2 = replicator.dumpSchema(SCHEMA);
        assertThat(replicator.lastDumpWasCached())
                .as("second call without schema change must be a cache hit")
                .isTrue();
        assertThat(ddl2)
                .as("cached content must match the original pg_dump output byte-for-byte")
                .isEqualTo(ddl1);

        // Schema change — fingerprint must invalidate the cache
        sourceJdbc.execute("ALTER TABLE " + SCHEMA + ".foo ADD COLUMN extra_col INTEGER");

        String ddl3 = replicator.dumpSchema(SCHEMA);
        assertThat(replicator.lastDumpWasCached())
                .as("after ALTER TABLE the fingerprint differs and cache must miss")
                .isFalse();
        assertThat(ddl3)
                .as("re-dumped DDL reflects the new column")
                .contains("extra_col");
    }

    private static void cleanCacheDir() throws IOException {
        if (!Files.isDirectory(CACHE_DIR)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(CACHE_DIR)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort cleanup — leave file in place if locked
                        }
                    });
        }
    }
}
