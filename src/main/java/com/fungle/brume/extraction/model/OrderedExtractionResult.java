package com.fungle.brume.extraction.model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class OrderedExtractionResult {

    private static final Logger log = LoggerFactory.getLogger(OrderedExtractionResult.class);

    private final SequencedMap<String, List<ExtractedRow>> rowsByTable = new LinkedHashMap<>();

    public OrderedExtractionResult() {
    }

    OrderedExtractionResult(Map<String, List<ExtractedRow>> source, List<String> tableOrder) {
        // Tables dans l'ordre topologique
        for (String table : tableOrder) {
            List<ExtractedRow> rows = source.get(table);
            if (rows != null) {
                rowsByTable.put(table, rows);
            }
        }
        // Tables absentes du tri topologique (defensive)
        source.forEach((table, rows) -> {
            if (!rowsByTable.containsKey(table)) {
                log.warn("Table '{}' absente du tri topologique — ajoutée en fin.", table);
                rowsByTable.put(table, rows);
            }
        });
    }

    public void add(ExtractedRow row) {
        rowsByTable.computeIfAbsent(row.table(), _ -> new ArrayList<>()).add(row);
    }

    public List<ExtractedRow> getRows(String table) {
        return rowsByTable.getOrDefault(table, List.of());
    }

    public Set<String> allTables() {
        return rowsByTable.sequencedKeySet();
    }

    public int totalRowCount() {
        return rowsByTable.values().stream().mapToInt(List::size).sum();
    }
}
