package com.fungle.brume.shutdown;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancellableDataSourceTest {

    @Test
    @DisplayName("getConnection — registers in registry, wraps in proxy, deregisters on close")
    void getConnection_registersAndDeregisters() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);
        Connection raw = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(raw);

        CancellableDataSource ds = new CancellableDataSource(delegate, registry);

        Connection proxy = ds.getConnection();
        assertThat(registry.activeConnectionCount())
                .as("registered immediately at getConnection")
                .isEqualTo(1);

        proxy.close();
        assertThat(registry.activeConnectionCount())
                .as("deregistered on Connection.close()")
                .isZero();
        verify(raw).close();
    }

    @Test
    @DisplayName("non-close methods are forwarded to the raw connection")
    void otherMethodsForwarded() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        CancellationRegistry registry = new CancellationRegistry(new CancellationToken());
        Connection raw = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(raw);
        when(raw.getAutoCommit()).thenReturn(false);

        CancellableDataSource ds = new CancellableDataSource(delegate, registry);
        Connection proxy = ds.getConnection();

        assertThat(proxy.getAutoCommit()).isFalse();
        verify(raw).getAutoCommit();
        proxy.setAutoCommit(true);
        verify(raw).setAutoCommit(true);
    }

    @Test
    @DisplayName("close() propagates to a Closeable/AutoCloseable delegate (Hikari)")
    void close_propagatesToAutoCloseableDelegate() throws Exception {
        // HikariDataSource implements Closeable — verify our wrapper triggers it.
        // Use mock with AutoCloseable to assert close propagation without spinning up Hikari.
        HikariDataSource hikari = mock(HikariDataSource.class);
        CancellationRegistry registry = new CancellationRegistry(new CancellationToken());
        CancellableDataSource ds = new CancellableDataSource(hikari, registry);

        ds.close();
        verify(hikari, times(1)).close();
    }

    @Test
    @DisplayName("close() — exception from delegate is wrapped in IOException")
    void close_wrapsDelegateException() throws Exception {
        AutoCloseable broken = mock(AutoCloseable.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(broken).close();
        // Need a DataSource that is AutoCloseable — wrap via mock with the right interface set.
        DataSource ds = mock(DataSource.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(AutoCloseable.class));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when((AutoCloseable) ds).close();

        CancellableDataSource wrapper = new CancellableDataSource(ds,
                new CancellationRegistry(new CancellationToken()));

        assertThatThrownBy(wrapper::close)
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
