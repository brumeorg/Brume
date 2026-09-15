package com.fungle.brume.config;

import com.fungle.brume.config.BrumeProperties.JdbcSinkProperties;
import com.fungle.brume.config.BrumeProperties.PlanProperties;
import com.fungle.brume.config.BrumeProperties.ReportProperties;
import com.fungle.brume.config.BrumeProperties.SinkProperties;
import com.fungle.brume.config.BrumeProperties.SubstitutionDictProperties;
import com.fungle.brume.plan.PlanMode;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.writer.CompressionType;
import com.fungle.brume.writer.CopyMode;
import com.fungle.brume.writer.JdbcSink;
import com.fungle.brume.writer.NullSink;
import com.fungle.brume.writer.Sink;
import com.fungle.brume.writer.SinkType;
import com.fungle.brume.writer.SqlFileSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Régression #79i — {@link SinkConfig#sink} doit dispatcher au <strong>runtime</strong>
 * sur {@link BrumeProperties.SinkProperties#type()}. Ancien wiring : trois {@code @Bean}
 * séparés gardés par {@code @ConditionalOnProperty}, condition évaluée <em>au build</em>
 * par Spring AOT → binaire natif embarquait uniquement {@code JdbcSink} et ignorait
 * {@code BRUME_SINK_TYPE=DUMP} au runtime. Ces tests instancient {@code SinkConfig}
 * directement (sans démarrer Spring) et vérifient que le {@code switch} produit
 * le bon type pour chaque valeur de {@link SinkType} — protège contre une régression
 * qui réintroduirait des conditions build-time.
 */
class SinkConfigNativeSelectionTest {

    @Test
    @DisplayName("sink.type=JDBC → JdbcSink (runtime dispatch, pas @ConditionalOnProperty AOT)")
    void jdbcSelectionReturnsJdbcSink() {
        DataSource ds = new SingleConnectionDataSource();
        PlatformTransactionManager tx = new DataSourceTransactionManager(ds);
        SinkConfig config = new SinkConfig();

        Sink sink = config.sink(
                brumeProps(SinkType.JDBC, "unused.sql"),
                provider(ds),
                provider(tx),
                mock(SchemaReplicator.class));

        assertThat(sink).isInstanceOf(JdbcSink.class);
    }

    @Test
    @DisplayName("sink.type=DUMP → SqlFileSink (runtime dispatch — coeur du fix #79i)")
    void dumpSelectionReturnsSqlFileSink() {
        SinkConfig config = new SinkConfig();

        Sink sink = config.sink(
                brumeProps(SinkType.DUMP, "target/79i-dump.sql"),
                provider(mock(DataSource.class)),
                provider(mock(PlatformTransactionManager.class)),
                mock(SchemaReplicator.class));

        assertThat(sink).isInstanceOf(SqlFileSink.class);
    }

    @Test
    @DisplayName("sink.type=NULL → NullSink (runtime dispatch)")
    void nullSelectionReturnsNullSink() {
        SinkConfig config = new SinkConfig();

        Sink sink = config.sink(
                brumeProps(SinkType.NULL, "unused.sql"),
                provider(mock(DataSource.class)),
                provider(mock(PlatformTransactionManager.class)),
                mock(SchemaReplicator.class));

        assertThat(sink).isInstanceOf(NullSink.class);
    }

    @Test
    @DisplayName("sink.type=DUMP sans output-path → ConfigurationException actionable")
    void dumpWithoutOutputPathFailsFast() {
        SinkConfig config = new SinkConfig();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.sink(
                        brumeProps(SinkType.DUMP, ""),
                        provider(mock(DataSource.class)),
                        provider(mock(PlatformTransactionManager.class)),
                        mock(SchemaReplicator.class)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("brume.sink.output-path");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getObject()).thenReturn(value);
        return op;
    }

    private static BrumeProperties brumeProps(SinkType type, String outputPath) {
        return new BrumeProperties(
                "brume.yml",
                "test-secret",
                "test-fpekey0123",
                "HmacSHA256",
                "fr",
                0.0,
                0L,
                85,
                PipelineMode.STREAMING,
                new SubstitutionDictProperties(1_000_000L),
                new ReportProperties("", "", ""),
                new SinkProperties(type, outputPath, CompressionType.NONE,
                        new JdbcSinkProperties(CopyMode.NEVER)),
                new PlanProperties(PlanMode.EXACT),
                false
        );
    }
}
