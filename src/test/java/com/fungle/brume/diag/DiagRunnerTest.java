package com.fungle.brume.diag;

import com.fungle.brume.diag.DiagRunner.DiagReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strong regression guard for {@code brume diag} (#75 A32) — proves at the unit level
 * that running the diag never opens a JDBC connection, regardless of Hikari pool
 * internals. Uses a {@link Proxy}-based {@link DataSource} that counts every
 * {@code getConnection()} invocation; the test asserts the counter stays at zero.
 *
 * <p>This addresses Q2 reco C from the {@code feature-cycle} decision tables (2026-05-12):
 * Hikari pool metrics alone ({@code getTotalConnections == 0}) are insufficient to prove
 * "no connection attempted" because {@code initializationFailTimeout=-1} (ADR-0031 #19)
 * lets Hikari fail connections async without bumping the counter. Counting at the
 * {@code DataSource} interface level is the only definitive check.
 */
@SpringBootTest(args = {})
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config.yaml",
        "brume.hmac-secret=test-secret-16bytes",
        "brume.fpe-key=test-fpe-key-16b",
        "replication.source.url=jdbc:postgresql://127.0.0.1:9999/none",
        "replication.source.username=test",
        "replication.source.password=test",
        "replication.target.url=jdbc:postgresql://127.0.0.1:9999/none",
        "replication.target.username=test",
        "replication.target.password=test",
        "replication.schema=test_schema"
})
@Import(DiagRunnerTest.CountingDataSourceConfig.class)
class DiagRunnerTest {

    @Autowired
    private DiagRunner diagRunner;

    @Autowired
    @Qualifier("sourceConnectionCounter")
    private AtomicInteger sourceCounter;

    @Autowired
    @Qualifier("targetConnectionCounter")
    private AtomicInteger targetCounter;

    @Test
    @DisplayName("diag never opens a JDBC connection (Q2 reco C — counting proxy DataSource)")
    void diagOpensNoConnections() {
        sourceCounter.set(0);
        targetCounter.set(0);

        int exitCode = diagRunner.run();

        assertThat(exitCode).isEqualTo(0);
        assertThat(sourceCounter.get())
                .as("source DataSource.getConnection() must never be called during diag")
                .isZero();
        assertThat(targetCounter.get())
                .as("target DataSource.getConnection() must never be called during diag")
                .isZero();
    }

    @Test
    @DisplayName("diag report lists critical beans with ok=true status")
    void diagReportCoversCriticalBeans() {
        DiagReport report = diagRunner.build();

        assertThat(report.app()).isEqualTo("Brume");
        assertThat(report.beans()).isNotEmpty();
        assertThat(report.beans())
                .as("all critical beans must resolve at boot")
                .allMatch(DiagRunner.BeanStatus::ok);
        assertThat(report.beans())
                .extracting(DiagRunner.BeanStatus::name)
                .contains("ReplicationAgent", "BoundedQueryExecutor", "BrumeCommand",
                        "PreflightCheckRunner", "CancellationRegistry");
    }

    @Test
    @DisplayName("diag report exposes config bytes counts (proves validators ran)")
    void diagReportIncludesConfigMetadata() {
        DiagReport report = diagRunner.build();

        assertThat(report.config()).containsKeys(
                "brume.config-path",
                "brume.hmac-secret.bytes",
                "brume.fpe-key.bytes",
                "replication.schema",
                "replication.source.url"
        );
        assertThat((int) report.config().get("brume.hmac-secret.bytes"))
                .isGreaterThanOrEqualTo(16);
        assertThat((int) report.config().get("brume.fpe-key.bytes"))
                .isIn(16, 24, 32);
    }

    @TestConfiguration
    static class CountingDataSourceConfig {

        @Bean
        @Qualifier("sourceConnectionCounter")
        AtomicInteger sourceConnectionCounter() {
            return new AtomicInteger(0);
        }

        @Bean
        @Qualifier("targetConnectionCounter")
        AtomicInteger targetConnectionCounter() {
            return new AtomicInteger(0);
        }

        @Bean
        @Primary
        @Qualifier("sourceDataSource")
        DataSource sourceDataSourceCounter(
                @Qualifier("sourceConnectionCounter") AtomicInteger counter) {
            return countingDataSource(counter);
        }

        @Bean
        @Primary
        @Qualifier("targetDataSource")
        DataSource targetDataSourceCounter(
                @Qualifier("targetConnectionCounter") AtomicInteger counter) {
            return countingDataSource(counter);
        }

        private static DataSource countingDataSource(AtomicInteger counter) {
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> handle(method, args, counter)
            );
        }

        private static Object handle(Method method, Object[] args, AtomicInteger counter) throws Throwable {
            String name = method.getName();
            return switch (name) {
                case "getConnection" -> {
                    counter.incrementAndGet();
                    throw new UnsupportedOperationException(
                            "Counting DataSource: getConnection() called during diag — this is a regression. "
                                    + "diag must never establish a JDBC connection.");
                }
                case "unwrap" -> {
                    Class<?> iface = (Class<?>) args[0];
                    if (iface.isInstance(proxy(args))) yield proxy(args);
                    throw new java.sql.SQLException("Not a wrapper for " + iface);
                }
                case "isWrapperFor" -> false;
                case "getLoginTimeout" -> 0;
                case "getLogWriter" -> null;
                case "toString" -> "CountingDataSource(calls=" + counter.get() + ")";
                case "hashCode" -> System.identityHashCode(args);
                case "equals" -> args != null && args.length == 1 && args[0] == proxy(args);
                default -> null;
            };
        }

        // Tiny helper used only for the toString/equals/unwrap path of the proxy handler.
        private static Object proxy(Object[] args) {
            // The Proxy handler does not receive `proxy` separately in this lambda signature,
            // but for our purposes equality/identity is intentionally weak: the proxy is
            // unique per Spring bean and we only need it to behave as DataSource.
            return new Object();
        }
    }
}
