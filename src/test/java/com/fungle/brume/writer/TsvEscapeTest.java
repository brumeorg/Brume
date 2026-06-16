package com.fungle.brume.writer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TsvEscape} — covers PostgreSQL TEXT-format escaping rules
 * (NULL, special chars, common types, buffer lifecycle) without any database.
 */
class TsvEscapeTest {

    private final StringBuilder out = new StringBuilder();

    // -----------------------------------------------------------------------
    // 1. NULL & basic ASCII
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("NULL and basics")
    class NullAndBasics {

        @Test
        @DisplayName("null → \\N")
        void escapesNullAsBackslashN() {
            TsvEscape.escape(out, null);
            assertThat(out.toString()).isEqualTo("\\N");
        }

        @Test
        @DisplayName("empty string → empty output")
        void escapesEmptyStringAsEmpty() {
            TsvEscape.escape(out, "");
            assertThat(out.toString()).isEmpty();
        }

        @Test
        @DisplayName("plain ASCII passes through unchanged")
        void plainAsciiPassesThrough() {
            TsvEscape.escape(out, "Hello, World!");
            assertThat(out.toString()).isEqualTo("Hello, World!");
        }

        @Test
        @DisplayName("UTF-8 multi-byte (accents) passes through unchanged")
        void utf8MultiBytePassesThrough() {
            TsvEscape.escape(out, "café — éàïøù 日本語");
            assertThat(out.toString()).isEqualTo("café — éàïøù 日本語");
        }
    }

    // -----------------------------------------------------------------------
    // 2. Individual special characters
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Individual special characters")
    class SpecialChars {

        @Test
        @DisplayName("backslash alone → \\\\")
        void escapesBackslash() {
            TsvEscape.escape(out, "\\");
            assertThat(out.toString()).isEqualTo("\\\\");
        }

        @Test
        @DisplayName("newline alone → \\n")
        void escapesNewline() {
            TsvEscape.escape(out, "\n");
            assertThat(out.toString()).isEqualTo("\\n");
        }

        @Test
        @DisplayName("carriage return alone → \\r")
        void escapesCarriageReturn() {
            TsvEscape.escape(out, "\r");
            assertThat(out.toString()).isEqualTo("\\r");
        }

        @Test
        @DisplayName("tab alone → \\t")
        void escapesTab() {
            TsvEscape.escape(out, "\t");
            assertThat(out.toString()).isEqualTo("\\t");
        }
    }

    // -----------------------------------------------------------------------
    // 3. Combinations and positions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Combinations and positions")
    class Combinations {

        @Test
        @DisplayName("escape at the start is preserved")
        void escapeAtStart() {
            TsvEscape.escape(out, "\tfoo");
            assertThat(out.toString()).isEqualTo("\\tfoo");
        }

        @Test
        @DisplayName("escape at the end is preserved")
        void escapeAtEnd() {
            TsvEscape.escape(out, "foo\t");
            assertThat(out.toString()).isEqualTo("foo\\t");
        }

        @Test
        @DisplayName("consecutive escapes (\\t\\t) → \\\\t\\\\t")
        void consecutiveEscapes() {
            TsvEscape.escape(out, "\t\t");
            assertThat(out.toString()).isEqualTo("\\t\\t");
        }

        @Test
        @DisplayName("mixed text and escapes preserves order")
        void mixedTextAndEscapes() {
            TsvEscape.escape(out, "a\tb\nc\\d");
            assertThat(out.toString()).isEqualTo("a\\tb\\nc\\\\d");
        }

        @Test
        @DisplayName("Windows CRLF → \\r\\n")
        void windowsCrlf() {
            TsvEscape.escape(out, "line1\r\nline2");
            assertThat(out.toString()).isEqualTo("line1\\r\\nline2");
        }
    }

    // -----------------------------------------------------------------------
    // 4. Non-String types via Object.toString()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Non-String types")
    class NonStringTypes {

        @Test
        @DisplayName("Long is rendered via toString")
        void longValue() {
            TsvEscape.escape(out, 42L);
            assertThat(out.toString()).isEqualTo("42");
        }

