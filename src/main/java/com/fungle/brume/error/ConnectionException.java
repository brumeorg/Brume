package com.fungle.brume.error;

/**
 * Source or target database is unreachable, refused the connection, or timed out at
 * the Hikari pool initialization stage. Maps to exit code 2.
 *
 * <p>Distinguished from {@link com.fungle.brume.config.ConfigurationException} on purpose:
 * a malformed JDBC URL is a config error, but a syntactically valid URL whose host is
 * down is a connection error. Operators automating Brume in CI care about the
 * difference (a connection error is usually transient, a config error never is).
 */
public class ConnectionException extends BrumeException {

    public ConnectionException(BrumeErrorCode code, String message, String suggestion, Throwable cause) {
        super(code, message, suggestion, cause);
    }

    public ConnectionException(BrumeErrorCode code, String message, String suggestion) {
        super(code, message, suggestion, null);
    }

    public ConnectionException(BrumeErrorCode code, String message, Throwable cause) {
        super(code, message, null, cause);
    }
}
