package com.fungle.brume.scan;

import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps a column name + SQL data type to a suggested anonymization {@link Strategy}
 * and {@link SemanticType}, so {@code brume scan} can pre-fill a usable
 * {@code brume.yml} without the operator having to label every column by hand.
 *
 * <p>The mapper is a pure function of the column metadata — no DB access, no Spring —
 * so it can be unit-tested in isolation. All heuristics live here as an ordered list
 * of patterns ({@link PatternRule}). The first substring match wins.
 *
 * <p>Scope split with the existing detectors is deliberate :
 * <ul>
 *   <li>{@link com.fungle.brume.plan.PiiDetector} and
 *       {@link com.fungle.brume.audit.QuasiIdDetector} <em>warn</em> at plan / audit time
 *       about uncovered columns.</li>
 *   <li>This mapper <em>proposes</em> a concrete rule, used at scan time to generate
 *       config. Deliberately more permissive on data types (we propose for any type,
 *       not just text — e.g. a column named {@code zip_code INT} still gets a MASK rule).</li>
 * </ul>
 *
 * <p>Design decisions for the pattern list recorded in ADR scan-command.
 */
public final class ColumnStrategyMapper {

    /**
     * One suggestion for a column, carrying enough information to render both the YAML
     * output and the trailing provenance comment ({@code # detected via pattern 'xxx'}).
     *
     * @param strategy       strategy to apply
     * @param semanticType   semantic type if the strategy needs one (FAKE/MASK), else {@code null}
     * @param matchedPattern the human-readable origin of the suggestion — pattern substring,
     *                       {@code "primary key (numeric)"}, {@code "primary key (uuid)"}
     */
    public record Suggestion(Strategy strategy, SemanticType semanticType, String matchedPattern) {
    }

    private record PatternRule(String pattern, Strategy strategy, SemanticType type) {
    }

    private static final List<PatternRule> RULES = List.of(
            // Identity
            new PatternRule("firstname",   Strategy.FAKE, SemanticType.FIRST_NAME),
            new PatternRule("first_name",  Strategy.FAKE, SemanticType.FIRST_NAME),
            new PatternRule("prenom",      Strategy.FAKE, SemanticType.FIRST_NAME),
            new PatternRule("lastname",    Strategy.FAKE, SemanticType.LAST_NAME),
            new PatternRule("last_name",   Strategy.FAKE, SemanticType.LAST_NAME),
            new PatternRule("fullname",    Strategy.FAKE, SemanticType.LAST_NAME),
            // Contact
            new PatternRule("email",       Strategy.FAKE, SemanticType.EMAIL),
            new PatternRule("mail",        Strategy.FAKE, SemanticType.EMAIL),
            new PatternRule("phone",       Strategy.MASK, SemanticType.PHONE),
            new PatternRule("mobile",      Strategy.MASK, SemanticType.PHONE),
            new PatternRule("fax",         Strategy.MASK, SemanticType.PHONE),
            new PatternRule("tel",         Strategy.MASK, SemanticType.PHONE),
            // Technical PII (ordered before "address" so ip_address wins the substring race)
            new PatternRule("ip_address",  Strategy.MASK, SemanticType.IP_ADDRESS),
            new PatternRule("ip_addr",     Strategy.MASK, SemanticType.IP_ADDRESS),
            new PatternRule("ipv4",        Strategy.MASK, SemanticType.IP_ADDRESS),
            new PatternRule("ipv6",        Strategy.MASK, SemanticType.IP_ADDRESS),
            // Address
            new PatternRule("address",     Strategy.FAKE, SemanticType.ADDRESS),
            new PatternRule("adresse",     Strategy.FAKE, SemanticType.ADDRESS),
            new PatternRule("street",      Strategy.FAKE, SemanticType.ADDRESS),
            new PatternRule("rue",         Strategy.FAKE, SemanticType.ADDRESS),
            new PatternRule("city",        Strategy.MASK, SemanticType.ADDRESS),
            new PatternRule("ville",       Strategy.MASK, SemanticType.ADDRESS),
            new PatternRule("zip",         Strategy.MASK, SemanticType.ADDRESS),
            new PatternRule("postal",      Strategy.MASK, SemanticType.ADDRESS),
            // Financial
            new PatternRule("iban",        Strategy.HASH, SemanticType.IBAN),
            new PatternRule("bic",         Strategy.HASH, SemanticType.IBAN),
            new PatternRule("card",        Strategy.HASH, null),
            new PatternRule("pan",         Strategy.HASH, null),
            new PatternRule("credit",      Strategy.HASH, null),
            // Credentials
            new PatternRule("password",    Strategy.HASH, null),
            new PatternRule("passwd",      Strategy.HASH, null),
            new PatternRule("api_key",     Strategy.HASH, null),
            new PatternRule("token",       Strategy.HASH, null),
            new PatternRule("session",     Strategy.HASH, null),
            // Sensitive personal data
            new PatternRule("ssn",         Strategy.HASH, null),
            new PatternRule("nir",         Strategy.HASH, null),
            new PatternRule("national_id", Strategy.HASH, null),
            new PatternRule("birthday",    Strategy.NULLIFY, null),
            new PatternRule("birth",       Strategy.NULLIFY, null),
            new PatternRule("dob",         Strategy.NULLIFY, null),
            new PatternRule("gender",      Strategy.NULLIFY, null),
            new PatternRule("sexe",        Strategy.NULLIFY, null),
            // Generic "name" — kept last : narrower identity patterns above win first
            new PatternRule("name",        Strategy.FAKE, SemanticType.LAST_NAME),
            new PatternRule("nom",         Strategy.FAKE, SemanticType.LAST_NAME)
    );

    private ColumnStrategyMapper() {
    }

    /**
     * Returns a suggested strategy for the given column, if any pattern matches.
     *
     * @param columnName the column name as it appears in the schema
     * @return the suggestion, or {@link Optional#empty()} if no pattern matches
     */
    public static Optional<Suggestion> suggestForColumn(String columnName) {
        if (columnName == null || columnName.isBlank()) return Optional.empty();
        String lower = columnName.toLowerCase(Locale.ROOT);
        for (PatternRule rule : RULES) {
            if (lower.contains(rule.pattern())) {
                return Optional.of(new Suggestion(rule.strategy(), rule.type(), rule.pattern()));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns a suggestion for a primary-key column based on its SQL data type :
     * {@code uuid} → {@link Strategy#FPE_UUID}, any other type → {@link Strategy#FPE_ID}
     * (which fits numeric PKs and is the common case). Foreign keys are left alone here
     * and will be filled in later by {@code FkStrategyPropagator} at run time.
     *
     * @param dataType the PostgreSQL data type as reported by {@code information_schema}
     * @return the suggestion for this primary-key column
     */
    public static Suggestion suggestForPrimaryKey(String dataType) {
        boolean uuid = dataType != null && dataType.toLowerCase(Locale.ROOT).contains("uuid");
        return uuid
                ? new Suggestion(Strategy.FPE_UUID, null, "primary key (uuid)")
                : new Suggestion(Strategy.FPE_ID, null, "primary key (numeric)");
    }
}
