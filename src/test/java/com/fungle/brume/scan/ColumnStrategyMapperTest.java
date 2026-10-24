package com.fungle.brume.scan;

import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.scan.ColumnStrategyMapper.Suggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pattern -> strategy mapping.
 *
 * <p>These lock in the Q3 decision table from ADR scan-command so a future contributor
 * cannot silently retune the heuristic without the regression showing up here. The
 * {@code dataType} parameter was added in #45a to close the false-positive class where
 * a column name containing a PII substring (e.g. {@code password}) matched a text-oriented
 * strategy (HASH) that could not possibly write back into a temporal column.
 */
class ColumnStrategyMapperTest {

    @Test
    void emailColumnMapsToFakeEmail() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("billing_email", "varchar");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.FAKE);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.EMAIL);
        assertThat(hit.get().matchedPattern()).isEqualTo("email");
    }

    @Test
    void ibanColumnMapsToHashIban() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("iban", "varchar");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.HASH);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.IBAN);
    }

    @Test
    void passwordColumnMapsToHashWithoutSemanticType() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("password_hash", "varchar");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.HASH);
        assertThat(hit.get().semanticType()).isNull();
    }

    @Test
    void birthdayColumnMapsToFakeDate() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("birthday", "date");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.FAKE);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.DATE);
    }

    @Test
    void dobColumnOnTimestampMapsToFakeDate() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("user_dob", "timestamp without time zone");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.FAKE);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.DATE);
    }

    @Test
    void ipAddressColumnMapsToMask() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("ip_address", "varchar");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.MASK);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.IP_ADDRESS);
    }

    @Test
    void firstNamePatternWinsOverGenericName() {
        // Guard : the generic "name" rule is listed last so narrower patterns take precedence.
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("first_name", "varchar");
        assertThat(hit).isPresent();
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.FIRST_NAME);
    }

    @Test
    void unknownColumnReturnsEmpty() {
        assertThat(ColumnStrategyMapper.suggestForColumn("quantity", "integer")).isEmpty();
        assertThat(ColumnStrategyMapper.suggestForColumn("total_amount", "numeric")).isEmpty();
    }

    @Test
    void nullOrBlankColumnReturnsEmpty() {
        assertThat(ColumnStrategyMapper.suggestForColumn(null, "varchar")).isEmpty();
        assertThat(ColumnStrategyMapper.suggestForColumn("", "varchar")).isEmpty();
        assertThat(ColumnStrategyMapper.suggestForColumn("   ", "varchar")).isEmpty();
    }

    @Test
    void uuidPrimaryKeyMapsToFpeUuid() {
        Suggestion s = ColumnStrategyMapper.suggestForPrimaryKey("uuid");
        assertThat(s.strategy()).isEqualTo(Strategy.FPE_UUID);
        assertThat(s.semanticType()).isNull();
        assertThat(s.matchedPattern()).isEqualTo("primary key (uuid)");
    }

    @Test
    void numericPrimaryKeyMapsToFpeId() {
        Suggestion s = ColumnStrategyMapper.suggestForPrimaryKey("bigint");
        assertThat(s.strategy()).isEqualTo(Strategy.FPE_ID);
        assertThat(s.matchedPattern()).isEqualTo("primary key (numeric)");
    }

    @Test
    void primaryKeyWithNullDataTypeFallsBackToFpeId() {
        Suggestion s = ColumnStrategyMapper.suggestForPrimaryKey(null);
        assertThat(s.strategy()).isEqualTo(Strategy.FPE_ID);
    }

    // ---------------------------------------------------------------------
    // Temporal type-filter regressions — #45a
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("timestamp column 'date_derniere_maj_password' no longer matches HASH (regression #45a)")
    void temporalColumnMatchingHashPatternIsSkipped() {
        // Before #45a : substring match on 'password' → HASH, which would try to write a
        // SHA-256 hex string into a timestamp column at execute time → type error.
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(
                "date_derniere_maj_password", "timestamp without time zone");
        assertThat(hit).isEmpty();
    }

    @Test
    void temporalColumnWithMaskPatternIsSkipped() {
        // phone-like substring in a date column name is nonsensical but could happen
        // (e.g. 'telephony_event_date'). HASH/MASK on a temporal → skip.
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(
                "telephony_event_date", "timestamptz");
        assertThat(hit).isEmpty();
    }

    @Test
    void temporalColumnWithNonDateFakePatternIsSkipped() {
        // 'first_name_date' matches 'first_name' (FAKE + FIRST_NAME) but writing a fake
        // first name string into a timestamp column fails — skip.
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(
                "first_name_date", "timestamp");
        assertThat(hit).isEmpty();
    }

    @Test
    void temporalColumnWithNullifyPatternStillMatches() {
        // NULLIFY always fits a nullable column, regardless of type family.
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(
                "last_gender_change", "date");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.NULLIFY);
    }

    @Test
    void nullDataTypeDisablesTemporalFiltering() {
        // Back-compat guard : callers that don't yet know the type should get the pre-#45a
        // behavior rather than silently losing suggestions.
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(
                "date_derniere_maj_password", null);
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.HASH);
    }

    // ---------------------------------------------------------------------
    // French column names — #126a (gaps revealed by the E2E scan fixture, ADR-0049 Q7)
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "{0} ({1}) → {2} {3}")
    @CsvSource(nullValues = "null", value = {
            "courriel,                varchar, FAKE, EMAIL",
            "portable,                varchar, MASK, PHONE",
            "adresse_ip,              varchar, MASK, IP_ADDRESS",
            "date_naissance,          date,    FAKE, DATE",
            "mot_de_passe,            varchar, HASH, null",
            "mdp_hash,                varchar, HASH, null",
            "numero_securite_sociale, varchar, HASH, null",
            "num_secu,                varchar, HASH, null"
    })
    @DisplayName("French PII column names missed before #126a are now detected (E2E scan fixture e2e_fr)")
    void frenchPiiColumnNamesAreDetected(String column, String dataType, Strategy strategy, SemanticType type) {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(column, dataType);
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(strategy);
        assertThat(hit.get().semanticType()).isEqualTo(type);
    }

    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource(nullValues = "null", value = {
            "Prénom,            FAKE, FIRST_NAME",
            "prénom,            FAKE, FIRST_NAME",
            "nom de famille,    FAKE, LAST_NAME",
            "téléphone,         MASK, PHONE",
            "mot de passe,      HASH, null",
            "Adresse IP,        MASK, IP_ADDRESS",
            "e-mail,            FAKE, EMAIL"
    })
    @DisplayName("#26c — accents, spaces and dashes in column names do not defeat the patterns "
            + "('prénom' used to fall through to the generic 'nom' → LAST_NAME)")
    void accentedAndSpacedNamesAreDetected(String column, Strategy strategy, SemanticType type) {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn(column, "text");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(strategy);
        assertThat(hit.get().semanticType()).isEqualTo(type);
    }

    @Test
    @DisplayName("'adresse_ip' wins over the generic 'adresse' rule (was FAKE ADDRESS before #126a)")
    void adresseIpIsAnIpAddressNotAPostalAddress() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("adresse_ip", "varchar");
        assertThat(hit).isPresent();
        assertThat(hit.get().matchedPattern()).isEqualTo("adresse_ip");
    }

    @Test
    void frenchPasswordUpdateTimestampIsStillSkipped() {
        // Same false-positive class as #45a, French spelling : HASH cannot land in a timestamp.
        assertThat(ColumnStrategyMapper.suggestForColumn("date_maj_mot_de_passe", "timestamp")).isEmpty();
    }
}
