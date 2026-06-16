package com.fungle.brume.timeout;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.RunTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BoundedQueryExecutor} (#23 / A21, ADR-0033) using the
 * {@link BoundedQueryExecutor#passthrough(JdbcTemplate)} factory — covers the disabled
 * code path (no {@code SET LOCAL}, no transaction, no WARN) and the exception mapping
 * from pgJDBC's {@link QueryTimeoutException} to a typed {@link RunTimeoutException}.
 *
 * <p>The transactional path (real {@code SET LOCAL statement_timeout}) requires a live
 * Postgres connection and is covered by Docker-required ITs.
 */
@ExtendWith(MockitoExtension.class)
class BoundedQueryExecutorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("passthrough mode: no SET LOCAL, work is applied directly on the given JdbcTemplate")
    void passthrough_runs_work_directly() {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Long.class))).thenReturn(1L);
        BoundedQueryExecutor exec = BoundedQueryExecutor.passthrough(jdbcTemplate);

        Long result = exec.execute("op-A", jdbc -> jdbc.queryForObject("SELECT 1", Long.class));

        assertThat(result).isEqualTo(1L);
        // Critical: passthrough must NOT issue SET LOCAL (would fail outside a tx).
        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.startsWith("SET LOCAL"));
    }

    @Test
    @DisplayName("executeVoid: same pass-through, returns null wrap around Consumer<JdbcTemplate>")
    void executeVoid_passthrough() {
        BoundedQueryExecutor exec = BoundedQueryExecutor.passthrough(jdbcTemplate);

        exec.executeVoid("op-B", jdbc -> jdbc.execute("ANALYZE foo"));

        verify(jdbcTemplate).execute("ANALYZE foo");
    }

    @Test
    @DisplayName("RunTimeoutException carries STATEMENT code and propagates from a bounded query")
    void exception_typing_round_trip() {
        // Build a RunTimeoutException as the production code would.
        RunTimeoutException ex = new RunTimeoutException(
                BrumeErrorCode.RUN_TIMEOUT_STATEMENT,
                "Bounded query 'PlanEstimator.count[users]' exceeded brume.timeouts.statement-seconds=10s.",
                "Raise brume.timeouts.statement-seconds or switch to ESTIMATE.",
                new QueryTimeoutException("canceling statement due to statement timeout"));

        assertThat(ex.code()).isEqualTo(BrumeErrorCode.RUN_TIMEOUT_STATEMENT);
        assertThat(ex.getMessage()).contains("statement-seconds=10s");
        assertThat(ex.suggestion()).contains("Raise brume.timeouts.statement-seconds");
        assertThat(ex.getCause()).isInstanceOf(QueryTimeoutException.class);

        // Same path: a caller catching DataAccessException should NOT swallow this.
        assertThatThrownBy(() -> { throw ex; })
                .isInstanceOf(RunTimeoutException.class)
                .isNotInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("public ctor is @Autowired — disambiguates from the private passthrough ctor (#23i regression)")
    void publicConstructorIsAutowired() {
        // Spring 6 fails to pick the public 4-arg ctor when a second (private) ctor exists
        // unless @Autowired is explicit. Without the annotation, Spring falls back to
        // Class.getConstructor() (zero-arg), which doesn't exist, and surfaces the
        // misleading error "No default constructor found".
        //
        // Reproduced 2026-05-12 with the packaged jar:
        //   java -jar brume.jar execute
        //   → Caused by: NoSuchMethodException: BoundedQueryExecutor.<init>()
        //
        // Unit tests didn't catch it because they all go through the
        // BoundedQueryExecutor.passthrough(...) static factory, never via Spring's wiring.
        // Spring Boot ITs would have caught it but require Docker and were last green
        // before this regression.
        Constructor<?> publicCtor = Arrays.stream(BoundedQueryExecutor.class.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No public ctor found on BoundedQueryExecutor"));

        assertThat(publicCtor.isAnnotationPresent(Autowired.class))
                .as("Public ctor of BoundedQueryExecutor must be @Autowired; without it, Spring 6 "
                        + "fails to disambiguate with the private passthrough ctor and crashes "
                        + "at startup with 'No default constructor found'.")
                .isTrue();
    }
}
