package com.fungle.brume.util;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.BrumeErrorCode;
import org.postgresql.Driver;

import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates and parses PostgreSQL JDBC URLs read from operator configuration
 * (audit § A5, ADR-0021).
 *
 * <p>Two distinct issues motivate this validator :
 * <ol>
 *   <li>The historical {@code SchemaReplicator.parseJdbcUrl} was a naive
 *       {@code String.split} that broke on query parameters — a URL like
 *       {@code jdbc:postgresql://host/mydb?sslmode=require} produced
 *       {@code database = "mydb?sslmode=require"} and was forwarded to
 *       {@code pg_dump -d mydb?sslmode=require}, silently connecting to a
 *       wrong (or non-existent) database.</li>
 *   <li>{@code DataSourceConfig} forwarded the raw URL to HikariCP. pgJDBC
 *       supports parameters that load arbitrary classes
 *       ({@code socketFactory}, {@code sslfactory}, {@code socketFactoryArg},
 *       {@code sslfactoryarg}, {@code sslpasswordcallback},
 *       {@code sslhostnameverifier}) or write to arbitrary files
 *       ({@code loggerFile}, {@code loggerLevel}). Each is a known pgJDBC
 *       RCE / file-write vector when the URL comes from untrusted templating.</li>
 * </ol>
 *
 * <p>Mitigation : delegate URL parsing to {@link Driver#parseURL(String, Properties)}
 * (the official pgJDBC parser, public API verified) and reject any parameter
 * not present in {@link #ALLOWED_PARAMS}. The allowlist is small and curated —
 * a new dangerous parameter introduced by a future pgJDBC version is rejected
 * by default until explicitly added here.
 */
public final class JdbcUrlValidator {

    /** Required prefix for a PostgreSQL JDBC URL. */
    private static final String PG_PREFIX = "jdbc:postgresql:";

    private static final String EXPECTED_FORM_HINT =
            "Expected form: 'jdbc:postgresql://host:port/dbname' with optional query parameters "
                    + "from the curated allowlist (ssl*, applicationName, *Timeout, currentSchema, "
                    + "options, targetServerType, readOnly, defaultRowFetchSize, "
                    + "prepareThreshold, reWriteBatchedInserts, loadBalanceHosts, "
                    + "hostRecheckSeconds, binaryTransfer).";

    /**
     * Curated allowlist of pgJDBC connection parameters. Adding a parameter to
     * this list is a deliberate decision and should be reviewed for security
     * implications (no class-loading, no file-writing).
     */
    private static final Set<String> ALLOWED_PARAMS = Set.of(
            // SSL
            "ssl", "sslmode", "sslrootcert", "sslcert", "sslkey", "sslcrl",
            // Application identity
            "applicationName", "ApplicationName",
            // Timeouts
            "connectTimeout", "socketTimeout", "loginTimeout", "tcpKeepAlive",
            // Schema / runtime
            "currentSchema", "options",
            // Auth + topology
            "gssEncMode", "targetServerType",
            // Connection behavior
            "readOnly", "defaultRowFetchSize",
            // Protocol tuning
            "prepareThreshold", "assumeMinServerVersion",
            "reWriteBatchedInserts",
            // Load balancing
            "loadBalanceHosts", "hostRecheckSeconds",
            // Encoding
            "binaryTransfer"
    );

    /**
     * Keys that {@link Driver#parseURL} synthesizes from the URL structure
     * itself (host/port/database). Not user-supplied parameters and therefore
     * exempt from the allowlist check.
     */
    private static final Set<String> CANONICAL_KEYS = Set.of("PGHOST", "PGPORT", "PGDBNAME");

    /**
     * Parsed and validated representation of a PostgreSQL JDBC URL.
     *
     * @param host     the host name (defaults to {@code "localhost"} if absent)
     * @param port     the port number (defaults to {@code 5432} if absent)
     * @param database the bare database name (no query string)
     * @param sslMode  the value of the {@code sslmode} query parameter, or {@code null}
     *                 if not specified
     */
    public record ParsedJdbcUrl(String host, int port, String database, String sslMode) {
    }

    private JdbcUrlValidator() {
    }

    /**
     * Validates {@code rawUrl} as a PostgreSQL JDBC URL and returns its parsed
     * components. Throws {@link ConfigurationException} with an actionable
     * message if the URL is malformed or contains a parameter not in the
     * allowlist.
     *
     * @param rawUrl       the raw URL string read from configuration
     * @param propertyName the property name (e.g. {@code replication.source.url}) used in error messages
     * @return the parsed URL components
     */
    public static ParsedJdbcUrl validate(String rawUrl, String propertyName) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_URL_BLANK,
                    propertyName + " must not be blank",
                    "Set '" + propertyName + "' in application.yaml. " + EXPECTED_FORM_HINT);
        }
        if (!rawUrl.startsWith(PG_PREFIX)) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_URL_INVALID,
                    propertyName + " must start with '" + PG_PREFIX + "' (got: '" + rawUrl + "')",
                    EXPECTED_FORM_HINT);
        }

        Properties parsed;
        try {
            parsed = Driver.parseURL(rawUrl, null);
        } catch (RuntimeException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_URL_INVALID,
                    propertyName + "='" + rawUrl + "' is not a valid PostgreSQL JDBC URL: "
                            + e.getMessage(),
                    EXPECTED_FORM_HINT,
                    e);
        }
        if (parsed == null) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_URL_INVALID,
                    propertyName + "='" + rawUrl + "' is not a valid PostgreSQL JDBC URL "
                            + "(org.postgresql.Driver.parseURL returned null)",
                    EXPECTED_FORM_HINT);
        }

        TreeSet<String> rejected = new TreeSet<>();
        for (String key : parsed.stringPropertyNames()) {
            if (CANONICAL_KEYS.contains(key)) {
                continue;
            }
            if (!ALLOWED_PARAMS.contains(key)) {
                rejected.add(key);
            }
        }
        if (!rejected.isEmpty()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_URL_REJECTED_PARAM,
                    propertyName + "='" + rawUrl + "' contains rejected parameter(s): " + rejected,
                    "Brume only accepts a curated allowlist of pgJDBC parameters (audit § A5, "
                            + "ADR-0021) to block known class-loading and file-writing vectors "
                            + "(socketFactory, loggerFile, sslfactory, etc.). Allowed: "
                            + new TreeSet<>(ALLOWED_PARAMS) + ". If a missing parameter is needed "
                            + "for a legitimate use case, open an issue to request its addition.");
        }

        String host = parsed.getProperty("PGHOST", "localhost");
        int port;
        try {
            port = Integer.parseInt(parsed.getProperty("PGPORT", "5432"));
        } catch (NumberFormatException e) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_URL_INVALID,
                    propertyName + ": invalid port in URL '" + rawUrl + "'",
                    EXPECTED_FORM_HINT,
                    e);
        }
        String database = parsed.getProperty("PGDBNAME");
        if (database == null || database.isBlank()) {
            throw new ConfigurationException(
                    BrumeErrorCode.CONFIG_JDBC_URL_INVALID,
                    propertyName + "='" + rawUrl + "' has no database name",
                    EXPECTED_FORM_HINT);
        }
        String sslMode = parsed.getProperty("sslmode");
        return new ParsedJdbcUrl(host, port, database, sslMode);
    }
}
