package com.fungle.brume.timeout;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.RunTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Wraps a bounded source query inside a short read-only transaction with a
 * {@code SET LOCAL statement_timeout} guard (#23 / A21, ADR-0033).
 *
 * <p>Bounded queries are operations whose run time scales with the schema size, not the
 * row count: {@code COUNT(*)}, FK closure SQL, {@code pg_class.reltuples} estimates,
 * {@code information_schema} introspection. Streaming reads ({@code CursorReader}) and
 * COPY operations ({@code JdbcSink}) are <strong>not</strong> routed through this helper
 * because applying a session-wide {@code statement_timeout} to them would fire on the
 * cursor scan as a whole — a false positive that would kill legitimate long runs.
 *
 * <p>Mechanic: a {@link TransactionTemplate} opens a transaction on the source
 * {@code DataSource}, the executor emits {@code SET LOCAL statement_timeout = N} on the
 * bound connection, then runs the caller's work via the same {@code JdbcTemplate}
 * (Spring auto-binds the connection through {@code DataSourceUtils}). On COMMIT, the
 * {@code LOCAL} scope ensures the timeout disappears with the transaction — no session
 * state leaks back to Hikari.
 *
 * <p>If Postgres cancels the query, pgJDBC raises a {@link QueryTimeoutException}; we
 * map it to {@link RunTimeoutException} with code {@link BrumeErrorCode#RUN_TIMEOUT_STATEMENT}
 * so the {@code BrumeExecutionExceptionHandler} formats it consistently with the rest of
 * the typed-error grid and exits with code 7.
 *
 * <p>When {@code brume.timeouts.statement-seconds = 0}, the executor is a transparent
 * pass-through (no transaction, no SET LOCAL, no WARN scheduling) so users who run
 * Brume against trusted DBs pay zero overhead.
 */
@Component
public class BoundedQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(BoundedQueryExecutor.class);

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate sourceJdbcTemplate;
    private final TimeoutWarner warner;
    private final int statementTimeoutSeconds;

    @Autowired
    public BoundedQueryExecutor(
            @Qualifier("sourceTransactionManager") PlatformTransactionManager txManager,
            @Qualifier("sourceJdbcTemplate") JdbcTemplate sourceJdbcTemplate,
            TimeoutWarner warner,
            BrumeProperties brumeProperties) {
        this(new TransactionTemplate(txManager), sourceJdbcTemplate, warner,
                brumeProperties.timeouts().statementSeconds());
        this.transactionTemplate.setReadOnly(true);
        log.info("BoundedQueryExecutor configured with statement-seconds={} (0=disabled)",
                this.statementTimeoutSeconds);
    }

    private BoundedQueryExecutor(TransactionTemplate transactionTemplate,
                                 JdbcTemplate sourceJdbcTemplate,
                                 TimeoutWarner warner,
                                 int statementTimeoutSeconds) {
        this.transactionTemplate = transactionTemplate;
        this.sourceJdbcTemplate = sourceJdbcTemplate;
        this.warner = warner;
        this.statementTimeoutSeconds = statementTimeoutSeconds;
    }

    /**
     * Test-only factory that returns a pass-through executor — the work runs on the given
     * {@code JdbcTemplate} with no transaction, no {@code SET LOCAL}, no WARN scheduling.
     * Useful for unit tests that mock {@code JdbcTemplate} and don't want to also stub a
     * {@code PlatformTransactionManager}. The production constructor remains the only path
     * for the Spring container.
     */
    public static BoundedQueryExecutor passthrough(JdbcTemplate jdbcTemplate) {
        return new BoundedQueryExecutor(null, jdbcTemplate, null, 0);
    }

    /**
     * Runs {@code work} as a bounded query. The work receives the same {@code JdbcTemplate}
     * that participates in the surrounding transaction — any call it makes is subject to
     * the {@code SET LOCAL statement_timeout} that has just been issued.
     *
     * @param operationName label used by the WARN at 50% (e.g. {@code "PlanEstimator.countTable[users]"})
     * @param work          the bounded SQL operation; never invoked off the transaction
     * @return whatever {@code work} returns
     */
    public <T> T execute(String operationName, Function<JdbcTemplate, T> work) {
        if (statementTimeoutSeconds <= 0) {
            return work.apply(sourceJdbcTemplate);
        }
        long timeoutMs = statementTimeoutSeconds * 1000L;
        return transactionTemplate.execute(status -> {
            sourceJdbcTemplate.execute("SET LOCAL statement_timeout = " + timeoutMs);
            try (TimeoutWarner.Handle h = warner.scheduleHalfwayWarning(operationName, statementTimeoutSeconds)) {
                try {
                    return work.apply(sourceJdbcTemplate);
                } catch (QueryTimeoutException qte) {
                    throw runTimeout(operationName, qte);
                }
            }
        });
    }

    /** Convenience overload for void operations (e.g. {@code jdbcTemplate.query(sql, rowHandler)}). */
    public void executeVoid(String operationName, Consumer<JdbcTemplate> work) {
        execute(operationName, jdbc -> {
            work.accept(jdbc);
            return null;
        });
    }

    private RunTimeoutException runTimeout(String operationName, QueryTimeoutException cause) {
        return new RunTimeoutException(
                BrumeErrorCode.RUN_TIMEOUT_STATEMENT,
                "Bounded query '" + operationName + "' exceeded brume.timeouts.statement-seconds="
                        + statementTimeoutSeconds + "s.",
                "Raise brume.timeouts.statement-seconds, or switch to brume.plan.mode=ESTIMATE "
                        + "if this is a slow COUNT(*) on a very large table (see README §Timeouts).",
                cause);
    }
}
