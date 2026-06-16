package com.fungle.brume.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.PipelineMode;
import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link CheckpointService}. */
class CheckpointServiceTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    private BrumeProperties propsWith(BrumeProperties.CheckpointProperties cp, Path configFile) {
        return new BrumeProperties(
                configFile.toString(),
                "test-secret-16bytes",
                "test-fpe-key-16b",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new BrumeProperties.SubstitutionDictProperties(1_000_000L),
                new BrumeProperties.ReportProperties("", "", ""),
                new BrumeProperties.SinkProperties(
                        com.fungle.brume.writer.SinkType.JDBC, null,
                        com.fungle.brume.writer.CompressionType.NONE,
                        new BrumeProperties.JdbcSinkProperties(com.fungle.brume.writer.CopyMode.NEVER)),
                new BrumeProperties.PlanProperties(com.fungle.brume.plan.PlanMode.EXACT),
                false,
                new BrumeProperties.OutputProperties(com.fungle.brume.output.OutputMode.TEXT),
                BrumeProperties.TimeoutsProperties.defaults(),
                BrumeProperties.AuditProperties.defaults(),
                BrumeProperties.PreflightProperties.defaults(),
                cp
        );
    }

    private ReplicationProperties replicationProps() {
        return new ReplicationProperties(
                "test_brume", "pg_dump", 300,
                ReplicationProperties.DdlErrorMode.STRICT, 20, 3,
                new ReplicationProperties.Source("jdbc:postgresql://s/p", "u", "p"),
                new ReplicationProperties.Target("jdbc:postgresql://t/p", "u", "p"));
    }

    private DatabaseSchema schemaWith(String... tables) {
        java.util.Map<String, TableMetadata> map = new java.util.LinkedHashMap<>();
        for (String t : tables) {
            map.put(t, new TableMetadata(t,
                    List.of(new ColumnMetadata("id", "bigint", false)),
                    List.of(), "id"));
        }
        return new DatabaseSchema(java.util.Collections.unmodifiableMap(map));
    }

    private Path writeYaml(String content) throws IOException {
        Path p = tempDir.resolve("config.yaml");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("disabled: boot is no-op, shouldSkip always false, markCompleted no-op")
    void disabledIsNoOp() throws IOException {
        Path configFile = writeYaml("extraction:\n  tables: [users]\n");
        Path cpFile = tempDir.resolve("brume-checkpoint.json");
        BrumeProperties props = propsWith(
                new BrumeProperties.CheckpointProperties(false, cpFile.toString()),
                configFile);

        CheckpointService svc = new CheckpointService(props, replicationProps(), mapper());
        svc.boot(schemaWith("users", "orders"));

        assertThat(svc.shouldSkip("users")).isFalse();
        svc.markCompleted("users");
        assertThat(Files.exists(cpFile)).as("no checkpoint file when disabled").isFalse();
    }

    @Test
    @DisplayName("enabled fresh: boot creates empty state, markCompleted writes")
    void freshBootAndWrite() throws IOException {
        Path configFile = writeYaml("extraction:\n  tables: [users]\n");
        Path cpFile = tempDir.resolve("brume-checkpoint.json");
        BrumeProperties props = propsWith(
                new BrumeProperties.CheckpointProperties(true, cpFile.toString()),
                configFile);

        CheckpointService svc = new CheckpointService(props, replicationProps(), mapper());
        svc.boot(schemaWith("users", "orders"));

        assertThat(svc.shouldSkip("users")).isFalse();
        svc.markCompleted("users");

        assertThat(Files.exists(cpFile)).isTrue();
        String written = Files.readString(cpFile);
        assertThat(written).contains("\"users\"").contains("configHash");
    }

    @Test
    @DisplayName("enabled existing checkpoint: shouldSkip returns true for completed tables")
    void boostExistingCheckpoint() throws IOException {
        Path configFile = writeYaml("extraction:\n  tables: [users]\n");
        Path cpFile = tempDir.resolve("brume-checkpoint.json");
        String configHash = ConfigHash.of(configFile);

        // Seed an existing checkpoint
        CheckpointStore seedStore = new CheckpointStore(cpFile, mapper());
        seedStore.write(CheckpointState
                .initial("run-prev", "test_brume", configHash, java.time.Instant.now())
                .withTableCompleted("users", java.time.Instant.now()));

        BrumeProperties props = propsWith(
                new BrumeProperties.CheckpointProperties(true, cpFile.toString()),
                configFile);
        CheckpointService svc = new CheckpointService(props, replicationProps(), mapper());
        svc.boot(schemaWith("users", "orders"));

        assertThat(svc.shouldSkip("users")).isTrue();
        assertThat(svc.shouldSkip("orders")).isFalse();
    }

    @Test
    @DisplayName("config drift: boot throws CHECKPOINT_CONFIG_DRIFT")
    void configDriftRefuses() throws IOException {
        Path configFile = writeYaml("extraction:\n  tables: [users]\n");
        Path cpFile = tempDir.resolve("brume-checkpoint.json");

        CheckpointStore seedStore = new CheckpointStore(cpFile, mapper());
        seedStore.write(CheckpointState
                .initial("run-prev", "test_brume", "STALE_HASH", java.time.Instant.now())
                .withTableCompleted("users", java.time.Instant.now()));

        BrumeProperties props = propsWith(
                new BrumeProperties.CheckpointProperties(true, cpFile.toString()),
                configFile);
        CheckpointService svc = new CheckpointService(props, replicationProps(), mapper());

        assertThatThrownBy(() -> svc.boot(schemaWith("users")))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> {
                    ConfigurationException ce = (ConfigurationException) e;
                    assert ce.code() == BrumeErrorCode.CHECKPOINT_CONFIG_DRIFT;
                })
                .hasMessageContaining("hash drift");
    }

    @Test
    @DisplayName("table drift out of schema: WARN + continue (no exception)")
    void tableDriftWarnsButContinues() throws IOException {
        Path configFile = writeYaml("extraction:\n  tables: [users]\n");
        Path cpFile = tempDir.resolve("brume-checkpoint.json");
        String configHash = ConfigHash.of(configFile);

        CheckpointStore seedStore = new CheckpointStore(cpFile, mapper());
        seedStore.write(CheckpointState
                .initial("run-prev", "test_brume", configHash, java.time.Instant.now())
                .withTableCompleted("users", java.time.Instant.now())
                .withTableCompleted("removed_table", java.time.Instant.now()));

        BrumeProperties props = propsWith(
                new BrumeProperties.CheckpointProperties(true, cpFile.toString()),
                configFile);
        CheckpointService svc = new CheckpointService(props, replicationProps(), mapper());

        // Schema only contains 'users' — 'removed_table' is in the checkpoint but not the schema
        svc.boot(schemaWith("users"));
        // shouldSkip still returns true for the removed table (idempotent),
        // and false for tables still in the schema and not yet processed
        assertThat(svc.shouldSkip("removed_table")).isTrue();
    }

    @Test
    @DisplayName("invalid checkpoint file: boot throws CHECKPOINT_FILE_INVALID")
    void invalidFileRefuses() throws IOException {
        Path configFile = writeYaml("a: b\n");
        Path cpFile = tempDir.resolve("brume-checkpoint.json");
        Files.writeString(cpFile, "{ not valid json", StandardCharsets.UTF_8);

        BrumeProperties props = propsWith(
                new BrumeProperties.CheckpointProperties(true, cpFile.toString()),
                configFile);
        CheckpointService svc = new CheckpointService(props, replicationProps(), mapper());

        assertThatThrownBy(() -> svc.boot(schemaWith("users")))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> {
                    ConfigurationException ce = (ConfigurationException) e;
                    assert ce.code() == BrumeErrorCode.CHECKPOINT_FILE_INVALID;
                });
    }

    @Test
    @DisplayName("boot called twice is idempotent (re-reads from disk for the new run)")
    void doubleBootIsIdempotent() throws IOException {
        Path configFile = writeYaml("a: b\n");
        Path cpFile = tempDir.resolve("brume-checkpoint.json");
        BrumeProperties props = propsWith(
                new BrumeProperties.CheckpointProperties(true, cpFile.toString()),
                configFile);
        CheckpointService svc = new CheckpointService(props, replicationProps(), mapper());

        svc.boot(schemaWith("users"));
        svc.markCompleted("users");

        // Second boot must NOT throw and must re-read the persisted state.
        svc.boot(schemaWith("users"));
        assertThat(svc.shouldSkip("users"))
                .as("second boot should re-read the on-disk checkpoint")
                .isTrue();
    }
}
