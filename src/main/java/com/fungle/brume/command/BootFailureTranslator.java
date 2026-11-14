package com.fungle.brume.command;

import com.fungle.brume.config.ConfigurationException;
import com.fungle.brume.error.BrumeErrorCode;
import com.fungle.brume.error.BrumeException;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a failure of the Spring context boot into the exception the operator should see
 * (#17c, ADR-0061).
 *
 * <p>Validators run as {@code @PostConstruct} hooks and sink factories run during bean
 * creation : their {@link BrumeException} reaches {@code main} wrapped in several Spring
 * {@code BeanCreationException}s. Spring itself rejects badly typed settings
 * ({@code BRUME_PIPELINE_MODE=FOO}, {@code BRUME_PARALLELISM=abc}) with a
 * {@link BindException}. Without translation, both printed a Spring stack trace or an
 * "APPLICATION FAILED TO START" banner naming Java classes and unresolved placeholders.
 *
 * <ul>
 *   <li>a {@link BrumeException} anywhere in the cause chain is returned as-is ;</li>
 *   <li>a {@link BindException} becomes {@link BrumeErrorCode#CONFIG_PROPERTY_INVALID},
 *       naming the setting, the environment variable it came from, the rejected value and
 *       the accepted ones ;</li>
 *   <li>anything else is returned unchanged and reported as {@code [UNEXPECTED]}, with its
 *       stack trace : that is a Brume bug.</li>
 * </ul>
 */
public final class BootFailureTranslator {

    /** {@code ${BRUME_PIPELINE_MODE:STREAMING}} → {@code BRUME_PIPELINE_MODE}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_.-]+)(?::[^}]*)?}$");

    private final UnaryOperator<String> settingLookup;

    /** Resolves settings from system properties ({@code .env.brume} is loaded there), then the environment. */
    public BootFailureTranslator() {
        this(key -> {
            String value = System.getProperty(key);
            return value != null ? value : System.getenv(key);
        });
    }

    BootFailureTranslator(UnaryOperator<String> settingLookup) {
        this.settingLookup = settingLookup;
    }

    public Throwable translate(Throwable failure) {
        BrumeException brume = find(failure, BrumeException.class);
        if (brume != null) return brume;
        BindException bind = find(failure, BindException.class);
        if (bind != null) return fromBind(bind);
        return failure;
    }

    private ConfigurationException fromBind(BindException bind) {
        String name = bind.getName().toString();
        ConfigurationProperty property = bind.getProperty();
        String raw = property == null || property.getValue() == null ? null : property.getValue().toString();
        String envKey = null;
        String value = raw;
        if (raw != null) {
            Matcher placeholder = PLACEHOLDER.matcher(raw.trim());
            if (placeholder.matches()) {
                envKey = placeholder.group(1);
                String resolved = settingLookup.apply(envKey);
                if (resolved != null) value = resolved;
            }
        }
        Class<?> type = bind.getTarget().getType().resolve(Object.class);

        StringBuilder message = new StringBuilder("Invalid value");
        if (value != null) message.append(" '").append(value).append('\'');
        message.append(" for ").append(name);
        if (envKey != null) message.append(" (set by ").append(envKey).append(')');
        message.append('.');

        String where = envKey != null
                ? "Fix " + envKey + " in .env.brume (or in the environment)."
                : "Fix '" + name + "' in your configuration.";
        return new ConfigurationException(BrumeErrorCode.CONFIG_PROPERTY_INVALID, message.toString(),
                expected(type) + " " + where, bind);
    }

    private static String expected(Class<?> type) {
        if (type.isEnum()) {
            String values = Arrays.stream(type.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return "Allowed values : " + values + ".";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class) {
            return "Expected a whole number.";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "Expected a number (e.g. 0.5).";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "Expected true or false.";
        }
        if (type == Duration.class) {
            return "Expected a duration (e.g. 30s, 5m).";
        }
        return "Expected a value of type " + type.getSimpleName().toLowerCase(Locale.ROOT) + ".";
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for (Throwable cur = failure; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) return type.cast(cur);
            if (cur.getCause() == cur) break;
        }
        return null;
    }
}
