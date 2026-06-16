package com.fungle.brume.shutdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class CancellationRegistryTest {

    @Test
    @DisplayName("requestCancelAll — sets the token, aborts every registered Connection, destroys every Process")
    void requestCancelAll_propagatesToAllResources() throws SQLException {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);

        Connection c1 = mock(Connection.class);
        Connection c2 = mock(Connection.class);
        Process p1 = mock(Process.class);

        registry.register(c1);
        registry.register(c2);
        registry.register(p1);

        registry.requestCancelAll();

        assertThat(token.isCancelled()).isTrue();
        verify(c1).abort(any());
        verify(c2).abort(any());
        verify(p1).destroyForcibly();
    }

    @Test
    @DisplayName("requestCancelAll — swallows resource errors (best-effort)")
    void requestCancelAll_swallowsResourceErrors() throws SQLException {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);

        Connection c1 = mock(Connection.class);
        doThrow(new SQLException("abort failed")).when(c1).abort(any());
        Connection c2 = mock(Connection.class);

        registry.register(c1);
        registry.register(c2);

        // Must not propagate; c2.abort() is still invoked despite c1 failing.
        registry.requestCancelAll();
        verify(c1).abort(any());
        verify(c2).abort(any());
    }

    @Test
    @DisplayName("deregister — connection no longer aborted on cancel")
    void deregister_excludesFromCancel() throws SQLException {
        CancellationToken token = new CancellationToken();
        CancellationRegistry registry = new CancellationRegistry(token);

        Connection c1 = mock(Connection.class);
        registry.register(c1);
        registry.deregister(c1);

        registry.requestCancelAll();

        verify(c1, times(0)).abort(any());
    }

    @Test
    @DisplayName("counts reflect registry state")
    void counts_reflectState() {
        CancellationRegistry registry = new CancellationRegistry(new CancellationToken());
        assertThat(registry.activeConnectionCount()).isZero();
        assertThat(registry.activeSubprocessCount()).isZero();

        Connection c = mock(Connection.class);
        Process p = mock(Process.class);
        registry.register(c);
        registry.register(p);

        assertThat(registry.activeConnectionCount()).isEqualTo(1);
        assertThat(registry.activeSubprocessCount()).isEqualTo(1);

        registry.deregister(c);
        registry.deregister(p);
        assertThat(registry.activeConnectionCount()).isZero();
        assertThat(registry.activeSubprocessCount()).isZero();
    }
}
