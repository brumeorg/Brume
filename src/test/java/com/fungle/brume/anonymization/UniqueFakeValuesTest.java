package com.fungle.brume.anonymization;

import com.fungle.brume.anonymization.strategies.FakeStrategy;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.AnonymizationConfig;
import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.ColumnReference;
import com.fungle.brume.config.model.LinkedColumnsConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.config.model.TableAnonymizationConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #29a — fake values on UNIQUE columns. Datafaker draws first names from ≈ 5 000 entries :
 * distinct real values collide after a few dozen rows, and the target used to drop the
 * colliding rows in silence. Small-vocabulary fakes now carry a uniqueness suffix there.
 */
class UniqueFakeValuesTest {

    private final BrumeProperties props = new BrumeProperties(
            "unused-config-path", "test-hmac-secret-32-chars-at-least", "test-fpe-key-16b",
            "HmacSHA256", "en", 0.0,
            new BrumeProperties.SubstitutionDictProperties(1_000_000L),
            new BrumeProperties.ReportProperties("", "", ""));
    private final FakeStrategy fake = new FakeStrategy(props);

    @Test
    @DisplayName("suffix : deterministic, 16 hex chars, case-sensitive like the UNIQUE constraint")
    void suffixIsDeterministicAndCaseSensitive() {
        String alice = fake.withUniqueSuffix("Darrell", "Alice", null);

        assertThat(alice).matches("^Darrell\\.[0-9a-f]{16}$").isEqualTo(fake.withUniqueSuffix("Darrell", "Alice", null));
        assertThat(fake.withUniqueSuffix("Darrell", "alice", null)).isNotEqualTo(alice);
    }

    @Test
    @DisplayName("suffix : the fake part is cut so the value fits varchar(n), the suffix is kept whole")
    void suffixFitsTheColumnLength() {
        assertThat(fake.withUniqueSuffix("Bartholomew", "x", 20)).hasSize(20).matches("^Bar\\.[0-9a-f]{16}$");
        assertThat(fake.withUniqueSuffix("Bo", "x", 40)).matches("^Bo\\.[0-9a-f]{16}$");
    }

    @Test
    @DisplayName("#29a regression — 10 000 distinct nicknames on a UNIQUE column give 10 000 distinct FAKE FIRST_NAMEs")
    void uniqueColumnGetsCollisionFreeFakes() {
        AnonymizationEngine engine = engine();
        AnonymizationConfig config = config(List.of());
        engine.prepare(schema(Set.of("nickname")), config);

        Set<Object> fakes = new HashSet<>();
        Set<Object> withoutSuffix = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            fakes.add(anonymize(engine, config, "nick_" + i).get("nickname"));
            withoutSuffix.add(anonymize(engine, config, "nick_" + i).get("first_name"));
        }

