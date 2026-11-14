package com.fungle.brume.config;

import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.writer.SinkType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Pre-boot check of the settings {@code application.yaml} reads without a default
 * (#17d, ADR-0061).
 *
 * <p>Spring leaves an unresolved {@code ${BRUME_HMAC_SECRET}} as the literal text : with the
 * key missing from {@code .env.brume}, the run used to succeed with that public string as
 * HMAC secret (and {@code ${BRUME_FPE_KEY}}, exactly 16 bytes, as FPE key), so anyone could
 * recompute the pseudonyms. Missing connection settings surfaced as a misleading
 * "connection failed" or "schema '${BRUME_SCHEMA}' not found". This check lists every
 * missing key at once, before Spring boots.
 *
 * <p>A setting counts as present when its environment-style key ({@code BRUME_SOURCE_HOST},
 * from {@code .env.brume} or the environment) or the property it feeds
 * ({@code replication.source.url}…) is set. Passwords may be empty ({@code .pgpass},
 * {@code trust}) but the key must be declared.
 */
public final class RequiredSettings {

    private record Setting(String envKey, String property, boolean mayBeEmpty) {
    }

    private static final List<Setting> ALWAYS = List.of(
            new Setting("BRUME_HMAC_SECRET", "brume.hmac-secret", false),
            new Setting("BRUME_FPE_KEY", "brume.fpe-key", false),
            new Setting("BRUME_SOURCE_HOST", "replication.source.url", false),
            new Setting("BRUME_SOURCE_DB", "replication.source.url", false),
            new Setting("BRUME_SOURCE_USER", "replication.source.username", false),
            new Setting("BRUME_SOURCE_PASSWORD", "replication.source.password", true),
            new Setting("BRUME_SCHEMA", "replication.schema", false));

    private static final List<Setting> JDBC_TARGET = List.of(
            new Setting("BRUME_TARGET_HOST", "replication.target.url", false),
            new Setting("BRUME_TARGET_DB", "replication.target.url", false),
            new Setting("BRUME_TARGET_USER", "replication.target.username", false));

    private RequiredSettings() {
    }

    /**
     * Environment-style keys of the required settings that are missing, in declaration order.
     *
     * @param lookup resolves a key from system properties then the environment
     */
    static List<String> missing(UnaryOperator<String> lookup) {
        List<Setting> required = new ArrayList<>(ALWAYS);
        if (sinkType(lookup) == SinkType.JDBC) {
            required.addAll(JDBC_TARGET);
        }
        List<String> missing = new ArrayList<>();
        for (Setting s : required) {
            if (isSet(lookup.apply(s.property()), false)) continue;
            if (isSet(lookup.apply(s.envKey()), s.mayBeEmpty())) continue;
            missing.add(s.envKey());
        }
        return missing;
    }

    /** Throws {@link BrumeErrorCode#CONFIG_ENV_VAR_MISSING} when a required setting is missing. */
    public static void check() {
        List<String> missing = missing(key -> {
            String value = System.getProperty(key);
            return value != null ? value : System.getenv(key);
        });
        if (missing.isEmpty()) return;
        throw new ConfigurationException(BrumeErrorCode.CONFIG_ENV_VAR_MISSING,
                (missing.size() == 1 ? "Required setting missing : " : "Required settings missing : ")
                        + String.join(", ", missing) + ".",
                "Add " + (missing.size() == 1 ? "it" : "them") + " to .env.brume (the template created by "
                        + "'brume init' lists every key) or export " + (missing.size() == 1 ? "it" : "them")
                        + " in the environment.");
    }

    /** Sink the run will use : forced overrides ({@code plan}, {@code audit}) first, then the setting, then JDBC. */
    private static SinkType sinkType(UnaryOperator<String> lookup) {
        String raw = lookup.apply("brume.sink.type");
        if (raw == null || raw.isBlank()) raw = lookup.apply("BRUME_SINK_TYPE");
        if (raw == null || raw.isBlank()) return SinkType.JDBC;
        try {
            return SinkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null; // invalid value : reported by Spring binding (CONFIG_PROPERTY_INVALID)
        }
    }

    private static boolean isSet(String value, boolean mayBeEmpty) {
        return value != null && (mayBeEmpty || !value.isBlank());
    }
}
