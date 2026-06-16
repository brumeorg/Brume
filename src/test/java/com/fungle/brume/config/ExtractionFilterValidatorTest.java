package com.fungle.brume.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExtractionFilterValidator} — covers the 5 rules of T01 / ADR-0017.
 */
class ExtractionFilterValidatorTest {

    @Nested
    @DisplayName("Accepted filters")
    class Accepted {

        @Test
        @DisplayName("null filter is accepted (means: no WHERE clause)")
        void nullFilter() {
            assertThatCode(() -> ExtractionFilterValidator.validate("orders", null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("empty / blank filter is accepted")
        void blankFilter() {
            assertThatCode(() -> ExtractionFilterValidator.validate("orders", "   "))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("date comparison with single-quoted literal")
        void dateLiteral() {
            assertThatCode(() ->
                    ExtractionFilterValidator.validate("orders", "created_at >= '2025-01-01'"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("multi-AND with numeric and string literals")
        void multiAnd() {
            assertThatCode(() ->
                    ExtractionFilterValidator.validate("orders",
                            "total_amount > 0 AND status = 'paid'"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("IN with subquery (SELECT inside is allowed — only DML/DDL keywords forbidden)")
        void inSubquery() {
            assertThatCode(() ->
                    ExtractionFilterValidator.validate("order_items",
                            "order_id IN (SELECT id FROM test_brume.orders WHERE created_at >= '2025-01-01')"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("semicolon inside single-quoted string literal is allowed")
        void semicolonInsideStringLiteral() {
            assertThatCode(() ->
                    ExtractionFilterValidator.validate("users",
                            "name IN ('alice', 'bob;charlie')"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("escaped doubled quote inside literal does not toggle string state")
        void escapedDoubledQuote() {
            assertThatCode(() ->
                    ExtractionFilterValidator.validate("users",
                            "name = 'O''Brien;Jr'"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("filter with 'select' as substring of a column name is allowed (word-boundary match)")
        void wordBoundaryAllowsSubstring() {
            assertThatCode(() ->
                    ExtractionFilterValidator.validate("items", "preselected = true"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Rejected filters")
    class Rejected {

        @Test
        @DisplayName("semicolon outside string literal — classic stacked-statement injection")
        void stackedStatement() {
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", "1=1; DROP SCHEMA test_brume CASCADE"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("orders")
                    .hasMessageContaining("';'");
        }

        @Test
        @DisplayName("line comment marker '--'")
        void lineComment() {
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", "id > 0 -- bypass"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("comment marker");
        }

        @Test
        @DisplayName("block comment open marker")
        void blockCommentOpen() {
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", "id /* bla */ > 0"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("comment marker");
        }

        @Test
        @DisplayName("forbidden keyword DROP")
        void forbiddenKeywordDrop() {
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", "id IN (DROP TABLE x)"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("DROP");
        }

        @Test
        @DisplayName("forbidden keyword INSERT (case-insensitive)")
        void forbiddenKeywordInsertCaseInsensitive() {
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", "x = 1 OR insert INTO foo VALUES (1)"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("INSERT");
        }

        @Test
        @DisplayName("forbidden keyword DELETE")
        void forbiddenKeywordDelete() {
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", "1=1 OR DELETE FROM x WHERE 1=1"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("DELETE");
        }

        @Test
        @DisplayName("filter exceeding 1000 characters")
        void tooLong() {
            String filter = "id = " + "1234567890".repeat(101);  // ~1010 chars
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", filter))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("maximum length");
        }

        @Test
        @DisplayName("filter containing a NUL byte")
        void nulByte() {
            String filter = "id = 1" + '\0' + " OR 1=1";
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", filter))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("NUL byte");
        }

        @Test
        @DisplayName("error message includes a snippet of the offending filter")
        void errorMessageIncludesSnippet() {
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", "id > 0; DROP DB"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("<<id > 0; DROP DB>>");
        }

        @Test
        @DisplayName("error message snippet is truncated above 80 chars")
        void errorMessageSnippetTruncation() {
            String longFilter = "id = '" + "x".repeat(120) + "'; DROP X";
            assertThatThrownBy(() ->
                    ExtractionFilterValidator.validate("orders", longFilter))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("...");
        }
    }
}
