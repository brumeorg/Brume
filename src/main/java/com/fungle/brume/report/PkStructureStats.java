package com.fungle.brume.report;

import java.util.List;

/**
 * Snapshot of the source schema's primary-key structure, surfaced in the execution report
 * (text + JSON) so operators can see how many tables have a composite or absent primary key
 * (#81a / ADR-0042).
 *
 * @param tablesWithCompositePk number of tables with a composite (≥ 2 column) primary key
 * @param tablesWithoutPk       number of tables with no primary key
 * @param compositePkTableNames names of the composite-PK tables (schema order)
 * @param noPkTableNames        names of the PK-less tables (schema order)
 */
public record PkStructureStats(
        int tablesWithCompositePk,
        int tablesWithoutPk,
        List<String> compositePkTableNames,
        List<String> noPkTableNames) {

    public PkStructureStats {
        compositePkTableNames = compositePkTableNames == null ? List.of() : List.copyOf(compositePkTableNames);
        noPkTableNames = noPkTableNames == null ? List.of() : List.copyOf(noPkTableNames);
    }

    /** Empty snapshot — no composite-PK and no PK-less tables. */
    public static PkStructureStats empty() {
        return new PkStructureStats(0, 0, List.of(), List.of());
    }

    /**
     * Builds a snapshot from the two table-name lists, deriving the counts from their sizes.
     *
     * @param compositePkTables names of the composite-PK tables
     * @param tablesWithoutPk   names of the PK-less tables
     */
    public static PkStructureStats of(List<String> compositePkTables, List<String> tablesWithoutPk) {
        List<String> composite = compositePkTables == null ? List.of() : compositePkTables;
        List<String> noPk = tablesWithoutPk == null ? List.of() : tablesWithoutPk;
        return new PkStructureStats(composite.size(), noPk.size(), composite, noPk);
    }
}
