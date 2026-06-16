package com.fungle.brume.anonymization;

import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.report.SubstitutionDictStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SubstitutionDictionary}.
 *
 * <p>Verifies determinism, cross-call consistency and thread-safe behavior.
 */
class SubstitutionDictionaryTest {

    private SubstitutionDictionary dictionary;

    @BeforeEach
    void setUp() {
        var substDictProps = new BrumeProperties.SubstitutionDictProperties(1_000_000L);
        var reportProps = new BrumeProperties.ReportProperties("", "", "");
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "test-secret-1234", "test-secret-1234", "HmacSHA256", "fr", 0.0,
                substDictProps, reportProps);
        dictionary = new SubstitutionDictionary(props);
    }

    @Test
    void getOrCreate_sameKey_returnsSameValue() {
        Object first  = dictionary.getOrCreate("user_email", "alice@example.com", () -> "fake1@example.com");
        Object second = dictionary.getOrCreate("user_email", "alice@example.com", () -> "other@example.com");

        // Generator must have been called only once; second call returns cached value
        assertThat(second).isEqualTo(first).isEqualTo("fake1@example.com");
    }

    @Test
    void getOrCreate_differentKeys_returnDifferentValues() {
        Object v1 = dictionary.getOrCreate("user_email", "alice@example.com", () -> "fake-alice");
        Object v2 = dictionary.getOrCreate("user_email", "bob@example.com", () -> "fake-bob");

        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void buildLongKey_sameInputs_returnsSameKey() {
        long k1 = dictionary.buildLongKey("user_email", "alice@example.com");
        long k2 = dictionary.buildLongKey("user_email", "alice@example.com");
        assertThat(k1).isEqualTo(k2);
    }

    @Test
    void buildLongKey_differentSemanticKey_returnsDifferentKey() {
        long k1 = dictionary.buildLongKey("user_email", "alice@example.com");
        long k2 = dictionary.buildLongKey("order_email", "alice@example.com");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void buildLongKey_differentAlgorithms_produceDifferentKeys() {
        // Same semanticKey and realValue with different HMAC algorithms must produce different keys
        var substDictProps = new BrumeProperties.SubstitutionDictProperties(1_000_000L);
        var reportProps = new BrumeProperties.ReportProperties("", "", "");
        BrumeProperties props256 = new BrumeProperties(
                "config.yaml", "test-secret-1234", "test-secret-1234",
                "HmacSHA256", "fr", 0.0, substDictProps, reportProps);
        BrumeProperties props512 = new BrumeProperties(
                "config.yaml", "test-secret-1234", "test-secret-1234",
                "HmacSHA512", "fr", 0.0, substDictProps, reportProps);

        SubstitutionDictionary dict256 = new SubstitutionDictionary(props256);
        SubstitutionDictionary dict512 = new SubstitutionDictionary(props512);

        long key256 = dict256.buildLongKey("user_email", "test@example.com");
        long key512 = dict512.buildLongKey("user_email", "test@example.com");

        assertThat(key256).isNotEqualTo(key512);
    }

    @Test
    void forward_map_uses_long_keys_for_memory_efficiency() throws Exception {
        // B2 (#10) — locks the invariant that the internal map keys are Long, not String.
        // A 64-char hex String would cost ~80 bytes per key vs ~16 bytes for a boxed Long,
        // delivering the ~3-4× footprint reduction targeted by B2.
        java.lang.reflect.Field forward = SubstitutionDictionary.class.getDeclaredField("forward");
        forward.setAccessible(true);
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) forward.get(dictionary);

        // Trigger one entry so the map has a sample key
        dictionary.getOrCreate("any_key", "any_value", () -> "fake");
        assertThat(map.keySet().iterator().next()).isInstanceOf(Long.class);
    }

    @Test
    void size_reflectsCachedEntries() {
        assertThat(dictionary.size()).isZero();
        dictionary.getOrCreate("semA", "k1", () -> "v1");
        dictionary.getOrCreate("semB", "k2", () -> "v2");
        assertThat(dictionary.size()).isEqualTo(2);
    }

    @Test
    void getOrCreate_differentDictInstances_sameKeyProducesSameResult_whenSeededByFakeStrategy() {
        // Simulate two runs: each creates a fresh dictionary but uses the same HMAC seed
        var substDictProps = new BrumeProperties.SubstitutionDictProperties(1_000_000L);
        var reportProps = new BrumeProperties.ReportProperties("", "", "");
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "test-secret-1234", "test-secret-1234", "HmacSHA256", "fr", 0.0,
                substDictProps, reportProps);
        SubstitutionDictionary dict1 = new SubstitutionDictionary(props);
        SubstitutionDictionary dict2 = new SubstitutionDictionary(props);

        long key1 = dict1.buildLongKey("user_email", "real@test.com");
        long key2 = dict2.buildLongKey("user_email", "real@test.com");

        // Keys must be identical across instances (deterministic HMAC)
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void shouldThrowException_whenExceedingMaxEntries() {
        // Arrange — create dictionary with limit of 2 entries
        var substDictProps = new BrumeProperties.SubstitutionDictProperties(2L);
        var reportProps = new BrumeProperties.ReportProperties("", "", "");
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "test-secret-1234", "test-secret-1234", "HmacSHA256", "fr", 0.0,
                substDictProps, reportProps);
        SubstitutionDictionary limitedDict = new SubstitutionDictionary(props);

        // Act — insert 2 entries (OK)
        limitedDict.getOrCreate("semA", "key1", () -> "value1");
        limitedDict.getOrCreate("semB", "key2", () -> "value2");

        // Assert — 3rd entry throws exception
        assertThat(limitedDict.size()).isEqualTo(2);
        assertThatThrownBy(() -> limitedDict.getOrCreate("semC", "key3", () -> "value3"))
                .isInstanceOf(SubstitutionDictionaryOverflowException.class)
                .hasMessageContaining("SubstitutionDictionary exceeded its maximum size")
                .hasMessageContaining("Top contributors:")
                .hasMessageContaining("semA: 1 entries")
                .hasMessageContaining("semB: 1 entries");
    }

    @Test
    void shouldAllowInsertionsUpToMaxEntries() {
        // Arrange — create dictionary with limit of 3 entries
        var substDictProps = new BrumeProperties.SubstitutionDictProperties(3L);
        var reportProps = new BrumeProperties.ReportProperties("", "", "");
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "test-secret-1234", "test-secret-1234", "HmacSHA256", "fr", 0.0,
                substDictProps, reportProps);
        SubstitutionDictionary limitedDict = new SubstitutionDictionary(props);

        // Act — insert 3 entries (all should succeed)
        limitedDict.getOrCreate("semA", "key1", () -> "value1");
        limitedDict.getOrCreate("semB", "key2", () -> "value2");
        limitedDict.getOrCreate("semC", "key3", () -> "value3");

        // Assert — size is 3 and no exception thrown
        assertThat(limitedDict.size()).isEqualTo(3);
    }

    @Test
    void topContributors_shouldBeSortedByEntryCountThenSemanticKey() {
        for (int i = 0; i < 100; i++) {
            int index = i;
            dictionary.getOrCreate("semA", "a-" + index, () -> "fake-a-" + index);
        }
        for (int i = 0; i < 50; i++) {
            int index = i;
            dictionary.getOrCreate("semB", "b-" + index, () -> "fake-b-" + index);
        }
        dictionary.getOrCreate("semC", "c-1", () -> "fake-c-1");

        assertThat(dictionary.topContributors(2)).containsExactly(
                new SubstitutionDictStats.TopContributor("semA", 100),
                new SubstitutionDictStats.TopContributor("semB", 50)
        );
    }

    @Test
    void topContributors_shouldBreakTiesAlphabetically() {
        dictionary.getOrCreate("semB", "b-1", () -> "fake-b-1");
        dictionary.getOrCreate("semA", "a-1", () -> "fake-a-1");

        assertThat(dictionary.topContributors(2)).containsExactly(
                new SubstitutionDictStats.TopContributor("semA", 1),
                new SubstitutionDictStats.TopContributor("semB", 1)
        );
    }

    @Test
    void snapshot_shouldExposeEntriesLimitAndTopContributors() {
        dictionary.getOrCreate("users.email", "alice@example.com", () -> "fake-alice");
        dictionary.getOrCreate("users.email", "bob@example.com", () -> "fake-bob");
        dictionary.getOrCreate("orders.notes", "note-1", () -> "fake-note-1");

        SubstitutionDictStats stats = dictionary.snapshot(10);

        assertThat(stats.entries()).isEqualTo(3);
        assertThat(stats.limit()).isEqualTo(1_000_000L);
        assertThat(stats.topContributors()).containsExactly(
                new SubstitutionDictStats.TopContributor("users.email", 2),
                new SubstitutionDictStats.TopContributor("orders.notes", 1)
        );
    }

    @Test
    void atomicCounter_shouldMatchNumberOfUniqueConcurrentInsertions() throws ExecutionException, InterruptedException {
        int taskCount = 200;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<? extends Future<?>> futures = IntStream.range(0, taskCount)
                    .mapToObj(i -> executor.submit(() ->
                            dictionary.getOrCreate("sem.concurrent", "value-" + i, () -> "fake-" + i)))
                    .toList();

            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(dictionary.size()).isEqualTo(taskCount);
        assertThat(dictionary.snapshot(1).topContributors())
                .containsExactly(new SubstitutionDictStats.TopContributor("sem.concurrent", taskCount));
    }

    @Test
    void warnPalier_shouldScaleWithConfiguredLimit() {
        var substDictProps = new BrumeProperties.SubstitutionDictProperties(100_000L);
        var reportProps = new BrumeProperties.ReportProperties("", "", "");
        BrumeProperties props = new BrumeProperties(
                "config.yaml", "test-secret-1234", "test-secret-1234", "HmacSHA256", "fr", 0.0,
                substDictProps, reportProps);

        SubstitutionDictionary limitedDict = new SubstitutionDictionary(props);

        assertThat(limitedDict.warnPalier()).isEqualTo(10_000L);
    }
}
