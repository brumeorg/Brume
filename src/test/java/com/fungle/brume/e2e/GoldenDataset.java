package com.fungle.brume.e2e;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/**
 * Golden dataset of the pseudonymized target (ADR-0049 Q5) : one TSV file per table,
 * header line + one line per row ordered by primary key, values in PostgreSQL text form
 * ({@code \N} for NULL, tab / newline / backslash escaped).
 *
 * <p>Brume is deterministic for a given secret pair (README « Key features ») so the
 * target must match the golden file cell for cell. An intentional change of output
 * (strategy, Datafaker bump) is applied with {@code -De2e.updateGolden=true} and the
 * resulting diff is reviewed in the merge request.
 */
final class GoldenDataset {

    private static final int MAX_REPORTED_DIFFS = 25;

    private GoldenDataset() {
    }

    /** Snapshot of {@code schema.table} as TSV lines, header first. */
    static List<String> snapshot(JdbcTemplate jdbc, String schema, String table, String orderBy) {
        return jdbc.query("SELECT * FROM " + schema + "." + table + " ORDER BY " + orderBy, rs -> {
            List<String> lines = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            List<String> header = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                header.add(meta.getColumnName(i));
            }
            lines.add(String.join("\t", header));
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    cells.add(escape(rs.getString(i)));
                }
                lines.add(String.join("\t", cells));
            }
            return lines;
        });
    }

    /**
     * Compares {@code actual} with the golden file, or rewrites the file when
     * {@link E2eSettings#updateGolden()} is set.
     *
     * @return the list of differences, empty when the snapshot matches
     */
    static List<String> diff(Path goldenFile, List<String> actual) {
        try {
            if (E2eSettings.updateGolden()) {
                Files.createDirectories(goldenFile.getParent());
                Files.write(goldenFile, actual, StandardCharsets.UTF_8);
                return List.of();
            }
            return compareWith(goldenFile, actual);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Like {@link #diff} but never rewrites the golden file — for scenarios that reuse it. */
    static List<String> compareWith(Path goldenFile, List<String> actual) {
        if (!Files.exists(goldenFile)) {
            return List.of("golden file " + goldenFile + " is missing — run with -De2e.updateGolden=true");
        }
        try {
            return compare(Files.readAllLines(goldenFile, StandardCharsets.UTF_8), actual);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> compare(List<String> expected, List<String> actual) {
        List<String> diffs = new ArrayList<>();
        if (!expected.getFirst().equals(actual.getFirst())) {
            diffs.add("columns differ: expected [" + expected.getFirst() + "] but was [" + actual.getFirst() + "]");
            return diffs;
        }
        String[] columns = expected.getFirst().split("\t", -1);
        if (expected.size() != actual.size()) {
            diffs.add("row count differs: expected " + (expected.size() - 1) + " but was " + (actual.size() - 1));
        }
        for (int row = 1; row < Math.min(expected.size(), actual.size()); row++) {
            String[] want = expected.get(row).split("\t", -1);
            String[] got = actual.get(row).split("\t", -1);
            for (int col = 0; col < columns.length; col++) {
                if (!want[col].equals(got[col]) && diffs.size() < MAX_REPORTED_DIFFS) {
                    diffs.add("row " + row + " (" + columns[0] + "=" + got[0] + "), column " + columns[col]
                            + ": expected <" + want[col] + "> but was <" + got[col] + ">");
                }
            }
        }
        return diffs;
    }

    private static String escape(String value) {
        if (value == null) return "\\N";
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }
}
