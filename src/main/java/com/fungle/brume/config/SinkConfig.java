package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.util.SafeOutputPath;
import com.fungle.brume.writer.JdbcSink;
import com.fungle.brume.writer.NullSink;
import com.fungle.brume.writer.Sink;
import com.fungle.brume.writer.SqlFileSink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Spring wiring for the {@link Sink} bean — selects an implementation based on
 * {@code brume.sink.type}.
 *
 * <p>{@link JdbcSink} is the default ({@code matchIfMissing=true}) so that
 * existing {@code application.yaml} files without a {@code brume.sink.*} block
 * keep their behaviour. {@link SqlFileSink} is registered when
 * {@code brume.sink.type=dump}, with {@code brume.sink.output-path} required.
 */
@Configuration
public class SinkConfig {

    @Bean
    @ConditionalOnProperty(name = "brume.sink.type", havingValue = "JDBC", matchIfMissing = true)
    public Sink jdbcSink(
            @Qualifier("targetDataSource") DataSource targetDataSource,
            @Qualifier("targetTransactionManager") PlatformTransactionManager targetTransactionManager,
            BrumeProperties brumeProperties) {
        return new JdbcSink(targetDataSource, targetTransactionManager, brumeProperties);
    }

    @Bean
    @ConditionalOnProperty(name = "brume.sink.type", havingValue = "DUMP")
    public Sink sqlFileSink(
            BrumeProperties brumeProperties,
            SchemaReplicator schemaReplicator) {
        String outputPath = brumeProperties.sink().outputPath();
        if (outputPath == null || outputPath.isBlank()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_SINK_OUTPUT_PATH_MISSING,
                    "brume.sink.type=DUMP requires brume.sink.output-path to be set",
                    "Set 'brume.sink.output-path' in application.yaml (e.g. "
                            + "'dumps/brume-dump.sql.gz'). The extension must match "
                            + "'brume.sink.compression' — '.gz' for GZIP, '.zst' for ZSTD, "
                            + "no extension for NONE.");
        }
        // Defense-in-depth — BrumePropertiesValidator already ran the same check at @PostConstruct;
        // calling it here also gives us the normalized absolute Path for free (audit § A4, ADR-0020).
        Path validated = SafeOutputPath.validate(outputPath, "brume.sink.output-path");
        return SqlFileSink.writingToFile(
                validated,
                brumeProperties.sink().compression(),
                schemaReplicator,
                brumeProperties.sink().stripTimestamps());
    }

    @Bean
    @ConditionalOnProperty(name = "brume.sink.type", havingValue = "NULL")
    public Sink nullSink() {
        return new NullSink();
    }
}
