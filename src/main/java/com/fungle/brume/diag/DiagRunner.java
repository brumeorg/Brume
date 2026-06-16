package com.fungle.brume.diag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.output.OutputMode;
import com.fungle.brume.shutdown.CancellableDataSource;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Boots the Spring context and reports its wiring/config status without touching any
 * database — the runtime half of #75 A32 (post-mortem #23i, "validation packagée"
 * strategy, Couche 2).
 *
 * <p>The {@code brume diag} subcommand exists for two purposes:
 * <ol>
 *   <li><b>CI/dev guard</b>: paired with {@code PackagedJarSmokeIT}, it lets us verify
 *       that the repackaged fat-jar boots cleanly (catches {@code BOOT-INF/}/shading/
 *       {@code META-INF/spring.factories} bugs that {@code ApplicationContextWiringIT}
 *       (#74) cannot see because it runs on Maven classes, not the jar).</li>
 *   <li><b>Ops healthcheck</b>: operators can run {@code java -jar brume.jar diag}
 *       before launching a real run, or wire it as a Docker {@code HEALTHCHECK}, to
 *       confirm the install is healthy without contacting the source/target DBs.</li>
 * </ol>
 *
 * <p>By design, this runner never opens a JDBC connection. It only inspects:
 * <ul>
 *   <li>Critical beans resolved at boot (proves Spring wiring is intact).</li>
 *   <li>Validated configuration (HMAC secret length, FPE key, output paths, etc. —
 *       enforced eagerly by {@code @PostConstruct} validators).</li>
 *   <li>Hikari pool stats — must all read zero (no connection ever attempted).</li>
 * </ul>
 *
 * <p>A non-zero pool stat would mean a regression — something in the boot path now
 * opens a connection. The diag reports it but does <b>not</b> exit non-zero on that
 * alone: it is observational. Hard validation that no {@code getConnection()} was
 * called lives in {@code DiagRunnerTest} via a counting {@code DataSource} proxy.
 */
@Component
public class DiagRunner {

    private static final Logger log = LoggerFactory.getLogger(DiagRunner.class);
    private static final Logger output = LoggerFactory.getLogger("brume.output");

    private final ApplicationContext ctx;
    private final BrumeProperties brumeProperties;
    private final ReplicationProperties replicationProperties;
    private final DataSource sourceDataSource;
    private final DataSource targetDataSource;
    private final ObjectMapper objectMapper;

    public DiagRunner(ApplicationContext ctx,
                      BrumeProperties brumeProperties,
                      ReplicationProperties replicationProperties,
                      @Qualifier("sourceDataSource") DataSource sourceDataSource,
                      @Qualifier("targetDataSource") Optional<DataSource> targetDataSource,
                      ObjectMapper objectMapper) {
        this.ctx = ctx;
        this.brumeProperties = brumeProperties;
        this.replicationProperties = replicationProperties;
        this.sourceDataSource = sourceDataSource;
        // targetDataSource is conditional on brume.sink.type=JDBC (ADR-0028 / #6b)
        this.targetDataSource = targetDataSource.orElse(null);
        this.objectMapper = objectMapper;
    }

    public int run() {
        log.debug("brume diag — boot-only health check, no DB contacted");

        DiagReport report = build();
        emit(report);

        // Always exit 0 — the diag is informational. If wiring/config were broken,
        // Spring would have failed long before reaching this method. Pool stats > 0
        // are reported in the output but not fatal — they signal regression for
        // tests/CI to assert against, not a runtime failure.
        return 0;
    }

    DiagReport build() {
        List<BeanStatus> beans = inspectBeans();
        Map<String, Object> config = inspectConfig();
        PoolStats source = poolStatsOf(sourceDataSource);
        PoolStats target = targetDataSource == null ? PoolStats.NONE : poolStatsOf(targetDataSource);

        return new DiagReport(
                "Brume",
                brumeVersion(),
                beans,
                config,
                source,
                target,
                source.isZero() && target.isZero()
        );
    }

    private String brumeVersion() {
        String impl = getClass().getPackage().getImplementationVersion();
        return impl != null ? impl : "0.0.1-SNAPSHOT";
    }

    private List<BeanStatus> inspectBeans() {
        // Beans whose presence proves the runtime wiring is intact. Adding a bean to
        // this list is a deliberate signal that it must exist at boot time — kept
        // in sync with ApplicationContextWiringIT (#74) coverage.
        String[] critical = {
                "com.fungle.brume.agent.ReplicationAgent",
                "com.fungle.brume.timeout.TimedReplicationRunner",
                "com.fungle.brume.timeout.BoundedQueryExecutor",
                "com.fungle.brume.timeout.TimeoutWarner",
                "com.fungle.brume.preflight.PreflightCheckRunner",
                "com.fungle.brume.shutdown.CancellationRegistry",
                "com.fungle.brume.config.BrumePropertiesValidator",
                "com.fungle.brume.config.ReplicationPropertiesValidator",
                "com.fungle.brume.config.SchemaConfigValidator",
                "com.fungle.brume.config.ConfigLoader",
                "com.fungle.brume.command.BrumeCommand"
        };
        List<BeanStatus> out = new ArrayList<>(critical.length);
        for (String fqn : critical) {
            String shortName = fqn.substring(fqn.lastIndexOf('.') + 1);
            try {
                Class<?> cls = Class.forName(fqn);
                Object bean = ctx.getBean(cls);
                out.add(new BeanStatus(shortName, bean != null, null));
            } catch (Exception e) {
                out.add(new BeanStatus(shortName, false, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return out;
    }

    private Map<String, Object> inspectConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("brume.config-path", brumeProperties.configPath());
        out.put("brume.hmac-algorithm", brumeProperties.hmacAlgorithm());
        out.put("brume.hmac-secret.bytes", utf8Bytes(brumeProperties.hmacSecret()));
        out.put("brume.fpe-key.bytes", utf8Bytes(brumeProperties.fpeKey()));
        out.put("brume.faker-locale", brumeProperties.fakerLocale());
        out.put("brume.pipeline-mode", String.valueOf(brumeProperties.pipelineMode()));
        out.put("brume.sink.type", String.valueOf(brumeProperties.sink().type()));
        out.put("replication.schema", replicationProperties.schema());
        out.put("replication.source.url", replicationProperties.source().url());
        out.put("replication.target.url",
                replicationProperties.target() == null ? "<not configured>" : replicationProperties.target().url());
        return out;
    }

    private static int utf8Bytes(String s) {
        return s == null ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private PoolStats poolStatsOf(DataSource ds) {
        HikariDataSource hikari = unwrapHikari(ds);
        if (hikari == null) {
            return PoolStats.UNKNOWN;
        }
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) {
            // Pool not initialized yet — Hikari is fully lazy. That's the expected state.
            return new PoolStats(0, 0, 0, 0, true);
        }
        return new PoolStats(
                pool.getTotalConnections(),
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(),
                false
        );
    }

    private HikariDataSource unwrapHikari(DataSource ds) {
        if (ds instanceof HikariDataSource h) return h;
        if (ds instanceof CancellableDataSource c) {
            return unwrapHikari(c.getDelegate());
        }
        // Test scenarios may wrap with proxies (e.g. counting DataSource) — pool stats
        // are not retrievable in that case, which is fine for diag's purpose: the test
        // asserts no-connection via the counting proxy, not the pool MXBean.
        return null;
    }

    private void emit(DiagReport report) {
        OutputMode mode = OutputMode.current();
        if (mode == OutputMode.JSON) {
            emitJson(report);
        } else {
            emitText(report);
        }
    }

    private void emitText(DiagReport r) {
        output.info("Brume {} — diagnostic", r.version());
        output.info("");
        output.info("Beans wired ({})", r.beans().stream().filter(BeanStatus::ok).count());
        for (BeanStatus b : r.beans()) {
            output.info("  [{}] {}", b.ok() ? "✓" : "✗", b.name() + (b.error() != null ? " — " + b.error() : ""));
        }
        output.info("");
        output.info("Configuration");
        r.config().forEach((k, v) -> output.info("  {} = {}", k, v));
        output.info("");
        output.info("Source pool  — {}", r.sourcePool());
        output.info("Target pool  — {}", r.targetPool());
        output.info("");
        output.info("No DB contacted: {}", r.noDbContacted() ? "yes" : "NO — investigate");
    }

    private void emitJson(DiagReport r) {
        try {
            // Emit on stdout (cf. OutputMode JSON convention — result goes to stdout,
            // logs to stderr). Use the shared mapper from JacksonConfig with pretty-print
            // applied per-call so we don't mutate the bean's global config.
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(r);
            System.out.println(json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize diag report as JSON", e);
            emitText(r);
        }
    }

    /** Wiring + config + pool snapshot, serializable to JSON. */
    public record DiagReport(
            String app,
            String version,
            List<BeanStatus> beans,
            Map<String, Object> config,
            PoolStats sourcePool,
            PoolStats targetPool,
            boolean noDbContacted
    ) {}

    public record BeanStatus(String name, boolean ok, String error) {}

    public record PoolStats(int total, int active, int idle, int waiting, boolean lazy) {
        public static final PoolStats NONE = new PoolStats(0, 0, 0, 0, true);
        public static final PoolStats UNKNOWN = new PoolStats(-1, -1, -1, -1, false);

        public boolean isZero() {
            return total == 0 && active == 0 && idle == 0 && waiting == 0;
        }

        @Override
        public String toString() {
            if (this == NONE) return "not configured (sink type ≠ JDBC)";
            if (this == UNKNOWN) return "unavailable (non-Hikari DataSource)";
            if (lazy) return "lazy (pool not initialized)";
            return "total=" + total + ", active=" + active + ", idle=" + idle + ", waiting=" + waiting;
        }
    }
}
