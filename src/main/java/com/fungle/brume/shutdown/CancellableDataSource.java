package com.fungle.brume.shutdown;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * {@link DataSource} wrapper that registers every opened {@link Connection} with the
 * {@link CancellationRegistry} so the SIGTERM shutdown hook can abort blocking JDBC
 * calls (#24 / A22). Wraps the source and target Hikari pools in {@code DataSourceConfig}.
 *
 * <p>Registration is done at {@code getConnection()}, deregistration via a thin
 * {@link Connection} proxy that hooks {@code close()}. We don't proxy any other method —
 * everything else is forwarded to the delegate via {@code java.lang.reflect.Proxy}.
 *
 * <p>Failure mode if the registry throws during register: log + propagate. Failure
 * during deregister at close(): we swallow (the close itself succeeded; misregister
 * leaves a stale entry that the JVM exit will clean up anyway).
 */
public class CancellableDataSource implements DataSource, Closeable {

    private final DataSource delegate;
    private final CancellationRegistry registry;

    public CancellableDataSource(DataSource delegate, CancellationRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * Exposes the underlying wrapped {@link DataSource} (typically a {@code HikariDataSource})
     * for code paths that need to read pool statistics or runtime metadata without going
     * through {@link #unwrap(Class)}. Used by {@code brume diag} to read Hikari pool metrics
     * (#75 A32) and by potential future debug/observability features.
     *
     * @return the wrapped {@code DataSource} passed to the constructor
     */
    public DataSource getDelegate() {
        return delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw = delegate.getConnection();
        registry.register(raw);
        return wrap(raw);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection raw = delegate.getConnection(username, password);
        registry.register(raw);
        return wrap(raw);
    }

    private Connection wrap(Connection raw) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        try {
                            registry.deregister(raw);
                        } catch (RuntimeException ignored) {
                            // best-effort — leave a stale entry rather than fail the close
                        }
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                });
    }

    // Plain delegation for the rest of the DataSource SPI.

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }

    /**
     * Propagates close to the delegate so Spring's auto-detected destroy method on
     * {@code HikariDataSource} actually fires. Without this, the wrapping breaks the
     * "any bean with a {@code close()} method gets it called at context shutdown"
     * convention — Hikari pools accumulate across {@code @SpringBootTest} contexts,
     * exhausting Postgres {@code max_connections}.
     */
    @Override
    public void close() throws IOException {
        if (delegate instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                throw new IOException("Failed to close delegate DataSource", e);
            }
        }
    }
}
