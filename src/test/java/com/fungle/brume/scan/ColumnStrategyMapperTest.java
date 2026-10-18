package com.fungle.brume.scan;

import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.scan.ColumnStrategyMapper.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pattern -> strategy mapping.
 *
 * <p>These lock in the Q3 decision table from ADR scan-command so a future contributor
 * cannot silently retune the heuristic without the regression showing up here.
 */
class ColumnStrategyMapperTest {

    @Test
    void emailColumnMapsToFakeEmail() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("billing_email");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.FAKE);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.EMAIL);
        assertThat(hit.get().matchedPattern()).isEqualTo("email");
    }

    @Test
    void ibanColumnMapsToHashIban() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("iban");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.HASH);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.IBAN);
    }

    @Test
    void passwordColumnMapsToHashWithoutSemanticType() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("password_hash");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.HASH);
        assertThat(hit.get().semanticType()).isNull();
    }

    @Test
    void birthdayColumnMapsToNullify() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("birthday");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.NULLIFY);
    }

    @Test
    void ipAddressColumnMapsToMask() {
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("ip_address");
        assertThat(hit).isPresent();
        assertThat(hit.get().strategy()).isEqualTo(Strategy.MASK);
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.IP_ADDRESS);
    }

    @Test
    void firstNamePatternWinsOverGenericName() {
        // Guard : the generic "name" rule is listed last so narrower patterns take precedence.
        Optional<Suggestion> hit = ColumnStrategyMapper.suggestForColumn("first_name");
        assertThat(hit).isPresent();
        assertThat(hit.get().semanticType()).isEqualTo(SemanticType.FIRST_NAME);
    }

    @Test
    void unknownColumnReturnsEmpty() {
        assertThat(ColumnStrategyMapper.suggestForColumn("quantity")).isEmpty();
        assertThat(ColumnStrategyMapper.suggestForColumn("total_amount")).isEmpty();
    }

    @Test
    void nullOrBlankColumnReturnsEmpty() {
        assertThat(ColumnStrategyMapper.suggestForColumn(null)).isEmpty();
        assertThat(ColumnStrategyMapper.suggestForColumn("")).isEmpty();
        assertThat(ColumnStrategyMapper.suggestForColumn("   ")).isEmpty();
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
}