        @Test
        @DisplayName("BigDecimal is rendered via toString")
        void bigDecimalValue() {
            TsvEscape.escape(out, new BigDecimal("99.99"));
            assertThat(out.toString()).isEqualTo("99.99");
        }

        @Test
        @DisplayName("Boolean is rendered via toString")
        void booleanValue() {
            TsvEscape.escape(out, Boolean.TRUE);
            assertThat(out.toString()).isEqualTo("true");
            out.setLength(0);
            TsvEscape.escape(out, Boolean.FALSE);
            assertThat(out.toString()).isEqualTo("false");
        }

        @Test
        @DisplayName("java.sql.Timestamp is rendered via toString (ISO-like)")
        void timestampValue() {
            Timestamp ts = Timestamp.valueOf("2025-01-01 12:34:56");
            TsvEscape.escape(out, ts);
            assertThat(out.toString()).isEqualTo(ts.toString());
        }
    }

    // -----------------------------------------------------------------------
    // 5. StringBuilder lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("StringBuilder lifecycle")
    class BufferLifecycle {

        @Test
        @DisplayName("non-empty buffer is preserved; escape is appended")
        void preservesExistingContent() {
            out.append("PREFIX:");
            TsvEscape.escape(out, "x\ty");
            assertThat(out.toString()).isEqualTo("PREFIX:x\\ty");
        }

        @Test
        @DisplayName("setLength(0) between calls produces independent results")
        void setLengthZeroResetsBetweenCalls() {
            TsvEscape.escape(out, "first\trun");
            assertThat(out.toString()).isEqualTo("first\\trun");
            out.setLength(0);
            TsvEscape.escape(out, "second");
            assertThat(out.toString()).isEqualTo("second");
        }

        @Test
        @DisplayName("multiple escape calls without reset concatenate")
        void multipleCallsConcatenate() {
            TsvEscape.escape(out, "a");
            out.append('\t');
            TsvEscape.escape(out, "b\nc");
            assertThat(out.toString()).isEqualTo("a\tb\\nc");
        }
    }

    // -----------------------------------------------------------------------
    // 6. appendRow helper
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("appendRow helper")
    class AppendRowHelper {

        @Test
        @DisplayName("empty list emits a lone newline")
        void emptyListEmitsNewline() {
            TsvEscape.appendRow(out, Collections.emptyList());
            assertThat(out.toString()).isEqualTo("\n");
        }

        @Test
        @DisplayName("single value emits value + newline (no separator)")
        void singleValue() {
            TsvEscape.appendRow(out, List.of("alpha"));
            assertThat(out.toString()).isEqualTo("alpha\n");
        }

        @Test
        @DisplayName("multiple values are tab-separated and newline-terminated")
        void multipleValues() {
            TsvEscape.appendRow(out, List.of(1L, "alice", 99.99));
            assertThat(out.toString()).isEqualTo("1\talice\t99.99\n");
        }

        @Test
        @DisplayName("null values become \\N in the row")
        void nullValueInRow() {
            TsvEscape.appendRow(out, Arrays.asList(1L, null, "x"));
            assertThat(out.toString()).isEqualTo("1\t\\N\tx\n");
        }

        @Test
        @DisplayName("escape characters in values are still escaped")
        void escapeCharsInValues() {
            TsvEscape.appendRow(out, List.of("a\tb", "c\nd"));
            assertThat(out.toString()).isEqualTo("a\\tb\tc\\nd\n");
        }
    }

    // -----------------------------------------------------------------------
    // 7. byte[] fail-fast
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("byte[] fail-fast")
    class ByteArrayGuard {

        @Test
        @DisplayName("byte[] throws UnsupportedOperationException with explicit message")
        void byteArrayIsRejected() {
            byte[] bytes = {0x01, 0x02, 0x03};
            assertThatThrownBy(() -> TsvEscape.escape(out, bytes))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("bytea")
                    .hasMessageContaining("#5b");
        }

        @Test
        @DisplayName("empty byte[] also throws (no leakage of [B@hash)")
        void emptyByteArrayIsRejected() {
            byte[] empty = new byte[0];
            assertThatThrownBy(() -> TsvEscape.escape(out, empty))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
