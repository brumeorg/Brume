package com.fungle.brume.preflight;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.error.ConnectionException;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.SinkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fungle.brume.preflight.PreflightCheckRunner.LOGIN_TIMEOUT_SECONDS;
import static com.fungle.brume.preflight.PreflightCheckRunner.appendLoginTimeout;
import static com.fungle.brume.preflight.PreflightCheckRunner.parsePostgresMajor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-unit coverage of {@link PreflightCheckRunner}: helpers + one negative integration-light
 * scenario using a guaranteed-unreachable port. Positive scenarios are covered by the Docker ITs
 * in {@code PreflightCheckIT} (production preflight) and {@code BootWithoutTargetIT} (sink=DUMP/NULL).
 */
class PreflightCheckRunnerTest {

    // ---------------------------------------------------------------------
    // appendLoginTimeout
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("appendLoginTimeout — URL without query string gains ?loginTimeout")
    void appendLoginTimeout_addsQuestionMark_whenNoQueryString() {
        assertThat(appendLoginTimeout("jdbc:postgresql://h:5432/db", 2))
                .isEqualTo("jdbc:postgresql://h:5432/db?loginTimeout=2");
    }

    @Test
    @DisplayName("appendLoginTimeout — URL with existing query string gains &loginTimeout")
    void appendLoginTimeout_addsAmpersand_whenQueryStringPresent() {
        assertThat(appendLoginTimeout("jdbc:postgresql://h/db?sslmode=require", 2))
                .isEqualTo("jdbc:postgresql://h/db?sslmode=require&loginTimeout=2");
    }

    @Test
    @DisplayName("appendLoginTimeout — always appends, overriding a user-supplied longer value")
    void appendLoginTimeout_alwaysAppends_pgJdbcHonorsRightmost() {
        // We don't dedup: pgJDBC honors the rightmost loginTimeout, so our 2s wins.
        // This is the contract that lets us guarantee the <3s preflight budget.
        String result = appendLoginTimeout("jdbc:postgresql://h/db?loginTimeout=60", 2);
        assertThat(result).endsWith("&loginTimeout=2");
    }

    // ---------------------------------------------------------------------
    // parsePostgresMajor
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("parsePostgresMajor — server SELECT version() banner")
    void parsePostgresMajor_extractsFromServerBanner() {
        String banner = "PostgreSQL 16.2 on x86_64-pc-linux-gnu, compiled by gcc, 64-bit";
        assertThat(parsePostgresMajor(banner, "test", BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE))
                .isEqualTo(16);
    }

    @Test
    @DisplayName("parsePostgresMajor — pg_dump --version line")
    void parsePostgresMajor_extractsFromPgDumpVersion() {
        assertThat(parsePostgresMajor("pg_dump (PostgreSQL) 16.2\n", "test",
                BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND))
                .isEqualTo(16);
    }

    @Test
    @DisplayName("parsePostgresMajor — major version 17 (current)")
    void parsePostgresMajor_handlesMajor17() {
        assertThat(parsePostgresMajor("PostgreSQL 17.0", "test",
                BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE))
                .isEqualTo(17);
    }

    @Test
    @DisplayName("parsePostgresMajor — major version 10 (first single-major)")
    void parsePostgresMajor_handlesMajor10() {
        assertThat(parsePostgresMajor("PostgreSQL 10.21", "test",
                BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE))
                .isEqualTo(10);
    }

