package com.fungle.brume.schema.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of a PostgreSQL schema: all tables with their columns and foreign keys.
 *
 * <p>Built by {@link com.fungle.brume.schema.SchemaAnalyzer} from {@code information_schema}
 * queries against the source database. Consumed by
 * {@link com.fungle.brume.schema.GraphAnalyzer} for topological ordering and cycle detection,
 * and by the extraction and writer layers in later phases.
 *
 * @param tables map from unqualified table name to its full metadata;
 *               the map is unmodifiable after construction
 */
public record DatabaseSchema(Map<String, TableMetadata> tables) {

    /**
     * Returns all table names present in this schema.
     *
     * @return unmodifiable collection of table names
     */
    public Collection<String> tableNames() {
        return tables.keySet();
    }

    /**
     * Returns the metadata for a given table.
     *
     * @param table unqualified table name
     * @return the {@link TableMetadata}, or {@code null} if the table is not in this schema
     */
    public TableMetadata get(String table) {
        return tables.get(table);
    }

    /**
     * Returns {@code true} if the given table declares at least one outgoing foreign key.
     *
     * @param table unqualified table name
     * @return {@code true} if the table has FK constraints, {@code false} otherwise
     */
    public boolean hasForeignKeys(String table) {
        TableMetadata meta = tables.get(table);
        return meta != null && meta.foreignKeys() != null && !meta.foreignKeys().isEmpty();
    }

    /**
     * Names of the tables that have a composite (≥ 2 column) primary key, in schema
     * iteration order.
     *
     * @return unmodifiable list of composite-PK table names (possibly empty)
     */
    public List<String> compositePkTables() {
        return tables.values().stream()
                .filter(TableMetadata::hasCompositePrimaryKey)
                .map(TableMetadata::name)
                .toList();
    }

    /**
     * Names of the tables that declare no primary key, in schema iteration order.
     *
     * @return unmodifiable list of PK-less table names (possibly empty)
     */
    public List<String> tablesWithoutPrimaryKey() {
        return tables.values().stream()
                .filter(TableMetadata::hasNoPrimaryKey)
                .map(TableMetadata::name)
                .toList();
    }
}

