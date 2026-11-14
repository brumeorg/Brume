package com.fungle.brume.replicator;

/**
 * Removes from {@code pg_dump --schema-only} output the lines that make the DDL
 * non-replayable on an older PostgreSQL server or with an older {@code psql}
 * (ADR-0058).
 *
 * <p>Removed, only when they appear at top level (outside a string or a
 * dollar-quoted body, so function bodies are never altered):
 * <ul>
 *   <li>psql meta-commands — notably <code>&#92;restrict &lt;key&gt;</code> / <code>&#92;unrestrict &lt;key&gt;</code>,
 *       emitted since the August 2025 minors (17.6, 16.10, 15.14, …; CVE-2025-8714),
 *       rejected by older {@code psql} with {@code invalid command} and meaningless
 *       over JDBC;</li>
 *   <li>{@code SET transaction_timeout …}, emitted by {@code pg_dump} ≥ 17 and rejected
 *       by servers ≤ 16 ({@code unrecognized configuration parameter}).</li>
 * </ul>
 *
 * <p>Pure function, applied once at the exit of {@link SchemaReplicator#dumpSchema(String)}
 * so that every DDL consumer (JDBC replay, dump sink) gets the same output.
 */
public final class PgDumpDdlSanitizer {

    private PgDumpDdlSanitizer() {
    }

    public static String sanitize(String ddl) {
        if (ddl == null || ddl.isEmpty()) {
            return ddl;
        }
        StringBuilder out = new StringBuilder(ddl.length());
        LexState state = new LexState();
        int start = 0;
        while (start < ddl.length()) {
            int nl = ddl.indexOf('\n', start);
            int end = nl == -1 ? ddl.length() : nl + 1;
            String line = ddl.substring(start, end);
            if (!(state.atTopLevel() && isIncompatibleTopLevelLine(line))) {
                out.append(line);
            }
            state.consume(line);
            start = end;
        }
        return out.toString();
    }

    private static boolean isIncompatibleTopLevelLine(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("\\")
                || trimmed.regionMatches(true, 0, "SET transaction_timeout", 0, 23);
    }

    /** Minimal SQL lexer state carried across lines : single-quoted string or dollar-quoted body. */
    private static final class LexState {
        private boolean inSingleQuote;
        private String dollarTag; // non-null while inside $tag$ … $tag$

        boolean atTopLevel() {
            return !inSingleQuote && dollarTag == null;
        }

        void consume(String line) {
            int i = 0;
            while (i < line.length()) {
                if (dollarTag != null) {
                    int close = line.indexOf(dollarTag, i);
                    if (close == -1) {
                        return;
                    }
                    i = close + dollarTag.length();
                    dollarTag = null;
                } else if (inSingleQuote) {
                    int close = line.indexOf('\'', i);
                    if (close == -1) {
                        return;
                    }
                    i = close + 1;
                    inSingleQuote = false;
                } else {
                    char c = line.charAt(i);
                    if (c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                        return; // line comment
                    }
                    if (c == '\'') {
                        inSingleQuote = true;
                        i++;
                    } else if (c == '$') {
                        String tag = dollarTagAt(line, i);
                        if (tag != null) {
                            dollarTag = tag;
                            i += tag.length();
                        } else {
                            i++;
                        }
                    } else {
                        i++;
                    }
                }
            }
        }

        /** Returns {@code $tag$} starting at {@code i}, or null ({@code $1} params are not tags). */
        private static String dollarTagAt(String line, int i) {
            int j = i + 1;
            while (j < line.length()) {
                char c = line.charAt(j);
                if (c == '$') {
                    return line.substring(i, j + 1);
                }
                boolean valid = Character.isLetter(c) || c == '_' || (j > i + 1 && Character.isDigit(c));
                if (!valid) {
                    return null;
                }
                j++;
            }
            return null;
        }
    }
}
