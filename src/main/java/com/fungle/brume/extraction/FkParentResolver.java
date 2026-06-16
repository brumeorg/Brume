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
 * Resolves missing FK parent rows for an {@link ExtractionResult} by iteratively loading
 * parent tables until referential integrity is satisfied or the maximum depth is reached.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>For each table in the current extraction result, inspect every outgoing FK.</li>
 *   <li>Collect all non-null FK column values from child rows.</li>
 *   <li>Determine which parent rows (by PK) are not yet present in the result.</li>
 *   <li>Fetch missing parent rows via {@link CursorReader#readByPrimaryKeys}.</li>
 *   <li>Repeat until no new rows are added (stable state) or {@code maxDepth} is reached.</li>
 * </ol>
 *
 * <p>FK tasks within each pass are resolved concurrently using <em>virtual threads</em>
 * ({@link Executors#newVirtualThreadPerTaskExecutor()}). This is optimal for IO-bound DB
 * queries: virtual threads block without consuming OS threads, and the HikariCP pool size
 * becomes the sole concurrency limit (no {@code ForkJoinPool.commonPool()} saturation).
 *
 * <p>If the loop is stopped by {@code maxDepth} before reaching stability, a WARN is logged.
 */
@Component
public class FkParentResolver {

    private static final Logger log = LoggerFactory.getLogger(FkParentResolver.class);
    private final CursorReader cursorReader;

    public FkParentResolver(CursorReader cursorReader) {
        this.cursorReader = cursorReader;
    }

    /**
     * Resolves missing FK parent rows for the given extraction result.
     *
     * @param result     the mutable extraction result to augment with parent rows
     * @param schema     the analyzed source schema containing FK metadata
     * @param schemaName the PostgreSQL schema name used for queries
     * @param maxDepth   maximum number of resolution passes before stopping
     * @param report     the execution report collector; FK parent counts are recorded here
     */
    public void resolve(ExtractionResult result, DatabaseSchema schema, String schemaName,
                        int maxDepth, ExecutionReport report) {
        resolveConsolidated(result, schema, schemaName, maxDepth, report);
    }

    public void resolveConsolidated(ExtractionResult result, DatabaseSchema schema, String schemaName,
                                    int maxDepth, ExecutionReport report) {
        Map<String, Set<Object>> resolvedPks = new ConcurrentHashMap<>();
        boolean stable = false;

        for (int depth = 0; depth < maxDepth && !stable; depth++) {
            int rowsBefore = result.totalRowCount();
            Set<String> currentTables = new LinkedHashSet<>(result.allTables());

            Map<ParentReference, List<ChildForeignKeyReference>> fkTasksByParent = currentTables.stream()
                    .filter(table -> schema.get(table) != null && !schema.get(table).foreignKeys().isEmpty())
                    .flatMap(table -> schema.get(table).foreignKeys().stream()
                            .map(fk -> new ChildForeignKeyReference(table, fk)))
                    .collect(Collectors.groupingBy(
                            ref -> new ParentReference(ref.foreignKey().toTable(), ref.foreignKey().toColumn()),
                            LinkedHashMap::new,
                            Collectors.toList()));

            // Resolve all FK tasks concurrently using virtual threads.
            // Virtual threads are ideal for IO-bound work: each DB query can block its
            // virtual thread without consuming an OS thread. The HikariCP pool size is
            // the only concurrency limit — no ForkJoinPool.commonPool() contention.
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = fkTasksByParent.entrySet().stream()
                        .<Future<?>>map(entry -> executor.submit(() ->
                                resolveForeignKeyGroup(result, schemaName, entry.getKey(), entry.getValue(), resolvedPks, report)))
                        .toList();

                // Wait for all FK tasks to complete. Failures are non-recoverable :
                // continuing with a partial ExtractionResult would silently produce dangling
                // FK references in the target (masked at write time by
                // session_replication_role=replica, surfaced only when a real client reads).
                // Audit § B3, ADR-0018 — fail-fast on the first task error.
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("FK parent resolution interrupted at depth {}", depth + 1);
                        return;
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        log.error("FK parent resolution task failed at depth {} — aborting pipeline",
                                depth + 1, cause);
                        if (cause instanceof RuntimeException re) throw re;
                        throw new IllegalStateException("FK parent resolution task threw unexpected checked exception at depth " + (depth + 1) + ": " + cause.getMessage(), cause);
                    }
                }
            }

            int rowsAfter = result.totalRowCount();
            stable = (rowsAfter == rowsBefore);

            if (!stable) {
                log.debug("Pass {}/{}: added {} rows during FK parent resolution.",
                        depth + 1, maxDepth, rowsAfter - rowsBefore);
            }
        }

        if (!stable) {
            log.warn("FK parent resolution stopped after {} passes without reaching a stable state. "
                    + "Consider increasing extraction.fk-depth in config.yaml.", maxDepth);
        } else {
            log.info("FK parent resolution reached stable state. Total rows: {}", result.totalRowCount());
        }
    }

    /**
     * Collects the direct FK parents referenced by a filtered child-table query, then reuses the
     * existing recursive resolver to fetch higher-level parents.
     *
     * <p>This method is tailored for the streaming pipeline: it scans only the FK columns of the
     * child table with {@code SELECT DISTINCT fk_col ...}, avoiding a full in-memory materialization
     * of child rows before parent resolution.
     *
     * @param schemaName            PostgreSQL schema name used for source queries
     * @param childTable            child table being processed
     * @param whereFilter           optional filter used by the child-table extraction
     * @param schema                analyzed database schema
     * @param maxDepth              configured FK depth; the initial direct-parent collection counts as depth 1
     * @param alreadyProcessedPks   per-table PK index of rows already written/processed, used to
     *                              avoid useless re-fetches; outer key is the parent table name,
     *                              inner set holds the raw PK values (Long, String, UUID, …)
     * @param report                execution report collector
     * @return an extraction result containing only parent rows required for {@code childTable}
     */
    public ExtractionResult resolveParentsForTable(
            String schemaName,
            String childTable,
            String whereFilter,
            DatabaseSchema schema,
            int maxDepth,
            Map<String, Set<Object>> alreadyProcessedPks,
            ExecutionReport report) {
        return resolveParentsForTable(schemaName, childTable, whereFilter, schema, maxDepth,
                1_000, alreadyProcessedPks, report);
    }

    public ExtractionResult resolveParentsForTable(
            String schemaName,
            String childTable,
            String whereFilter,
            DatabaseSchema schema,
            int maxDepth,
            int fetchSize,
            Map<String, Set<Object>> alreadyProcessedPks,
            ExecutionReport report) {

        ExtractionResult result = new ExtractionResult();
        TableMetadata childMeta = schema.get(childTable);
        if (childMeta == null || childMeta.foreignKeys() == null || childMeta.foreignKeys().isEmpty()) {
            return result;
        }

        Map<ParentReference, Set<Object>> referencedPksByParent = new LinkedHashMap<>();
        for (ForeignKey fk : childMeta.foreignKeys()) {
            Set<Object> referencedPks = referencedPksByParent.computeIfAbsent(
                    new ParentReference(fk.toTable(), fk.toColumn()),
                    _ -> new LinkedHashSet<>());
            referencedPks.addAll(cursorReader.readDistinctColumnValues(
                    schemaName, childTable, fk.fromColumn(), whereFilter, fetchSize));
        }

        for (Map.Entry<ParentReference, Set<Object>> entry : referencedPksByParent.entrySet()) {
            ParentReference parentReference = entry.getKey();
            Set<Object> referencedPks = entry.getValue();
            Set<Object> processedForTable = alreadyProcessedPks.getOrDefault(
                    parentReference.table(), Set.of());
            referencedPks.removeIf(processedForTable::contains);

            if (referencedPks.isEmpty()) {
                continue;
            }

            List<ExtractedRow> parents = cursorReader.readByPrimaryKeys(
                    schemaName, parentReference.table(), parentReference.column(), referencedPks);

            for (ExtractedRow parent : parents) {
                result.tryAddWithPk(parent, parentReference.column());
            }

            report.recordFkParent(parentReference.table(), parents.size());
        }

        int remainingDepth = Math.max(0, maxDepth - 1);
        if (remainingDepth > 0 && result.totalRowCount() > 0) {
            resolveConsolidated(result, schema, schemaName, remainingDepth, report);
        }

        return result;
    }

    private void resolveForeignKeyGroup(
            ExtractionResult result,
            String schemaName,
            ParentReference parentReference,
            List<ChildForeignKeyReference> childRefs,
            Map<String, Set<Object>> resolvedPks,
            ExecutionReport report) {

        String parentKey = parentReference.table() + "." + parentReference.column();
        Set<Object> alreadyResolved = resolvedPks.computeIfAbsent(parentKey, _ -> ConcurrentHashMap.newKeySet());

        Set<Object> missingPks = childRefs.stream()
                .flatMap(ref -> result.getRows(ref.childTable()).stream()
                        .map(row -> row.data().get(ref.foreignKey().fromColumn())))
                .filter(Objects::nonNull)
                .filter(v -> !alreadyResolved.contains(v))
                .filter(v -> !result.containsPrimaryKey(parentReference.table(), parentReference.column(), v))
                .collect(Collectors.toSet());

        if (missingPks.isEmpty()) return;

        log.debug("Resolving {} missing parent row(s) in {}.{} for {} FK path(s)",
                missingPks.size(), schemaName, parentReference.table(), childRefs.size());

        List<ExtractedRow> parents = cursorReader.readByPrimaryKeys(
                schemaName, parentReference.table(), parentReference.column(), missingPks);

        parents.forEach(row -> result.tryAddWithPk(row, parentReference.column()));

        // Record FK parent rows fetched for this parent table
        report.recordFkParent(parentReference.table(), parents.size());

        alreadyResolved.addAll(missingPks);
    }

    private record ParentReference(String table, String column) {
    }

    private record ChildForeignKeyReference(String childTable, ForeignKey foreignKey) {
    }

}
