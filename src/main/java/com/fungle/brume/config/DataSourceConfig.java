package com.fungle.brume.config;

import com.fungle.brume.shutdown.CancellableDataSource;
import com.fungle.brume.shutdown.CancellationRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Spring configuration that creates the two {@link DataSource} beans ({@code sourceDataSource}
 * and {@code targetDataSource}), the {@code sourceJdbcTemplate} wrapper, and the
 * {@link PlatformTransactionManager} for the target database used by {@code JdbcSink}.
 *
 * <p>The two {@code target*} beans are gated on {@code brume.sink.type=JDBC}
 * ({@code matchIfMissing=true} preserves the default). When the sink is {@code DUMP}
 * or {@code NULL} (the latter set programmatically by {@code plan} / {@code dry-run}),
 * no target {@link DataSource} is created — so the application boots without
 * a {@code replication.target.url} being declared or reachable. See ADR-0028.
 */
@Configuration
public class DataSourceConfig {

    private final ReplicationProperties replicationProperties;
    private final CancellationRegistry cancellationRegistry;

    public DataSourceConfig(ReplicationProperties replicationProperties,
                            CancellationRegistry cancellationRegistry) {
        this.replicationProperties = replicationProperties;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Bean
    @DependsOn("replicationPropertiesValidator")
    public DataSource sourceDataSource() {
        HikariConfig config = new HikariConfig();
        ReplicationProperties.Source source = replicationProperties.source();
        config.setJdbcUrl(source.url());
        config.setUsername(source.username());
        config.setPassword(source.password());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(replicationProperties.poolSize());
        // Lazy init (#19/A13): without this, Hikari opens a probe connection during bean
        // construction and a wrong URL makes Spring context startup fail with a Hikari
        // stack trace — short-circuiting the preflight runner that exists precisely to
        // produce a clean, typed error. With initializationFailTimeout=-1 the pool is
        // created without probing; the first getConnection() opens the first connection
        // and any failure surfaces inside the pipeline (where BrumeException handling kicks
        // in). The preflight runner uses DriverManager (not this pool) so it remains the
        // first thing that hits the source/target — exactly the order we want.
        config.setInitializationFailTimeout(-1);
        // Wrap in CancellableDataSource (#24/A22) so SIGTERM can abort blocking JDBC calls
        // like CopyManager.copyIn or long SELECT cursors via Connection.abort().
        return new CancellableDataSource(new HikariDataSource(config), cancellationRegistry);
    }

    @Bean
    @DependsOn("replicationPropertiesValidator")
    @ConditionalOnProperty(name = "brume.sink.type", havingValue = "JDBC", matchIfMissing = true)
    public DataSource targetDataSource() {
        HikariConfig config = new HikariConfig();
        ReplicationProperties.Target target = replicationProperties.target();
        config.setJdbcUrl(target.url());
        config.setUsername(target.username());
        config.setPassword(target.password());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(replicationProperties.poolSize());
        // Lazy init — cf. sourceDataSource() above.
        config.setInitializationFailTimeout(-1);
        return new CancellableDataSource(new HikariDataSource(config), cancellationRegistry);
    }

    @Bean
    public JdbcTemplate sourceJdbcTemplate(@Qualifier("sourceDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * Transaction manager bound exclusively to the source database.
     *
     * <p>Qualified as {@code "sourceTransactionManager"} so {@code BoundedQueryExecutor}
     * (#23 / A21, ADR-0033) can wrap bounded queries in a short transaction and apply
     * {@code SET LOCAL statement_timeout} without polluting the connection's session state
     * — the timeout vanishes at COMMIT. Source pool is read-only by convention; the manager
     * is wired specifically for this read-only transactional pattern.
     */
    @Bean
    @Qualifier("sourceTransactionManager")
    public PlatformTransactionManager sourceTransactionManager(
            @Qualifier("sourceDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    /**
     * Transaction manager bound exclusively to the target database.
     *
     * <p>Qualified as {@code "targetTransactionManager"} so that {@code JdbcSink} can inject
     * it without ambiguity. Gated on {@code brume.sink.type=JDBC} for symmetry with
     * {@link #targetDataSource()} — its only consumer ({@code SinkConfig.jdbcSink}) is itself
     * JDBC-only.
     *
     * @param ds the target {@link DataSource}
     * @return a {@link DataSourceTransactionManager} for the target database
     */
    @Bean
    @Qualifier("targetTransactionManager")
    @ConditionalOnProperty(name = "brume.sink.type", havingValue = "JDBC", matchIfMissing = true)
    public PlatformTransactionManager targetTransactionManager(
            @Qualifier("targetDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}