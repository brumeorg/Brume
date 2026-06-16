package com.fungle.brume.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlIdentifiersTest {

    @Test
    void validate_shouldAcceptValidIdentifiers() {
        assertDoesNotThrow(() -> SqlIdentifiers.validate("users"));
        assertDoesNotThrow(() -> SqlIdentifiers.validate("order_items"));
        assertDoesNotThrow(() -> SqlIdentifiers.validate("_audit"));
        assertDoesNotThrow(() -> SqlIdentifiers.validate("t1"));
        assertDoesNotThrow(() -> SqlIdentifiers.validate("My$Table"));
        assertDoesNotThrow(() -> SqlIdentifiers.validate("a" + "b".repeat(61))); // 62 chars
    }

    @Test
    void validate_shouldRejectNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate(null)
        );
        assertTrue(ex.getMessage().contains("cannot be null"));
    }

    @Test
    void validate_shouldRejectEmpty() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("")
        );
        assertTrue(ex.getMessage().contains("cannot be empty"));
    }

    @Test
    void validate_shouldRejectStartingWithDigit() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("1users")
        );
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
    }

    @Test
    void validate_shouldRejectDash() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("users-table")
        );
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
    }

    @Test
    void validate_shouldRejectSemicolon() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("users; drop")
        );
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
    }

    @Test
    void validate_shouldRejectTooLong() {
        String tooLong = "a" + "b".repeat(63); // 64 chars
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate(tooLong)
        );
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
    }

    @Test
    void validate_shouldRejectDoubleQuote() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("users\"")
        );
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
    }

    @Test
    void validate_shouldRejectSpace() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("my schema")
        );
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
    }

    @Test
    void validate_shouldRejectNonASCII() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("é")
        );
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
    }

    @Test
    void quote_shouldQuoteValidIdentifier() {
        assertEquals("\"users\"", SqlIdentifiers.quote("users"));
        assertEquals("\"My$Table\"", SqlIdentifiers.quote("My$Table"));
    }

    @Test
    void quote_shouldThrowForInvalidIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("invalid-name"));
    }

    @Test
    void quoteQualified_shouldQuoteBothParts() {
        assertEquals("\"public\".\"users\"", SqlIdentifiers.quoteQualified("public", "users"));
        assertEquals("\"test_schema\".\"order_items\"", SqlIdentifiers.quoteQualified("test_schema", "order_items"));
    }

    @Test
    void quoteQualified_shouldThrowIfEitherInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifiers.quoteQualified("invalid-schema", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifiers.quoteQualified("schema", "invalid-table"));
    }
}

