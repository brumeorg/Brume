package com.fungle.brume.extraction;

import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Resolves child rows for the currently extracted set by following FK edges in the
 * <em>reverse</em> direction (child → parent inverted to parent → children).
 *
 * <p>For each table already in the {@link ExtractionResult}, any table that holds a
 * foreign-key column referencing it is considered a child. Child rows whose FK value
 * matches a PK already in the result are fetched and added.
 *
 * <p>Iterates up to {@code maxDepth} levels so that, for example, with depth 2, both the
 * direct children of the seed tables and the grandchildren are included.
 *
 * <p>The implementation mirrors {@link FkParentResolver}: virtual threads per FK task,
 * fail-fast on error, {@link ExtractionResult#tryAddWithPk} for thread-safe deduplication.
 */
@Component
public class FkChildResolver {

    private static final Logger log = LoggerFactory.getLogger(FkChildResolver.class);
    private final CursorReader cursorReader;

    public FkChildResolver(CursorReader cursorReader) {
        this.cursorReader = cursorReader;
    }

    /**
     * Fetches child rows from tables that reference the currently extracted rows via FK.
     *
     * @param result     the mutable extraction result; child rows are added in place
     * @param schema     the analyzed source schema containing FK metadata
     * @param schemaName the PostgreSQL schema name used for queries
     * @param maxDepth   maximum number of child traversal levels
     * @param report     the execution report; child counts are recorded per child table
     */
    public void resolve(ExtractionResult result, DatabaseSchema schema, String schemaName,
                        int maxDepth, ExecutionReport report) {
        if (maxDepth <= 0) return;

        Map<String, List<ForeignKey>> inverseIndex = buildInverseIndex(schema);
        // Track which (parentTable → set of PKs) have been used as seeds to fetch children,
        // preventing re-expansion of the same rows on subsequent depth passes (cycle guard).
        Map<String, Set<Object>> expandedPks = new ConcurrentHashMap<>();

        for (int depth = 0; depth < maxDepth; depth++) {
            int rowsBefore = result.totalRowCount();

            Map<String, Set<Object>> seedPks = collectUnexpandedPks(result, schema, expandedPks);
            if (seedPks.isEmpty()) break;

            resolveOnePass(result, schema, schemaName, seedPks, inverseIndex, report);

            // Mark seeds as expanded so the next depth pass only processes newly added rows.
            seedPks.forEach((table, pks) ->
                    expandedPks.computeIfAbsent(table, _ -> ConcurrentHashMap.newKeySet()).addAll(pks));

            int rowsAfter = result.totalRowCount();
            if (rowsAfter == rowsBefore) {
                log.debug("FK child resolution reached stable state at depth {}", depth + 1);
                break;
            }
            log.debug("FK child resolution pass {}/{}: added {} rows",
                    depth + 1, maxDepth, rowsAfter - rowsBefore);
        }
    }

    private void resolveOnePass(
            ExtractionResult result,
            DatabaseSchema schema,
            String schemaName,
            Map<String, Set<Object>> seedPks,
            Map<String, List<ForeignKey>> inverseIndex,
            ExecutionReport report) {

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = seedPks.entrySet().stream()
                    .<Future<?>>flatMap(entry -> {
                        String parentTable = entry.getKey();
                        Set<Object> parentPkValues = entry.getValue();
                        return inverseIndex.getOrDefault(parentTable, List.of()).stream()
                                .map(fk -> executor.submit(() ->
                                        fetchChildRows(result, schema, schemaName, fk, parentPkValues, report)));
                    })
                    .toList();

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("FK child resolution interrupted");
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("FK child resolution task failed — aborting pipeline", cause);
                    if (cause instanceof RuntimeException re) throw re;
                    throw new IllegalStateException("FK child resolution task threw unexpected checked exception: " + cause.getMessage(), cause);
                }
            }
        }
    }

    private void fetchChildRows(
            ExtractionResult result,
            DatabaseSchema schema,
            String schemaName,
            ForeignKey fk,
            Set<Object> parentPkValues,
            ExecutionReport report) {

        // readByPrimaryKeys fetches WHERE column IN (values) — works for any column, not just PKs.
        List<ExtractedRow> children = cursorReader.readByPrimaryKeys(
                schemaName, fk.fromTable(), fk.fromColumn(), parentPkValues);

        if (children.isEmpty()) return;

        TableMetadata childMeta = schema.get(fk.fromTable());
        String childPkCol = childMeta != null ? childMeta.primaryKeyColumn() : null;

        long added = 0;
        for (ExtractedRow child : children) {
            if (childPkCol != null) {
                if (result.tryAddWithPk(child, childPkCol)) added++;
            } else {
                result.add(child);
                added++;
            }
        }

        if (added > 0) {
            log.debug("FK child resolution: {} row(s) from {} (via {}.{} → {}.{})",
                    added, fk.fromTable(),
                    fk.fromTable(), fk.fromColumn(), fk.toTable(), fk.toColumn());
            report.recordFkChild(fk.fromTable(), added);
        }
    }

    /**
     * Collects PK values of rows not yet used as child-fetch seeds, per table.
     * Only tables with a known single-column PK are included (composite-PK tables
     * cannot be reliably batched and are silently skipped).
     */
    private Map<String, Set<Object>> collectUnexpandedPks(
            ExtractionResult result,
            DatabaseSchema schema,
            Map<String, Set<Object>> expandedPks) {

        Map<String, Set<Object>> seeds = new LinkedHashMap<>();
        for (String table : result.allTables()) {
            TableMetadata meta = schema.get(table);
            if (meta == null || meta.primaryKeyColumn() == null) continue;
            String pkCol = meta.primaryKeyColumn();
            Set<Object> alreadyExpanded = expandedPks.getOrDefault(table, Set.of());

            Set<Object> newPks = result.getRows(table).stream()
                    .map(row -> row.data().get(pkCol))
                    .filter(Objects::nonNull)
                    .filter(pk -> !alreadyExpanded.contains(pk))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (!newPks.isEmpty()) {
                seeds.put(table, newPks);
            }
        }
        return seeds;
    }

    /**
     * Builds the inverse FK index: for each parent table, the list of FK edges that point to it.
     * Used to find which child tables reference any given parent table.
     */
    private Map<String, List<ForeignKey>> buildInverseIndex(DatabaseSchema schema) {
        Map<String, List<ForeignKey>> index = new HashMap<>();
        for (TableMetadata meta : schema.tables().values()) {
            for (ForeignKey fk : meta.foreignKeys()) {
                index.computeIfAbsent(fk.toTable(), _ -> new ArrayList<>()).add(fk);
            }
        }
        return index;
    }
}
