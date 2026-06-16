package com.fungle.brume.anonymization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungle.brume.anonymization.strategies.FakeStrategy;
import com.fungle.brume.anonymization.strategies.FpeIdStrategy;
import com.fungle.brume.anonymization.strategies.FpeUuidStrategy;
import com.fungle.brume.anonymization.strategies.HashStrategy;
import com.fungle.brume.anonymization.strategies.KeepStrategy;
import com.fungle.brume.anonymization.strategies.MaskStrategy;
import com.fungle.brume.anonymization.strategies.NullifyStrategy;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.JsonPathConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JsonPathProcessor}.
 *
 * <p>Verifies that JSON path traversal correctly locates and anonymizes leaf fields
 * without requiring a Spring context.
 */
class JsonPathProcessorTest {

    /** #79b — JSONB leaves now require host table/column + config for semanticKey resolution. */
    private static final String TEST_TABLE = "test_table";
    private static final String TEST_COLUMN = "jsonb_col";
    private static final AnonymizationConfig TEST_CONFIG =
            new AnonymizationConfig(java.util.Collections.emptyList(), java.util.Collections.emptyList());

    private JsonPathProcessor processor;
    private AnonymizationEngine engine;
    private BrumeProperties brumeProperties;

    @BeforeEach
    void setUp() {
        brumeProperties = new BrumeProperties("config.yaml", "test-secret-1234", "test-secret-1234", "HmacSHA256", "fr",  0.0,new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        SubstitutionDictionary dictionary = new SubstitutionDictionary(brumeProperties);
        FakeStrategy fakeStrategy      = new FakeStrategy(brumeProperties);
        FpeIdStrategy fpeIdStrategy    = new FpeIdStrategy(brumeProperties);
        FpeUuidStrategy fpeUuidStrategy = new FpeUuidStrategy(brumeProperties);
        MaskStrategy maskStrategy      = new MaskStrategy();
        HashStrategy hashStrategy      = new HashStrategy(brumeProperties);
        NullifyStrategy nullifyStrategy = new NullifyStrategy();
        KeepStrategy keepStrategy      = new KeepStrategy();

        StrategyResolver strategyResolver = new StrategyResolver(
                fakeStrategy, fpeIdStrategy, fpeUuidStrategy, maskStrategy, hashStrategy, nullifyStrategy, keepStrategy);
        SemanticKeyResolver semanticKeyResolver = new SemanticKeyResolver();
        ObjectMapper objectMapper = new ObjectMapper();
        processor = new JsonPathProcessor(objectMapper);

        engine = new AnonymizationEngine(strategyResolver, dictionary, semanticKeyResolver, processor);
    }

    @Test
    void process_shallowPath_anonymizesTargetedField() throws Exception {
        String json = "{\"email\":\"real@test.com\",\"name\":\"Alice\"}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE);

        Object result = processor.process(json, List.of(pathConfig), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result).isNotNull().isInstanceOf(String.class);
        String resultStr = result.toString();
        // Email field must be present in output
        assertThat(resultStr).contains("email");
        // The original email must have been replaced
        assertThat(resultStr).doesNotContain("real@test.com");
        // The non-targeted field must be unchanged
        assertThat(resultStr).contains("Alice");
    }

    @Test
    void process_nestedPath_anonymizesNestedField() throws Exception {
        String json = "{\"user\":{\"email\":\"real@test.com\",\"age\":30}}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.user.email", SemanticType.EMAIL, Strategy.FAKE);

        Object result = processor.process(json, List.of(pathConfig), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result).isNotNull().isInstanceOf(String.class);
        assertThat(result.toString()).doesNotContain("real@test.com");
        // Non-targeted fields must survive
        assertThat(result.toString()).contains("30");
    }

    @Test
    void process_multiplePathConfigs_anonymizesAllTargetedFields() throws Exception {
        String json = "{\"email\":\"real@test.com\",\"phone\":\"+33612345678\"}";
        List<JsonPathConfig> paths = List.of(
                new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE),
                new JsonPathConfig("$.phone", SemanticType.PHONE, Strategy.MASK)
        );

