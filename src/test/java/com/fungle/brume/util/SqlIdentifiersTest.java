package com.fungle.brume.util;

import org.junit.jupiter.api.DisplayName;
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
        assertDoesNotThrow(() -> SqlIdentifiers.validate("a" + "b".repeat(62))); // 63 bytes
    }

    @Test
    @DisplayName("#26c — every legal PostgreSQL name is accepted : accents, spaces, dashes, leading digit, quotes")
    void validate_shouldAcceptEveryLegalPostgresName() {
        for (String name : new String[]{"prénom", "téléphone", "nom de famille", "zip code",
                "users-table", "1users", "Account", "a\"b", "😀"}) {
            assertDoesNotThrow(() -> SqlIdentifiers.validate(name), name);
        }
        assertDoesNotThrow(() -> SqlIdentifiers.validate("é".repeat(31) + "a")); // 63 bytes
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
    void validate_shouldRejectNulCharacter() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("users\0")
        );
        assertTrue(ex.getMessage().contains("NUL character"));
    }

    @Test
    @DisplayName("#26c — the 63-byte limit is counted in UTF-8 bytes, not in characters")
    void validate_shouldRejectMoreThan63Bytes() {
        IllegalArgumentException ascii = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("a" + "b".repeat(63)) // 64 bytes
        );
        assertTrue(ascii.getMessage().contains("64 bytes"));
        IllegalArgumentException accents = assertThrows(
                IllegalArgumentException.class,
                () -> SqlIdentifiers.validate("é".repeat(32)) // 32 characters, 64 bytes
        );
        assertTrue(accents.getMessage().contains("64 bytes"));
    }

    @Test
    void quote_shouldQuoteValidIdentifier() {
        assertEquals("\"users\"", SqlIdentifiers.quote("users"));
        assertEquals("\"My$Table\"", SqlIdentifiers.quote("My$Table"));
        assertEquals("\"nom de famille\"", SqlIdentifiers.quote("nom de famille"));
    }

    @Test
    @DisplayName("#26c — embedded double quotes are doubled : a name can never leave its quoted identifier")
    void quote_shouldDoubleEmbeddedQuotes() {
        assertEquals("\"a\"\"b\"", SqlIdentifiers.quote("a\"b"));
        assertEquals("\"x\"\"; DROP TABLE t; --\"", SqlIdentifiers.quote("x\"; DROP TABLE t; --"));
    }

    @Test
    void quote_shouldThrowForInvalidIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("invalid\0name"));
    }

    @Test
    void quoteQualified_shouldQuoteBothParts() {
        assertEquals("\"public\".\"users\"", SqlIdentifiers.quoteQualified("public", "users"));
        assertEquals("\"test_schema\".\"order_items\"", SqlIdentifiers.quoteQualified("test_schema", "order_items"));
    }

    @Test
    void quoteQualified_shouldThrowIfEitherInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifiers.quoteQualified("", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifiers.quoteQualified("schema", "x".repeat(64)));
    }
}
