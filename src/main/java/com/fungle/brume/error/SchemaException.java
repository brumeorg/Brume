package com.fungle.brume.error;

/**
 * Schema replication or analysis failed: {@code pg_dump} subprocess errored / timed out,
 * target schema cannot be created, source schema is missing required objects, or DDL
 * application threw. Maps to exit code 4.
 */
public class SchemaException extends BrumeException {

    public SchemaException(BrumeErrorCode code, String message, String suggestion, Throwable cause) {
        super(code, message, suggestion, cause);
    }

    public SchemaException(BrumeErrorCode code, String message, String suggestion) {
        super(code, message, suggestion, null);
    }

    public SchemaException(BrumeErrorCode code, String message, Throwable cause) {
        super(code, message, null, cause);
    }

    public SchemaException(BrumeErrorCode code, String message) {
        super(code, message, null, null);
    }
}
