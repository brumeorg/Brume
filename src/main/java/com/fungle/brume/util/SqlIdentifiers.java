package com.fungle.brume.util;

import java.util.regex.Pattern;

/**
 * Utility class for SQL identifier validation and quoting.
 * <p>
 * All schema, table, and column names used in dynamic SQL must pass through this class
 * to prevent SQL injection and ensure correct handling of PostgreSQL identifiers.
 * </p>
 * <p>
 * V1 validation rule: {@code ^[A-Za-z_][A-Za-z0-9_$]{0,62}$}
 * <ul>
 *   <li>First character: letter or underscore</li>
 *   <li>Subsequent characters: letters, digits, underscores, or dollar signs</li>
 *   <li>Maximum length: 63 characters (PostgreSQL NAMEDATALEN-1 limit)</li>
 * </ul>
 * </p>
 */
public final class SqlIdentifiers {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,62}$");

    private SqlIdentifiers() {
        // Utility class — no instantiation
    }

    /**
     * Validates an SQL identifier against the V1 rules.
     *
     * @param ident the identifier to validate (must not be null)
     * @throws IllegalArgumentException if the identifier is null, empty, or does not match the validation pattern
     */
    public static void validate(String ident) {
        if (ident == null) {
            throw new IllegalArgumentException("SQL identifier cannot be null");
        }
        if (ident.isEmpty()) {
            throw new IllegalArgumentException("SQL identifier cannot be empty");
        }
        if (!VALID.matcher(ident).matches()) {
            throw new IllegalArgumentException(
                    String.format("Invalid SQL identifier '%s': must match pattern ^[A-Za-z_][A-Za-z0-9_$]{0,62}$", ident)
            );
        }
    }

    /**
     * Quotes a single SQL identifier with double quotes after validation.
     *
     * @param ident the identifier to quote
     * @return the quoted identifier (e.g., {@code "my_table"})
     * @throws IllegalArgumentException if the identifier is invalid
     */
    public static String quote(String ident) {
        validate(ident);
        return "\"" + ident + "\"";
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

