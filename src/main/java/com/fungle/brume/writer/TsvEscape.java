package com.fungle.brume.writer;

import java.util.List;

/**
 * PostgreSQL {@code COPY FROM stdin} TEXT format escaping — appends an escaped
 * representation of a Java value into a caller-owned {@link StringBuilder}, with
 * zero string allocation on the no-escape path.
 *
 * <p>Conforms to the PostgreSQL TEXT format specification (see PostgreSQL docs,
 * {@code COPY} command, "Text Format" section): backslash, newline, carriage
 * return and tab are escaped with a leading backslash; SQL {@code NULL} is
 * encoded as the literal {@code \N}.
 *
 * <p>Allocation profile: for values whose {@code toString()} contains no escape-
 * required character (the dominant case for IDs, timestamps, short ASCII), the
 * implementation issues a single bulk {@link StringBuilder#append(CharSequence, int, int)}
 * and allocates nothing beyond the unavoidable {@code toString()}. For values
 * containing escape characters, runs of safe characters are still appended in
 * bulk between escapes.
 *
 * <p>Caller is responsible for the {@link StringBuilder} lifecycle. Typical usage
 * is to reuse a per-thread / per-chunk buffer via {@code sb.setLength(0)} between
 * rows, then flush to an {@code OutputStream}.
 *
 * <h2>Limitations</h2>
 * <p>{@code byte[]} (PostgreSQL {@code bytea}) is not supported in V1: the
 * default Java {@code Object.toString()} on a byte array (e.g. {@code [B@1a2b3c4d})
 * would be silently inserted into the target. The method fails fast with
 * {@link UnsupportedOperationException} rather than risk silent data corruption.
 * Tracked as PROGRESS item #5b (T-B7c).
 */
public final class TsvEscape {

    private static final String NULL_MARKER = "\\N";
    private static final String ESCAPED_BACKSLASH = "\\\\";
    private static final String ESCAPED_NEWLINE = "\\n";
    private static final String ESCAPED_CR = "\\r";
    private static final String ESCAPED_TAB = "\\t";

    private TsvEscape() {
        // utility class — no instances
    }

    /**
     * Appends the PostgreSQL TEXT-format escaped representation of {@code value}
     * to {@code out}.
     *
     * <p>Encoding rules:
     * <ul>
     *   <li>{@code null} → {@code \N}</li>
     *   <li>{@code byte[]} → throws {@link UnsupportedOperationException}
     *       (see class-level limitations).</li>
     *   <li>Otherwise: {@code value.toString()} with backslash, newline, carriage
     *       return and tab each replaced by their two-character backslash escape.</li>
     * </ul>
     *
     * @param out   the target buffer; must not be {@code null}
     * @param value the value to escape; may be {@code null}
     * @throws UnsupportedOperationException if {@code value} is a {@code byte[]}
     */
    public static void escape(StringBuilder out, Object value) {
        if (value == null) {
            out.append(NULL_MARKER);
            return;
        }
        if (value instanceof byte[]) {
            throw new UnsupportedOperationException(
                    "TsvEscape does not support byte[] (PostgreSQL bytea) in V1. "
                            + "Tracked as PROGRESS item #5b (T-B7c). Configure the column with a "
                            + "different strategy or wait for bytea support to land alongside ticket #5 (B1).");
        }

        String s = value.toString();
        int len = s.length();
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            String replacement = switch (c) {
                case '\\' -> ESCAPED_BACKSLASH;
                case '\n' -> ESCAPED_NEWLINE;
                case '\r' -> ESCAPED_CR;
                case '\t' -> ESCAPED_TAB;
                default -> null;
            };
            if (replacement != null) {
                if (i > start) {
                    out.append(s, start, i);
                }
                out.append(replacement);
                start = i + 1;
            }
        }
        if (start < len) {
            out.append(s, start, len);
        }
    }

    /**
     * Appends a full TSV row (values separated by tab, terminated by newline) to
     * {@code out}. Convenience over a manual loop calling {@link #escape(StringBuilder, Object)}
     * — used by both {@code SqlFileSink} and {@code JdbcSink} (COPY mode) to share the
     * same separator/terminator semantics.
     *
     * @param out    the target buffer; must not be {@code null}
     * @param values one value per column, in column order; may contain {@code null} entries
     */
    public static void appendRow(StringBuilder out, List<?> values) {
        boolean first = true;
        for (Object v : values) {
            if (!first) {
                out.append('\t');
            }
            escape(out, v);
            first = false;
        }
        out.append('\n');
    }
}
