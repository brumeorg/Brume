package com.fungle.brume.output;

/**
 * Console output mode selected at runtime via {@code brume.output.mode} (or the
 * {@code --json} CLI flag parsed pre-boot in
 * {@link com.fungle.brume.BrumeApplication#main(String[])}).
 *
 * <ul>
 *   <li>{@link #TEXT} — human-readable ASCII tables on stdout, plain-text logs (default).</li>
 *   <li>{@link #JSON} — machine-readable: a single JSON wrapper object on stdout for
 *       the run result, structured JSON logs on stderr. See ADR-0030.</li>
 * </ul>
 */
public enum OutputMode {
    TEXT,
    JSON;

    /** System property name used to propagate the mode pre-boot (CLI flag → Logback). */
    public static final String SYSTEM_PROPERTY = "brume.output.mode";

    /**
     * Resolves the mode from the JVM system property — usable from non-Spring code
     * (Logback encoders, picocli exception handlers) that runs before or outside the
     * Spring context.
     *
     * @return the parsed mode; falls back to {@link #TEXT} when the property is unset
     *         or contains an unknown value
     */
    public static OutputMode current() {
        String raw = System.getProperty(SYSTEM_PROPERTY);
        if (raw == null || raw.isBlank()) return TEXT;
        try {
            return OutputMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TEXT;
        }
    }
}
