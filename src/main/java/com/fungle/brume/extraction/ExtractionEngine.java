package com.fungle.brume.extraction;

import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.config.model.ExtractionConfig;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.extraction.model.OrderedExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.report.OrphanReference;
import com.fungle.brume.schema.GraphAnalyzer;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates the full extraction pipeline for a given {@link AnonymizerConfig}.
 *
 * <p>Steps performed:
 * <ol>
 *   <li>Walks the FK graph with {@link FkTraversal} (ADR-0050) : the declared tables are read
 *       with their optional SQL filter, then every row within {@code fk_depth} FK hops of them,
 *       towards parents and children ; {@code orphan_policy} handles the edge of the radius.</li>
 *   <li>Re-orders the result in topological insertion order (parents before children)
 *       using {@link GraphAnalyzer#topologicalSort}.</li>
 * </ol>
 *
 * <p>The returned {@link ExtractionResult} is ready for anonymization and writing.
 */
@Component
public class ExtractionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExtractionEngine.class);

    private final CursorReader cursorReader;
    private final GraphAnalyzer graphAnalyzer;
    private final ReplicationProperties replicationProperties;

    public ExtractionEngine(
            CursorReader cursorReader,
            GraphAnalyzer graphAnalyzer,
            ReplicationProperties replicationProperties) {
        this.cursorReader = cursorReader;
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
        ExtractionConfig extraction = config.extraction();
        List<TableExtractionConfig> tableConfigs = extraction.tables();

        if (tableConfigs.isEmpty()) {
            log.warn("No tables declared in extraction.tables config — nothing to extract.");
            return new ExtractionResult().toOrdered(List.of());
        }

        String schemaName = replicationProperties.schema();
        ExtractionResult result = new ExtractionResult();

        FkTraversal.RowReader reader = new FkTraversal.RowReader() {
            @Override
            public void readSeeds(String table, String filter, Consumer<ExtractedRow> consumer) {
                log.info("Extracting table {}.{}{}", schemaName, table,
                        filter != null && !filter.isBlank() ? " (filtered)" : "");
                cursorReader.streamRows(schemaName, table, filter, consumer);
            }

            @Override
            public void readWhereIn(String table, List<String> keyColumns, Collection<Object> keys,
                                    Consumer<ExtractedRow> consumer) {
                cursorReader.streamWhereIn(schemaName, table, keyColumns, keys, null,
                        FkTraversal.readOrder(schema, table, keyColumns), extraction.fetchSize(), consumer);
            }
        };

        List<OrphanReference> orphans = new FkTraversal(schema, extraction.fkDepth(), extraction.orphanPolicy(), () -> { })
                .run(tableConfigs, reader, (row, origin) -> {
                    switch (origin) {
                        case DIRECT -> report.recordExtracted(row.table(), 1L);
                        case FK_PARENT -> report.recordFkParent(row.table(), 1L);
                        case FK_CHILD -> report.recordFkChild(row.table(), 1L);
                    }
                    TableMetadata meta = schema.get(row.table());
                    String pkColumn = (meta != null) ? meta.singlePrimaryKeyColumn() : null;
                    if (pkColumn != null) {
                        result.addWithPk(row, pkColumn);
                    } else {
                        result.add(row);
                    }
                });
        report.recordOrphanReferences(orphans);
        orphans.forEach(o -> log.warn("[ORPHANS] {} (extraction.orphan_policy: ALLOW_ORPHANS)", o.describe()));

        List<String> sortedOrder = graphAnalyzer.topologicalSort(schema);
        OrderedExtractionResult sorted = result.toOrdered(sortedOrder);

        log.info("Extraction complete — {} table(s), {} total row(s)",
                sorted.allTables().size(), sorted.totalRowCount());

        return sorted;
    }
}
