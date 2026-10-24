package com.fungle.brume.util;

import java.nio.charset.StandardCharsets;

/**
 * Utility class for SQL identifier validation and quoting.
 * <p>
 * All schema, table, and column names used in dynamic SQL must pass through {@link #quote}
 * to prevent SQL injection and ensure correct handling of PostgreSQL identifiers.
 * </p>
 * <p>
 * The protection comes from the quoting, not from a character whitelist (#26c, ADR-0053) :
 * the identifier is wrapped in double quotes and every embedded double quote is doubled, so
 * no name can leave the quoted identifier. Accents, spaces, upper case, dashes are all legal
 * PostgreSQL names. Validation only rejects what PostgreSQL itself cannot hold :
 * <ul>
 *   <li>an empty name, or one containing the NUL character</li>
 *   <li>more than 63 bytes in UTF-8 (NAMEDATALEN - 1) : PostgreSQL would silently truncate
 *       it, and the truncated name could designate another object</li>
 * </ul>
 * </p>
 */
public final class SqlIdentifiers {

    /** PostgreSQL NAMEDATALEN - 1, in bytes. */
    static final int MAX_BYTES = 63;

    private SqlIdentifiers() {
        // Utility class — no instantiation
    }

    /**
     * Validates an SQL identifier : 1 to 63 bytes in UTF-8, no NUL character.
     *
     * @param ident the identifier to validate (must not be null)
     * @throws IllegalArgumentException if the identifier is null, empty, too long or contains NUL
     */
    public static void validate(String ident) {
        if (ident == null) {
            throw new IllegalArgumentException("SQL identifier cannot be null");
        }
        if (ident.isEmpty()) {
            throw new IllegalArgumentException("SQL identifier cannot be empty");
        }
        if (ident.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    String.format("Invalid SQL identifier '%s': contains a NUL character", ident.replace('\0', '?')));
        }
        int bytes = ident.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException(String.format(
                    "Invalid SQL identifier '%s': %d bytes in UTF-8, PostgreSQL keeps at most %d",
                    ident, bytes, MAX_BYTES));
        }
    }

    /**
     * Quotes a single SQL identifier with double quotes after validation ; embedded double
     * quotes are doubled.
     *
     * @param ident the identifier to quote
     * @return the quoted identifier (e.g., {@code "my_table"}, {@code "nom de famille"})
     * @throws IllegalArgumentException if the identifier is invalid
     */
    public static String quote(String ident) {
        validate(ident);
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /**
     * Quotes and joins a schema-qualified identifier (schema.table).
     *
     * @param schema the schema name
     * @param table  the table name
     * @return the qualified quoted identifier (e.g., {@code "public"."users"})
     * @throws IllegalArgumentException if either identifier is invalid
     */
    public static String quoteQualified(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }
}
