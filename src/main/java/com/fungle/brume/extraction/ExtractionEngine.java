package com.fungle.brume.extraction;

import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.GraphAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Orchestrates the full extraction pipeline for a given {@link AnonymizerConfig}.
 *
 * <p>Steps performed:
 * <ol>
 *   <li>Reads each declared table from the source database via {@link CursorReader},
 *       applying any optional SQL filter.</li>
 *   <li>Resolves FK child rows via {@link FkChildResolver}: tables that hold a FK column
 *       referencing any seeded table are fetched, up to {@code fk_depth} levels.</li>
 *   <li>Resolves missing FK parent rows via {@link FkParentResolver}: ensures referential
 *       integrity for both the seeds and the newly fetched child rows.</li>
 *   <li>Re-orders the result in topological insertion order (parents before children)
 *       using {@link GraphAnalyzer#topologicalSort}.</li>
 * </ol>
 *
 * <p>The returned {@link ExtractionResult} is ready for anonymization and writing.
 *
 * <p>Note: this component is intentionally not wired into {@code ReplicationAgent} yet —
 * that integration happens in Phase 6.
 */
@Component
public class ExtractionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExtractionEngine.class);

    private final CursorReader cursorReader;
    private final FkChildResolver fkChildResolver;
    private final FkParentResolver fkParentResolver;
    private final GraphAnalyzer graphAnalyzer;
    private final ReplicationProperties replicationProperties;

    public ExtractionEngine(
            CursorReader cursorReader,
            FkChildResolver fkChildResolver,
            FkParentResolver fkParentResolver,
            GraphAnalyzer graphAnalyzer,
            ReplicationProperties replicationProperties) {
        this.cursorReader = cursorReader;
        this.fkChildResolver = fkChildResolver;
        this.fkParentResolver = fkParentResolver;
        this.graphAnalyzer = graphAnalyzer;
        this.replicationProperties = replicationProperties;
    }

    /**
     * Extracts a coherent, FK-complete dataset from the source database.
     *
     * @param config the anonymizer configuration specifying tables and filters
     * @param schema the analyzed source database schema
     * @param report the execution report collector; extraction stats are recorded here
     * @return an ordered extraction result ready for anonymization and writing
     */
    public OrderedExtractionResult extract(AnonymizerConfig config, DatabaseSchema schema,
                                            ExecutionReport report) {
        List<TableExtractionConfig> tableConfigs = config.extraction().tables();

        if (tableConfigs.isEmpty()) {
            log.warn("No tables declared in extraction.tables config — nothing to extract.");
            return new ExtractionResult().toOrdered(List.of());
        }

        String schemaName = replicationProperties.schema();
        ExtractionResult result = new ExtractionResult();

        for (TableExtractionConfig tableConfig : tableConfigs) {
            String tableName = tableConfig.table();
            String filter = tableConfig.filter();

            boolean hasFilter = filter != null && !filter.isBlank();
            log.info("Extracting table {}.{}{}", schemaName, tableName,
                    hasFilter ? " (filtered)" : "");

            List<ExtractedRow> rows = cursorReader.read(schemaName, tableName, filter);

            TableMetadata meta = schema.get(tableName);
            String pkColumn = (meta != null) ? meta.singlePrimaryKeyColumn() : null;
            if (pkColumn != null) {
                rows.forEach(row -> result.addWithPk(row, pkColumn));
            } else {
                rows.forEach(result::add);
            }

            // Record direct extraction count for this table
            report.recordExtracted(tableName, (long) rows.size());
        }

        fkChildResolver.resolve(result, schema, schemaName, config.extraction().fkDepth(), report);

        fkParentResolver.resolve(result, schema, schemaName, config.extraction().fkDepth(), report);

        List<String> sortedOrder = graphAnalyzer.topologicalSort(schema);
        OrderedExtractionResult sorted = result.toOrdered(sortedOrder);

        log.info("Extraction complete — {} table(s), {} total row(s)",
                sorted.allTables().size(), sorted.totalRowCount());

        return sorted;
    }
}

