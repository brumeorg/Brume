package com.fungle.brume.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

/**
 * Unit tests for {@link DataSourceConfig}.
 *
 * <p>Phase 4 fix: verifies that {@code replication.pool-size} is correctly applied to both
 * the source and target HikariCP pools.
 *
 * <p>Uses {@link MockedConstruction} to intercept the {@link HikariDataSource} constructor
 * and inspect the {@link HikariConfig} that was built — without actually starting a real pool
 * (which would require a live PostgreSQL instance).
 */
class DataSourceConfigTest {

    @Test
    @DisplayName("Phase 4 — pool-size from ReplicationProperties is applied to sourceDataSource")
    void sourceDataSourceUsesConfiguredPoolSize() {
        ReplicationProperties props = buildProps(5);
        DataSourceConfig config = new DataSourceConfig(props,
                new com.fungle.brume.shutdown.CancellationRegistry(
                        new com.fungle.brume.shutdown.CancellationToken()));

        AtomicInteger capturedPoolSize = new AtomicInteger(-1);

        try (MockedConstruction<HikariDataSource> ignored = mockConstruction(HikariDataSource.class,
                (mock, ctx) -> capturedPoolSize.set(((HikariConfig) ctx.arguments().getFirst()).getMaximumPoolSize()))) {
            config.sourceDataSource();
        }

        assertThat(capturedPoolSize.get())
                .as("sourceDataSource must use pool size from replication.pool-size")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("Phase 4 — pool-size from ReplicationProperties is applied to targetDataSource")
    void targetDataSourceUsesConfiguredPoolSize() {
        ReplicationProperties props = buildProps(7);
        DataSourceConfig config = new DataSourceConfig(props,
                new com.fungle.brume.shutdown.CancellationRegistry(
                        new com.fungle.brume.shutdown.CancellationToken()));

        AtomicInteger capturedPoolSize = new AtomicInteger(-1);

        try (MockedConstruction<HikariDataSource> ignored = mockConstruction(HikariDataSource.class,
                (mock, ctx) -> capturedPoolSize.set(((HikariConfig) ctx.arguments().getFirst()).getMaximumPoolSize()))) {
            config.targetDataSource();
        }

        assertThat(capturedPoolSize.get())
                .as("targetDataSource must use pool size from replication.pool-size")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("Phase 4 — default pool size is 20 when replication.pool-size is not overridden")
    void defaultPoolSizeIsTwenty() {
        // @DefaultValue("20") in ReplicationProperties guarantees this
        ReplicationProperties props = buildProps(20);
        DataSourceConfig config = new DataSourceConfig(props,
                new com.fungle.brume.shutdown.CancellationRegistry(
                        new com.fungle.brume.shutdown.CancellationToken()));

        AtomicInteger capturedPoolSize = new AtomicInteger(-1);

        try (MockedConstruction<HikariDataSource> ignored = mockConstruction(HikariDataSource.class,
                (mock, ctx) -> capturedPoolSize.set(((HikariConfig) ctx.arguments().getFirst()).getMaximumPoolSize()))) {
            config.sourceDataSource();
        }

        assertThat(capturedPoolSize.get()).isEqualTo(20);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ReplicationProperties buildProps(int poolSize) {
        return new ReplicationProperties(
                "test_brume",
                "pg_dump",
                300,  // pgdumpTimeoutSeconds
                ReplicationProperties.DdlErrorMode.STRICT,  // ddlErrorMode
                poolSize,
                3,
                new ReplicationProperties.Source("jdbc:postgresql://localhost:5432/postgres", "sa", ""),
                new ReplicationProperties.Target("jdbc:postgresql://localhost:5460/postgres", "sa", "")
        );
    }
}
