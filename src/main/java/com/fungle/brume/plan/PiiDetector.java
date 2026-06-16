package com.fungle.brume.plan;

import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.report.PiiWarning;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spring component that detects probable PII (Personally Identifiable Information) columns
 * that are not covered by any anonymization rule in the anonymization config.
 *
 * <p>Detection is heuristic: column names are matched against a curated list of PII-related
 * substrings (case-insensitive). Only text-like data types are considered; numeric columns
 * are excluded even if their name matches a pattern (e.g. {@code zip_code INTEGER}).
 *
 * <p>Results should be reviewed by the operator who can then either add anonymization rules
 * for genuinely sensitive columns, or add a {@code KEEP} rule to suppress future warnings.
 */
@Component
public class PiiDetector {

    /**
     * Ordered list of PII patterns matched as case-insensitive substrings of column names.
     * The first matching pattern is stored in {@link PiiWarning#matchedPattern()}.
     */
    private static final List<String> PII_PATTERNS = List.of(
            // Identity
            "name", "nom", "prenom", "firstname", "lastname", "fullname",
            // Contact
            "email", "mail", "phone", "tel", "mobile", "fax",
            // Address
            "address", "adresse", "street", "rue", "city", "ville", "zip", "postal",
            // Sensitive personal data
            "birthday", "birth", "dob", "gender", "sexe", "ssn", "nir", "national_id",
            // Financial
            "iban", "bic", "card", "pan", "credit",
            // Technical PII
            "ip_address", "ip_addr", "ipv4", "ipv6", "session", "token", "api_key", "password", "passwd"
    );

    /**
     * Substrings that identify a text-like PostgreSQL data type.
     * Column types whose lower-cased name contains any of these are considered text-like.
     */
    private static final Set<String> TEXT_TYPE_FRAGMENTS = Set.of(
            "char", "text", "varchar", "name", "citext", "bpchar"
    );

    /**
     * Creates a new {@code PiiDetector} with no external dependencies.
     */
    public PiiDetector() {
        // no dependencies
    }

    /**
     * Detects columns in the given schema tables that match known PII patterns
     * and are NOT covered by any anonymization rule in the config.
     *
     * @param schema     the source database schema (all tables and columns)
     * @param config     the anonymization config (declares which columns have rules)
     * @param schemaName the PostgreSQL schema name (e.g. {@code "test_brume"})
     * @return list of PII warnings, one per suspect uncovered column; never {@code null}
     */
    public List<PiiWarning> detect(DatabaseSchema schema, AnonymizerConfig config,
                                   String schemaName) {
        Set<String> coveredColumns = buildCoveredColumnsSet(config);
        List<PiiWarning> warnings = new ArrayList<>();

        for (TableMetadata tableMeta : schema.tables().values()) {
            String tableName = tableMeta.name();
            if (tableMeta.columns() == null) continue;

            for (ColumnMetadata col : tableMeta.columns()) {
                String columnName = col.name();
                String dataType = col.dataType();

                // Exclude numeric and non-text types (e.g. zip_code INTEGER)
                if (!isTextType(dataType)) continue;

                // Skip columns that already have an anonymization rule
                if (coveredColumns.contains(tableName + "." + columnName)) continue;

                // Match column name against PII patterns (case-insensitive substring)
                String matchedPattern = findMatchingPattern(columnName.toLowerCase(Locale.ROOT));
                if (matchedPattern != null) {
                    warnings.add(new PiiWarning(tableName, columnName, matchedPattern));
                }
            }
        }

        return warnings;
    }

    /**
     * Builds a set of {@code "tableName.columnName"} keys for all columns that have
     * at least one anonymization rule declared in the config.
     *
     * @param config the anonymization config
     * @return set of covered column keys
     */
    private Set<String> buildCoveredColumnsSet(AnonymizerConfig config) {
        if (config.anonymization() == null || config.anonymization().tables() == null) {
            return Set.of();
        }
        return config.anonymization().tables().stream()
                .flatMap(tableConfig -> {
                    if (tableConfig.columns() == null) return Stream.empty();
                    return tableConfig.columns().stream()
                            .map(colConfig -> tableConfig.table() + "." + colConfig.name());
                })
                .collect(Collectors.toSet());
    }

    /**
     * Returns {@code true} if the given SQL data type is text-like (can contain PII strings).
     * Numeric-only types ({@code integer}, {@code bigint}, {@code boolean}, etc.) return {@code false}.
     *
     * @param dataType the PostgreSQL column data type (e.g. {@code "character varying"})
     * @return {@code true} if the type can hold textual PII
     */
    private boolean isTextType(String dataType) {
        if (dataType == null) return false;
        String lower = dataType.toLowerCase(Locale.ROOT);
        return TEXT_TYPE_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    /**
     * Returns the first PII pattern that is a substring of the given lower-cased column name,
     * or {@code null} if none match.
     *
     * @param lowerColumnName the column name already converted to lower case
     * @return the first matching pattern string, or {@code null}
     */
    private String findMatchingPattern(String lowerColumnName) {
        for (String pattern : PII_PATTERNS) {
            if (lowerColumnName.contains(pattern)) {
                return pattern;
            }
        }
        return null;
    }
}


