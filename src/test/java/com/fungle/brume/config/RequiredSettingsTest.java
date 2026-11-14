package com.fungle.brume.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #17d (ADR-0061) — with {@code BRUME_HMAC_SECRET} missing from {@code .env.brume}, the
 * run used to succeed with the literal {@code ${BRUME_HMAC_SECRET}} as HMAC secret.
 */
class RequiredSettingsTest {

    private static Map<String, String> complete() {
        Map<String, String> s = new HashMap<>();
        s.put("BRUME_HMAC_SECRET", "e2e-hmac-secret-0001");
        s.put("BRUME_FPE_KEY", "e2e-fpe-key-0001");
        s.put("BRUME_SOURCE_HOST", "localhost");
        s.put("BRUME_SOURCE_DB", "postgres");
        s.put("BRUME_SOURCE_USER", "postgres");
        s.put("BRUME_SOURCE_PASSWORD", "postgres");
        s.put("BRUME_SCHEMA", "public");
        s.put("BRUME_TARGET_HOST", "localhost");
        s.put("BRUME_TARGET_DB", "postgres");
        s.put("BRUME_TARGET_USER", "postgres");
        return s;
    }

    @Test
    void completeSettingsMissNothing() {
        assertThat(RequiredSettings.missing(complete()::get)).isEmpty();
    }

    @Test
    @DisplayName("#17d — every missing key is listed at once, secrets included")
    void listsEveryMissingKey() {
        Map<String, String> s = complete();
        s.remove("BRUME_HMAC_SECRET");
        s.remove("BRUME_FPE_KEY");
        s.put("BRUME_SCHEMA", "  ");

        assertThat(RequiredSettings.missing(s::get))
                .containsExactly("BRUME_HMAC_SECRET", "BRUME_FPE_KEY", "BRUME_SCHEMA");
    }

    @Test
    void targetIsOnlyRequiredForTheJdbcSink() {
        Map<String, String> s = complete();
        s.remove("BRUME_TARGET_HOST");
        s.remove("BRUME_TARGET_DB");
        s.remove("BRUME_TARGET_USER");

        assertThat(RequiredSettings.missing(s::get)).containsExactly(
                "BRUME_TARGET_HOST", "BRUME_TARGET_DB", "BRUME_TARGET_USER");
        s.put("BRUME_SINK_TYPE", "DUMP");
        assertThat(RequiredSettings.missing(s::get)).isEmpty();
        // plan / dry-run / scan force the NULL sink before the check
        s.put("BRUME_SINK_TYPE", "JDBC");
        s.put("brume.sink.type", "NULL");
        assertThat(RequiredSettings.missing(s::get)).isEmpty();
    }

    @Test
    void anEmptyPasswordIsAllowedButTheKeyMustExist() {
        Map<String, String> s = complete();
        s.put("BRUME_SOURCE_PASSWORD", "");
        assertThat(RequiredSettings.missing(s::get)).isEmpty();

        s.remove("BRUME_SOURCE_PASSWORD");
        assertThat(RequiredSettings.missing(s::get)).containsExactly("BRUME_SOURCE_PASSWORD");
    }

    @Test
    void thePropertyItFeedsCountsAsSet() {
        Map<String, String> s = complete();
        s.remove("BRUME_SOURCE_HOST");
        s.remove("BRUME_SOURCE_DB");
        s.remove("BRUME_HMAC_SECRET");
        s.put("replication.source.url", "jdbc:postgresql://db:5432/app");
        s.put("brume.hmac-secret", "from-a-system-property");

        assertThat(RequiredSettings.missing(s::get)).isEmpty();
    }
}
