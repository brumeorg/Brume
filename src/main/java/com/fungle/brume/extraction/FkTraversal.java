package com.fungle.brume.extraction;

import com.fungle.brume.config.model.OrphanPolicy;
import com.fungle.brume.config.model.TableExtractionConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.OrphanReference;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Walks the FK graph around the seed rows, one distance level at a time (ADR-0050).
 *
 * <p>{@code fk_depth} is a radius : a row is copied when its FK distance to a seed row —
 * counted along FK edges in either direction, up to a parent or down to a child — is at
 * most {@code fk_depth}. At the edge of the radius, {@link OrphanPolicy} decides :
 * {@code KEEP_PARENTS} keeps climbing parents (upward only, without depth limit) so that no
 * FK is left dangling, {@code ALLOW_ORPHANS} stops and reports the references pointing out.
 *
 * <p>STREAMING writes the rows as they are accepted, BATCH collects them and {@code plan}
 * counts them from key columns only : the three paths share this walk, so they agree on the
 * copied set by construction. Each row is accepted once (dedup on its primary key, or on its
 * full content for a table without PK). Reads are sorted and edges visited in a fixed order,
 * so the acceptance order is deterministic (#30b).
 *
 * <p>Single use : one instance per run.
 */
public final class FkTraversal {

    private static final Logger log = LoggerFactory.getLogger(FkTraversal.class);

    /** How a row entered the copy — feeds the direct / FK-parent / FK-child report counters. */
    public enum Origin { DIRECT, FK_PARENT, FK_CHILD }

    /** Source reads needed by the walk. Implementations must be thread-safe (parents are read concurrently). */
    public interface RowReader {

        /** Streams the rows of a configured table, its extraction filter applied. */
        void readSeeds(String table, String filter, Consumer<ExtractedRow> consumer);

        /** Streams the rows of {@code table} whose {@code keyColumns} match one of {@code keys}. */
        void readWhereIn(String table, List<String> keyColumns, Collection<Object> keys,
                         Consumer<ExtractedRow> consumer);
    }

    /** Receives every row kept by the walk, once. */
    public interface RowSink {

        void accept(ExtractedRow row, Origin origin);

        /**
         * Called when distance level {@code level} is complete. {@code openTables} lists the
         * tables that may still receive rows later in the walk ; every other table is final.
         */
        default void levelCompleted(int level, Set<String> openTables) {
        }
    }

    /** Key columns referenced by a FK, on the parent table. */
    private record KeyRef(String table, List<String> columns) {
    }

    /** Rows accepted at one distance, reduced to the keys needed to expand them. */
    private static final class Level {
        /** FK → referenced key → number of rows of this level holding it. */
        final Map<ForeignKey, Map<Object, Long>> parentKeys = new HashMap<>();
        /** FK → keys of this level's rows that children of that FK may reference. */
        final Map<ForeignKey, Set<Object>> childKeys = new HashMap<>();
        final Set<String> tables = new LinkedHashSet<>();
        long rows;
    }

    private final DatabaseSchema schema;
    private final FkGraph graph;
    private final int radius;
    private final OrphanPolicy policy;
    private final Runnable checkpoint;

    private final Map<String, Set<Object>> seen = new HashMap<>();
    private final Map<KeyRef, Set<Object>> referencedKeys = new HashMap<>();
    private final Map<KeyRef, Set<Object>> missingInSource = new HashMap<>();
    private RowSink sink;

    /**
     * @param radius     {@code extraction.fk_depth}
     * @param policy     {@code extraction.orphan_policy}
     * @param checkpoint cancellation check point, run between reads (#24 / A22)
     */
    public FkTraversal(DatabaseSchema schema, int radius, OrphanPolicy policy, Runnable checkpoint) {
        this.schema = schema;
        this.graph = new FkGraph(schema);
        this.radius = radius;
        this.policy = policy;
        this.checkpoint = checkpoint;
    }

    /**
     * Runs the walk from the configured tables.
     *
     * @return the references left dangling — always empty under {@code KEEP_PARENTS}
     */
    public List<OrphanReference> run(List<TableExtractionConfig> seeds, RowReader reader, RowSink sink) {
        this.sink = sink;
        Level level = new Level();
        for (TableExtractionConfig seed : seeds) {
            checkpoint.run();
            reader.readSeeds(seed.table(), seed.filter(), row -> accept(row, Origin.DIRECT, level));
        }
        sink.levelCompleted(0, openTables(level, 0));

        Level current = level;
        int distance = 0;
        while (distance < radius && current.rows > 0) {
            Level next = new Level();
            climb(current, next, reader);
            descend(current, next, reader);
            distance++;
            log.debug("FK walk: {} row(s) at distance {}", next.rows, distance);
            sink.levelCompleted(distance, openTables(next, distance));
            current = next;
        }
        if (distance < radius) {
            return List.of(); // the walk ran dry inside the radius : nothing at its edge
        }

        if (policy == OrphanPolicy.ALLOW_ORPHANS) {
            return danglingReferences(current);
        }
        while (current.rows > 0) {
            Level next = new Level();
            climb(current, next, reader);
            distance++;
            log.debug("FK walk: {} parent row(s) climbed beyond fk_depth (distance {})", next.rows, distance);
            sink.levelCompleted(distance, graph.ancestors(next.tables));
            current = next;
        }
        return List.of();
    }

    /** Tables that may still receive rows once {@code level} (at {@code distance}) is done. */
    private Set<String> openTables(Level level, int distance) {
        return graph.reachable(level.tables, radius - distance, policy == OrphanPolicy.KEEP_PARENTS);
    }

    private void accept(ExtractedRow row, Origin origin, Level level) {
        TableMetadata meta = schema.get(row.table());
        if (!seen.computeIfAbsent(row.table(), _ -> new HashSet<>()).add(identity(row, meta))) {
            return;
        }
        for (ForeignKey fk : graph.childEdges(row.table())) {
            Object key = key(row, fk.toColumns());
            if (key == null) continue;
            if (!isPrimaryKey(meta, fk.toColumns())) {
                referencedKeys.computeIfAbsent(new KeyRef(fk.toTable(), fk.toColumns()), _ -> new HashSet<>()).add(key);
            }
            level.childKeys.computeIfAbsent(fk, _ -> new LinkedHashSet<>()).add(key);
        }
        for (ForeignKey fk : graph.parentEdges(row.table())) {
            Object key = key(row, fk.fromColumns());
            if (key != null) {
                level.parentKeys.computeIfAbsent(fk, _ -> new LinkedHashMap<>()).merge(key, 1L, Long::sum);
            }
        }
        level.tables.add(row.table());
        level.rows++;
        sink.accept(row, origin);
    }

    /**
     * Reads the parents of {@code from} that are not copied yet. Reads run concurrently on
     * virtual threads (one per referenced key set) ; rows are then accepted in edge order so
     * the outcome does not depend on thread scheduling. A failed read aborts the walk — a
     * partial parent set would leave dangling FKs in the target (ADR-0018).
     */
    private void climb(Level from, Level into, RowReader reader) {
        Map<KeyRef, Set<Object>> wanted = new LinkedHashMap<>();
        for (ForeignKey fk : graph.foreignKeys()) {
            Map<Object, Long> keys = from.parentKeys.get(fk);
            if (keys == null) continue;
            KeyRef ref = new KeyRef(fk.toTable(), fk.toColumns());
            for (Object key : keys.keySet()) {
                if (!isCopied(ref, key) && !missingInSource.getOrDefault(ref, Set.of()).contains(key)) {
                    wanted.computeIfAbsent(ref, _ -> new LinkedHashSet<>()).add(key);
                }
            }
        }
        if (wanted.isEmpty()) return;
        checkpoint.run();

        Map<KeyRef, Future<List<ExtractedRow>>> reads = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            wanted.forEach((ref, keys) -> reads.put(ref, executor.submit(() -> {
                List<ExtractedRow> rows = new ArrayList<>();
                reader.readWhereIn(ref.table(), ref.columns(), keys, rows::add);
                return rows;
            })));
            for (Map.Entry<KeyRef, Future<List<ExtractedRow>>> read : reads.entrySet()) {
                KeyRef ref = read.getKey();
                for (ExtractedRow parent : await(read.getValue(), ref)) {
                    accept(parent, Origin.FK_PARENT, into);
                }
                for (Object key : wanted.get(ref)) {
                    if (!isCopied(ref, key)) {
                        missingInSource.computeIfAbsent(ref, _ -> new HashSet<>()).add(key);
                    }
                }
            }
        }
    }

    /** Streams the children of {@code from}, one FK at a time. */
    private void descend(Level from, Level into, RowReader reader) {
        for (ForeignKey fk : graph.foreignKeys()) {
            Set<Object> keys = from.childKeys.get(fk);
            if (keys == null) continue;
            checkpoint.run();
            reader.readWhereIn(fk.fromTable(), fk.fromColumns(), keys, row -> accept(row, Origin.FK_CHILD, into));
        }
    }

    /** References of the edge level whose target row is not in the copy (ALLOW_ORPHANS). */
    private List<OrphanReference> danglingReferences(Level edge) {
        List<OrphanReference> orphans = new ArrayList<>();
        for (ForeignKey fk : graph.foreignKeys()) {
            Map<Object, Long> keys = edge.parentKeys.get(fk);
            if (keys == null) continue;
            KeyRef ref = new KeyRef(fk.toTable(), fk.toColumns());
            long rows = 0;
            long parents = 0;
            for (Map.Entry<Object, Long> key : keys.entrySet()) {
                if (!isCopied(ref, key.getKey())) {
                    rows += key.getValue();
                    parents++;
                }
            }
            if (rows > 0) {
                orphans.add(new OrphanReference(fk.fromTable(), fk.fromColumns(), fk.toTable(), rows, parents));
            }
        }
        return orphans;
    }

    private boolean isCopied(KeyRef ref, Object key) {
        Set<Object> keys = isPrimaryKey(schema.get(ref.table()), ref.columns())
                ? seen.get(ref.table())
                : referencedKeys.get(ref);
        return keys != null && keys.contains(key);
    }

    private static List<ExtractedRow> await(Future<List<ExtractedRow>> read, KeyRef ref) {
        try {
            return read.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FK walk interrupted while reading " + ref.table(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("FK walk: reading parents in {} failed — aborting", ref.table(), cause);
            throw new IllegalStateException("FK walk failed reading " + ref.table() + ": " + cause.getMessage(), cause);
        }
    }

    private static boolean isPrimaryKey(TableMetadata meta, List<String> columns) {
        return meta != null && !meta.primaryKeyColumns().isEmpty() && meta.primaryKeyColumns().equals(columns);
    }

    /** Dedup identity : the primary key, or the full row content for a table without one. */
    private static Object identity(ExtractedRow row, TableMetadata meta) {
        if (meta != null && !meta.primaryKeyColumns().isEmpty()) {
            Object pk = key(row, meta.primaryKeyColumns());
            if (pk != null) return pk;
        }
        return new ArrayList<>(row.data().values());
    }

    /**
     * The bare value for one column, the ordered value list for several ; {@code null} if any
     * part is null. Integral values are widened to {@code Long} : an {@code int4} FK may
     * reference an {@code int8} key.
     */
    static Object key(ExtractedRow row, List<String> columns) {
        if (columns.size() == 1) {
            return normalize(row.data().get(columns.getFirst()));
        }
        List<Object> tuple = new ArrayList<>(columns.size());
        for (String column : columns) {
            Object value = row.data().get(column);
            if (value == null) return null;
            tuple.add(normalize(value));
        }
        return tuple;
    }

    private static Object normalize(Object value) {
        return switch (value) {
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case null, default -> value;
        };
    }

    /**
     * Order of the rows read for {@code table} : its primary key, or the key columns of the
     * read for a table without one — a stable order keeps dumps byte-identical (#30b).
     */
    public static List<String> readOrder(DatabaseSchema schema, String table, List<String> keyColumns) {
        TableMetadata meta = schema.get(table);
        return meta != null && !meta.primaryKeyColumns().isEmpty() ? meta.primaryKeyColumns() : keyColumns;
    }

    /**
     * Columns the walk needs from {@code table} — primary key, FK columns, columns referenced
     * by other tables — or {@code null} (every column) for a table without primary key, whose
     * rows are deduplicated on their full content. Lets {@code plan} read keys only.
     */
    public static List<String> keyColumns(DatabaseSchema schema, String table) {
        TableMetadata meta = schema.get(table);
        if (meta == null || meta.primaryKeyColumns().isEmpty()) return null;
        Set<String> columns = new LinkedHashSet<>(meta.primaryKeyColumns());
        FkGraph graph = new FkGraph(schema);
        graph.parentEdges(table).forEach(fk -> columns.addAll(fk.fromColumns()));
        graph.childEdges(table).forEach(fk -> columns.addAll(fk.toColumns()));
        return List.copyOf(columns);
    }
}