        Object result = processor.process(json, paths, engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result).isNotNull().isInstanceOf(String.class);
        String resultStr = result.toString();
        assertThat(resultStr).doesNotContain("real@test.com");
        assertThat(resultStr).doesNotContain("+33612345678");
    }

    @Test
    void process_nullValue_returnsNull() {
        JsonPathConfig pathConfig = new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE);
        Object result = processor.process(null, List.of(pathConfig), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);
        assertThat(result).isNull();
    }

    @Test
    void process_emptyPaths_returnsOriginalValue() {
        String json = "{\"email\":\"real@test.com\"}";
        Object result = processor.process(json, List.of(), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);
        assertThat(result).isEqualTo(json);
    }

    @Test
    void process_nonExistentPath_skipsGracefully() throws Exception {
        String json = "{\"name\":\"Alice\"}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.missing_field", SemanticType.EMAIL, Strategy.FAKE);

        // Must not throw
        Object result = processor.process(json, List.of(pathConfig), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result).isNotNull();
        // Original fields survive
        assertThat(result.toString()).contains("Alice");
    }

    @Test
    void process_nullifyStrategy_setsFieldToNull() throws Exception {
        String json = "{\"sensitive\":\"secret-data\"}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.sensitive", null, Strategy.NULLIFY);

        Object result = processor.process(json, List.of(pathConfig), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result).isNotNull().isInstanceOf(String.class);
        // Field should be null in the output JSON
        assertThat(result.toString()).contains("\"sensitive\":null");
    }

    /**
     * Regression guard for #23b (audit § B2, ADR-0018) — a strategy that fails inside
     * {@code applyPath} must propagate; the original (PII-bearing) JSONB must NOT be
     * silently written back to the target.
     */
    @Test
    void process_propagatesStrategyError_whenStrategyThrows() {
        AnonymizationEngine failingEngine = mock(AnonymizationEngine.class);
        when(failingEngine.anonymizeValue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("strategy boom"));

        String json = "{\"email\":\"alice@example.com\"}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE);

        assertThatThrownBy(() ->
                processor.process(json, List.of(pathConfig), failingEngine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG))
                .as("strategy errors must propagate — no silent return-original (audit § B2)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy boom");
    }

    /**
     * Counterpart : malformed source JSONB stays a controlled-degradation case (parse error)
     * — return original, log warn. Verifies that ADR-0018's narrowed catch did not regress
     * this previously documented behavior.
     */
    @Test
    void process_returnsOriginal_whenJsonIsMalformed() {
        String malformed = "{this is not json";
        JsonPathConfig pathConfig = new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE);

        Object result = processor.process(malformed, List.of(pathConfig), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result)
                .as("malformed source JSONB stays a degraded path — original returned, run continues")
                .isEqualTo(malformed);
    }

    /**
     * Regression guard for #23d (audit § B4, ADR-0019) — a numeric leaf must remain a JSON
     * number after anonymization. FPE_ID returns a {@code Long}, which prior behavior coerced
     * to a quoted string ({@code "id":"13579"}) — drift of JSONB schema on the target.
     */
    @Test
    void process_preservesNumericType_whenStrategyReturnsLong() {
        String json = "{\"id\":42}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.id", null, Strategy.FPE_ID);

        Object result = processor.process(json, List.of(pathConfig), engine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result).isNotNull().isInstanceOf(String.class);
        String resultStr = result.toString();
        assertThat(resultStr)
                .as("numeric leaf must stay a JSON number — no quotes around the value")
                .matches("\\{\"id\":-?\\d+\\}")
                .doesNotContain("\"id\":\"");
    }

    /**
     * #23d : when the strategy returns a {@code String} but the original leaf was numeric,
     * the result is parsed back to a number to preserve the JSON type.
     */
    @Test
    void process_preservesNumericType_whenStrategyReturnsNumericString() {
        AnonymizationEngine stringReturningEngine = mock(AnonymizationEngine.class);
        when(stringReturningEngine.anonymizeValue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("13579");

        String json = "{\"id\":42}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.id", null, Strategy.FPE_ID);

        Object result = processor.process(json, List.of(pathConfig), stringReturningEngine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result.toString())
                .as("string \"13579\" on a numeric leaf must be parsed back to a number")
                .isEqualTo("{\"id\":13579}");
    }

    /**
     * #23d : numeric leaf with a strategy that returns a non-numeric string — degraded to
     * string with a WARN. This preserves run progress when an operator misconfigures (e.g.
     * applies FAKE to a numeric leaf), but the schema drift is logged and not silent.
     */
    @Test
    void process_degradesToString_whenAnonymizedNotNumericOnNumericLeaf() {
        AnonymizationEngine fakeReturning = mock(AnonymizationEngine.class);
        when(fakeReturning.anonymizeValue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("not-a-number");

        String json = "{\"id\":42}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.id", SemanticType.EMAIL, Strategy.FAKE);

        Object result = processor.process(json, List.of(pathConfig), fakeReturning, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result.toString())
                .as("non-numeric anonymized value on a numeric leaf falls back to a quoted string")
                .isEqualTo("{\"id\":\"not-a-number\"}");
    }

    /**
     * #23d : boolean leaf must stay boolean when the strategy returns a parseable token.
     */
    @Test
    void process_preservesBooleanType_whenAnonymizedIsBooleanString() {
        AnonymizationEngine boolReturning = mock(AnonymizationEngine.class);
        when(boolReturning.anonymizeValue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("false");

        String json = "{\"active\":true}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.active", null, Strategy.KEEP);

        Object result = processor.process(json, List.of(pathConfig), boolReturning, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

        assertThat(result.toString())
                .as("boolean leaf must stay a JSON boolean (no quotes)")
                .isEqualTo("{\"active\":false}");
    }

    /**
     * #79b — verify that the host JSONB column's table/column is propagated to
     * {@link SemanticKeyResolver}. Pre-fix, {@code AnonymizationEngine.anonymizeValue}
     * used a synthetic semanticKey {@code strategy.name() + "." + type.name()} which broke
     * cross-table consistency on JSONB leaves.
     */
    @org.junit.jupiter.api.DisplayName("#79b — host JSONB table/column propagated to SemanticKeyResolver")
    @Test
    void semanticKeyResolverReceivesHostTableAndColumn() {
        // Rebuild the engine with a spy resolver so we can verify the args.
        SemanticKeyResolver spyResolver = org.mockito.Mockito.spy(new SemanticKeyResolver());
        SubstitutionDictionary dict = new SubstitutionDictionary(brumeProperties);
        StrategyResolver strategies = new StrategyResolver(
                new com.fungle.brume.anonymization.strategies.FakeStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.FpeIdStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.FpeUuidStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.MaskStrategy(),
                new com.fungle.brume.anonymization.strategies.HashStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.NullifyStrategy(),
                new com.fungle.brume.anonymization.strategies.KeepStrategy());
        AnonymizationEngine engineWithSpy = new AnonymizationEngine(strategies, dict, spyResolver, processor);

        String json = "{\"email\":\"alice@test.com\"}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE);

        processor.process(json, List.of(pathConfig), engineWithSpy, "users", "profile", TEST_CONFIG);

        // The resolver must receive the host JSONB column's table/column, NOT a synthetic key.
        org.mockito.Mockito.verify(spyResolver)
                .resolve(org.mockito.ArgumentMatchers.eq("users"),
                         org.mockito.ArgumentMatchers.eq("profile"),
                         org.mockito.ArgumentMatchers.eq(TEST_CONFIG));
    }

    /**
     * #79b — same input value in two different JSONB columns must occupy two distinct
     * {@link SubstitutionDictionary} buckets. Pre-fix, both calls collapsed to the synthetic
     * key {@code "FAKE.EMAIL"} and shared a single dict bucket — bloated cache vs intent,
     * and contract drift: the semanticKey was disconnected from the host JSONB column.
     *
     * <p>Note: the observable fake output is identical regardless of the fix, because the
     * strategy implementations (FAKE/HASH/FPE) are <em>realValue-deterministic</em> — the
     * semanticKey only drives the dict bucket, not the fake computation. This test asserts
     * the bucket-structure contract directly.
     */
    @org.junit.jupiter.api.DisplayName("#79b — different host JSONB columns occupy different dict buckets")
    @Test
    void differentHostJsonbColumnsOccupyDifferentDictBuckets() {
        // Fresh dictionary so we can count entries unambiguously.
        SubstitutionDictionary freshDict = new SubstitutionDictionary(brumeProperties);
        StrategyResolver strategies = new StrategyResolver(
                new com.fungle.brume.anonymization.strategies.FakeStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.FpeIdStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.FpeUuidStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.MaskStrategy(),
                new com.fungle.brume.anonymization.strategies.HashStrategy(brumeProperties),
                new com.fungle.brume.anonymization.strategies.NullifyStrategy(),
                new com.fungle.brume.anonymization.strategies.KeepStrategy());
        AnonymizationEngine engineWithFreshDict = new AnonymizationEngine(
                strategies, freshDict, new SemanticKeyResolver(), processor);

        String json = "{\"email\":\"alice@test.com\"}";
        JsonPathConfig pathConfig = new JsonPathConfig("$.email", SemanticType.EMAIL, Strategy.FAKE);

        processor.process(json, List.of(pathConfig), engineWithFreshDict,
                "users", "profile_a", TEST_CONFIG);
        long sizeAfterA = freshDict.size();
        processor.process(json, List.of(pathConfig), engineWithFreshDict,
                "users", "profile_b", TEST_CONFIG);
        long sizeAfterB = freshDict.size();

        // Pre-fix : both calls collapsed on "FAKE.EMAIL" → sizeAfterB == sizeAfterA (1 bucket).
        // Post-fix : "users.profile_a" ≠ "users.profile_b" → sizeAfterB == sizeAfterA + 1.
        assertThat(sizeAfterA).as("first call creates one bucket").isEqualTo(1L);
        assertThat(sizeAfterB)
                .as("post-fix : distinct host JSONB columns create distinct dict buckets "
                        + "(pre-fix : both collapsed on synthetic 'FAKE.EMAIL' → still 1 bucket)")
                .isEqualTo(2L);
    }

    /**
     * #79a — when the schema-drift warn fires (anonymized value cannot be parsed back as
     * the original leaf's type), the raw value must NOT appear in the log message.
     * Pre-fix, the log included {@code 'alice-canary@example.com'} verbatim → ADR-0025
     * violation when the offending leaf carried PII.
     */
    @org.junit.jupiter.api.DisplayName("#79a — schema drift warn does not leak the anonymized value (ADR-0025)")
    @Test
    void schemaDriftWarnDoesNotLeakValueOnNumberLeaf() {
        final String canary = "alice-canary@example.com";
        AnonymizationEngine leakingEngine = mock(AnonymizationEngine.class);
        when(leakingEngine.anonymizeValue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(canary);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JsonPathProcessor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);

        try {
            // Integer leaf + anonymized string that is not numeric → drift warn fires.
            String json = "{\"contact_id\":42}";
            JsonPathConfig pathConfig = new JsonPathConfig("$.contact_id", SemanticType.EMAIL, Strategy.FAKE);
            processor.process(json, List.of(pathConfig), leakingEngine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

            java.util.List<String> messages = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();

            assertThat(messages)
                    .as("the schema drift warn must have fired")
                    .anyMatch(m -> m.contains("schema drift"));
            assertThat(messages)
                    .as("the raw anonymized value must NOT appear (ADR-0025)")
                    .noneMatch(m -> m.contains(canary));
            assertThat(messages)
                    .as("a length signal must replace the raw value")
                    .anyMatch(m -> m.contains("length=" + canary.length()));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * #79a — same leak class on the boolean drift path. Pre-fix included the raw value;
     * post-fix shows {@code length=N, masked}.
     */
    @org.junit.jupiter.api.DisplayName("#79a — boolean drift warn does not leak the anonymized value (ADR-0025)")
    @Test
    void schemaDriftWarnDoesNotLeakValueOnBooleanLeaf() {
        final String canary = "user-canary-secret-value";
        AnonymizationEngine leakingEngine = mock(AnonymizationEngine.class);
        when(leakingEngine.anonymizeValue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(canary);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JsonPathProcessor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);

        try {
            String json = "{\"is_active\":true}";
            JsonPathConfig pathConfig = new JsonPathConfig("$.is_active", null, Strategy.KEEP);
            processor.process(json, List.of(pathConfig), leakingEngine, TEST_TABLE, TEST_COLUMN, TEST_CONFIG);

            java.util.List<String> messages = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();

            assertThat(messages).anyMatch(m -> m.contains("schema drift"));
            assertThat(messages)
                    .as("the raw anonymized value must NOT appear on boolean drift path")
                    .noneMatch(m -> m.contains(canary));
            assertThat(messages).anyMatch(m -> m.contains("length=" + canary.length()));
        } finally {
            logger.detachAppender(appender);
        }
    }
}