    @Test
    @DisplayName("parsePostgresMajor — unparseable string throws ConfigurationException")
    void parsePostgresMajor_throwsOnUnparseable() {
        assertThatThrownBy(() -> parsePostgresMajor("not a postgres banner", "test ctx",
                BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Could not parse PostgreSQL major version")
                .hasMessageContaining("test ctx");
    }

    // ---------------------------------------------------------------------
    // checkPgDumpVersionCompatibility
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("checkPgDumpVersionCompatibility — equal majors is OK")
    void compat_acceptsEqual() {
        PreflightCheckRunner runner = newRunnerWith("pg_dump");
        assertThatCode(() -> runner.checkPgDumpVersionCompatibility(16, 16))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkPgDumpVersionCompatibility — pg_dump newer than source is OK")
    void compat_acceptsHigherPgDump() {
        PreflightCheckRunner runner = newRunnerWith("pg_dump");
        assertThatCode(() -> runner.checkPgDumpVersionCompatibility(17, 16))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkPgDumpVersionCompatibility — older pg_dump throws with both versions in message")
    void compat_rejectsOlderPgDump() {
        PreflightCheckRunner runner = newRunnerWith("pg_dump");
        assertThatThrownBy(() -> runner.checkPgDumpVersionCompatibility(14, 16))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(ex -> {
                    ConfigurationException ce = (ConfigurationException) ex;
                    assertThat(ce.code()).isEqualTo(BrumeErrorCode.PREFLIGHT_PG_DUMP_VERSION_MISMATCH);
                    assertThat(ce.getMessage()).contains("14").contains("16");
                    assertThat(ce.suggestion()).contains("pg_dump >= 16");
                });
    }

    // ---------------------------------------------------------------------
    // checkPgDumpAvailable — negative
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("checkPgDumpAvailable — non-existent binary throws PREFLIGHT_PG_DUMP_NOT_FOUND")
    void pgDumpAvailable_throwsOnMissingBinary() {
        PreflightCheckRunner runner = newRunnerWith("brume-no-such-binary-" + System.nanoTime());
        assertThatThrownBy(runner::checkPgDumpAvailable)
                .isInstanceOf(ConfigurationException.class)
                .satisfies(ex -> {
                    ConfigurationException ce = (ConfigurationException) ex;
                    assertThat(ce.code()).isEqualTo(BrumeErrorCode.PREFLIGHT_PG_DUMP_NOT_FOUND);
                    assertThat(ce.suggestion()).contains("replication.pgdump-path");
                });
    }

    // ---------------------------------------------------------------------
    // checkSourceReachableAndGetVersion — negative + <3s contract
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("checkSourceReachableAndGetVersion — unreachable port fails fast (<3s) with CONNECTION_SOURCE_UNREACHABLE")
    void sourceUnreachable_failsFastUnder3Seconds() {
        // Port 1 on loopback: kernel refuses immediately on most OSes. Even if it were a
        // black-hole route, our appended loginTimeout=2 bounds the wait. The <3s budget
        // is the chief contract of A13 — guard it explicitly here.
        PreflightCheckRunner runner = newRunnerWithSource(
                "jdbc:postgresql://127.0.0.1:1/postgres", "postgres", "x");

        long t0 = System.currentTimeMillis();
        try {
            assertThatThrownBy(runner::checkSourceReachableAndGetVersion)
                    .isInstanceOf(ConnectionException.class)
                    .satisfies(ex -> assertThat(((ConnectionException) ex).code())
                            .isEqualTo(BrumeErrorCode.CONNECTION_SOURCE_UNREACHABLE));
        } finally {
            long elapsed = System.currentTimeMillis() - t0;
            assertThat(elapsed)
                    .as("preflight wall-clock budget — loginTimeout=%ds should bound this well under 3s",
                            LOGIN_TIMEOUT_SECONDS)
                    .isLessThan(3_000);
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static PreflightCheckRunner newRunnerWith(String pgdumpPath) {
        return new PreflightCheckRunner(replication(pgdumpPath,
                "jdbc:postgresql://127.0.0.1:5432/postgres"), brumeJdbc());
    }

    private static PreflightCheckRunner newRunnerWithSource(String url, String user, String pwd) {
        ReplicationProperties rp = new ReplicationProperties(
                "test_brume", "pg_dump", 300,
                ReplicationProperties.DdlErrorMode.STRICT, 20, 3,
                new ReplicationProperties.Source(url, user, pwd),
                new ReplicationProperties.Target(
                        "jdbc:postgresql://127.0.0.1:5460/postgres", "postgres", "postgres")
        );
        return new PreflightCheckRunner(rp, brumeJdbc());
    }

    private static ReplicationProperties replication(String pgdumpPath, String sourceUrl) {
        return new ReplicationProperties(
                "test_brume", pgdumpPath, 300,
                ReplicationProperties.DdlErrorMode.STRICT, 20, 3,
                new ReplicationProperties.Source(sourceUrl, "postgres", "postgres"),
                new ReplicationProperties.Target(
                        "jdbc:postgresql://127.0.0.1:5460/postgres", "postgres", "postgres")
        );
    }

    private static BrumeProperties brumeJdbc() {
        return new BrumeProperties(
                "config.yaml", "test-secret-1234", "0123456789abcdef", "HmacSHA256",
                "fr", 0.0, 0, 85, PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""),
                new BrumeProperties.SinkProperties(SinkType.JDBC, null, CompressionType.NONE,
                        new BrumeProperties.JdbcSinkProperties(CopyMode.NEVER)),
                new BrumeProperties.PlanProperties(com.fungle.brume.plan.PlanMode.EXACT),
                false,
                new BrumeProperties.OutputProperties(com.fungle.brume.output.OutputMode.TEXT)
        );
    }
}