        assertThat(fakes).hasSize(10_000);
        assertThat(withoutSuffix).as("same FAKE FIRST_NAME on a non-unique column : names repeat, no suffix")
                .hasSizeLessThan(6_000).allMatch(v -> !((String) v).contains("."));
    }

    @Test
    @DisplayName("a column linked to a UNIQUE one gets the same suffixed fake (linked_columns stay consistent)")
    void linkedColumnFollowsTheUniqueOne() {
        AnonymizationEngine engine = engine();
        AnonymizationConfig config = config(List.of(new LinkedColumnsConfig("nick", List.of(
                new ColumnReference("account", "nickname"), new ColumnReference("account", "first_name")))));
        engine.prepare(schema(Set.of("nickname")), config);

        Map<String, Object> row = anonymize(engine, config, "nick_7");

        assertThat((String) row.get("nickname")).matches(".+\\.[0-9a-f]{16}$").isEqualTo(row.get("first_name"));
    }

    @Test
    @DisplayName("#29c regression — a FK to a UNIQUE column gets the exact parent value, suffix included, "
            + "cut to the shortest length of the class (was parent 'Kohler.a0fb…', child 'Kohler')")
    void foreignKeyToUniqueColumnGetsTheSameSuffixedValue() {
        AnonymizationEngine engine = engine();
        TableMetadata users = new TableMetadata("users", List.of(new ColumnMetadata("username", "text", false, 40)),
                List.of(), List.of(), Set.of("username"));
        TableMetadata orders = new TableMetadata("orders", List.of(new ColumnMetadata("username", "text", false, 20)),
                List.of(new ForeignKey("orders", "username", "users", "username")), List.of(), Set.of());
        AnonymizationConfig config = new AnonymizationConfig(List.of(), List.of(
                new TableAnonymizationConfig("users", List.of(new ColumnConfig("username", Strategy.FAKE, SemanticType.LAST_NAME, null))),
                new TableAnonymizationConfig("orders", List.of(new ColumnConfig("username", Strategy.FAKE, SemanticType.LAST_NAME, null)))));
        engine.prepare(new DatabaseSchema(Map.of("users", users, "orders", orders)), config);

        Object parent = engine.anonymizeRow(new ExtractedRow("users", new LinkedHashMap<>(Map.of("username", "jdupont"))),
                config, new ExecutionReport("s", "t")).data().get("username");
        Object child = engine.anonymizeRow(new ExtractedRow("orders", new LinkedHashMap<>(Map.of("username", "jdupont"))),
                config, new ExecutionReport("s", "t")).data().get("username");

        assertThat((String) parent).matches(".+\\.[0-9a-f]{16}$").hasSizeLessThanOrEqualTo(20).isEqualTo(child);
    }

    @Test
    @DisplayName("#29d regression — 50 000 distinct phones give (almost) 50 000 fake phones, in Datafaker's format "
            + "(the Datafaker draw collided ≈ 200 times : 1.6 % of 200 000 rows lost on a UNIQUE phone column)")
    void fakePhonesHardlyCollide() {
        SubstitutionDictionary dict = new SubstitutionDictionary(props);
        Set<Object> phones = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            phones.add(fake.anonymize("+33 6 00 00 " + i, SemanticType.PHONE, "phone", dict));
        }

        assertThat(50_000 - phones.size()).as("collisions").isLessThan(20);
        assertThat(phones).allMatch(p -> ((String) p).matches("^\\(\\d{3}\\) \\d{3}-\\d{4}$"));
        assertThat(fake.anonymize("0612345678", SemanticType.PHONE, "phone", new SubstitutionDictionary(props)))
                .as("deterministic").isEqualTo(fake.anonymize("0612345678", SemanticType.PHONE, "other", dict));
    }

    // ------------------------------------------------------------------------------------

    private AnonymizationEngine engine() {
        StrategyResolver resolver = mock(StrategyResolver.class);
        when(resolver.resolve(any())).thenReturn(fake);
        return new AnonymizationEngine(resolver, new SubstitutionDictionary(props), new SemanticKeyResolver(),
                mock(JsonPathProcessor.class));
    }

    /** {@code account(nickname, first_name)} : both FAKE FIRST_NAME, fed the same real value. */
    private static AnonymizationConfig config(List<LinkedColumnsConfig> linked) {
        return new AnonymizationConfig(linked, List.of(new TableAnonymizationConfig("account", List.of(
                new ColumnConfig("nickname", Strategy.FAKE, SemanticType.FIRST_NAME, null),
                new ColumnConfig("first_name", Strategy.FAKE, SemanticType.FIRST_NAME, null)))));
    }

    private static DatabaseSchema schema(Set<String> uniqueColumns) {
        TableMetadata account = new TableMetadata("account",
                List.of(new ColumnMetadata("nickname", "character varying", false, 100),
                        new ColumnMetadata("first_name", "character varying", false, 100)),
                List.of(), List.of(), uniqueColumns);
        return new DatabaseSchema(Map.of("account", account));
    }

    private static Map<String, Object> anonymize(AnonymizationEngine engine, AnonymizationConfig config, String value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nickname", value);
        data.put("first_name", value);
        return engine.anonymizeRow(new ExtractedRow("account", data), config, new ExecutionReport("s", "t")).data();
    }
}
