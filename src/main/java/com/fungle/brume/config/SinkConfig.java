package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.replicator.SchemaReplicator;
import com.fungle.brume.util.SafeOutputPath;
import com.fungle.brume.writer.JdbcSink;
import com.fungle.brume.writer.NullSink;
import com.fungle.brume.writer.Sink;
import com.fungle.brume.writer.SinkType;
import com.fungle.brume.writer.SqlFileSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Spring wiring for the {@link Sink} bean — selects an implementation based on
 * {@code brume.sink.type} <strong>at runtime</strong> via a factory-style {@code @Bean}
 * that dispatches on {@link BrumeProperties#sink()}.
 *
 * <p><strong>Why not {@code @ConditionalOnProperty}?</strong> Spring AOT
 * (Spring Boot's GraalVM native path) evaluates {@code @ConditionalOnProperty}
 * <em>at build time</em>, not at runtime. During {@code spring-boot:process-aot}
 * the property {@code brume.sink.type} is unset, so a {@code matchIfMissing=true}
 * on the JDBC bean would bake {@code JdbcSink} into the native image as the sole
 * {@code Sink} implementation — {@code SqlFileSink} and {@code NullSink} would
 * never exist at runtime. The runtime {@code BRUME_SINK_TYPE=DUMP} then has no
 * effect on the native binary. Cf. Spring Boot native-image reference
 * (« Understanding AOT processing ») and ticket {@code #79i}.
 *
 * <p>The runtime switch below is AOT-friendly: a single {@code Sink} bean is
 * always produced, and the {@code switch} on {@link SinkType} runs at bean
 * instantiation, so the choice is honoured whether the JAR or the native binary
 * is executed.
 *
 * <p>Optional dependencies ({@code targetDataSource}, {@code targetTransactionManager})
 * come in via {@link ObjectProvider} so DUMP/NULL modes don't force a target
 * DataSource resolution (ADR-0028 boot-without-target semantics preserved).
 */
@Configuration
public class SinkConfig {

    @Bean
    public Sink sink(
            BrumeProperties brumeProperties,
            @Qualifier("targetDataSource") ObjectProvider<DataSource> targetDataSource,
            @Qualifier("targetTransactionManager") ObjectProvider<PlatformTransactionManager> targetTransactionManager,
            SchemaReplicator schemaReplicator) {
        SinkType type = brumeProperties.sink().type();
        return switch (type) {
            case JDBC -> new JdbcSink(
                    targetDataSource.getObject(),
                    targetTransactionManager.getObject(),
                    brumeProperties);
            case DUMP -> {
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
                yield SqlFileSink.writingToFile(
                        validated,
                        brumeProperties.sink().compression(),
                        schemaReplicator,
                        brumeProperties.sink().stripTimestamps());
            }
            case NULL -> new NullSink();
        };
    }
}
