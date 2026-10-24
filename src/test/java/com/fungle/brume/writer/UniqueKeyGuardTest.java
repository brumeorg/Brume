package com.fungle.brume.writer;

import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.schema.model.ColumnMetadata;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.schema.model.UniqueKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** #29b — the DUMP sink must never write two rows that share a UNIQUE key : psql rejects the COPY. */
class UniqueKeyGuardTest {

    private static final ColumnConfig SHIFT = new ColumnConfig("day", Strategy.DATE_SHIFT, null, null);

    @Test
    @DisplayName("#29b — a repeated key on a column that can collide (DATE_SHIFT) is refused, the first row is kept")
    void repeatedKeyIsRefused() {
        UniqueKeyGuard guard = UniqueKeyGuard.forTable(table(new UniqueKey(List.of("day"), false)), Map.of("day", SHIFT));

        assertThat(guard.admit(row(1, LocalDate.of(2025, 6, 2)))).isTrue();
        assertThat(guard.admit(row(2, LocalDate.of(2025, 6, 3)))).isTrue();
        assertThat(guard.admit(row(3, LocalDate.of(2025, 6, 2)))).isFalse();
    }

    @Test
    @DisplayName("keys whose columns stay distinct (KEEP, HASH, FPE) are not tracked ; NULLs never collide by default")
    void injectiveKeysAndNullsAreLeftAlone() {
        assertThat(UniqueKeyGuard.forTable(table(new UniqueKey(List.of("id"), false)),
                Map.of("id", new ColumnConfig("id", Strategy.FPE_ID, null, null)))).isNull();

        UniqueKeyGuard guard = UniqueKeyGuard.forTable(table(new UniqueKey(List.of("day"), false)), Map.of("day", SHIFT));
        assertThat(guard.admit(row(1, null))).isTrue();
        assertThat(guard.admit(row(2, null))).isTrue();

        UniqueKeyGuard notDistinct = UniqueKeyGuard.forTable(table(new UniqueKey(List.of("day"), true)), Map.of("day", SHIFT));
        assertThat(notDistinct.admit(row(1, null))).isTrue();
        assertThat(notDistinct.admit(row(2, null))).as("NULLS NOT DISTINCT").isFalse();
    }

    @Test
    @DisplayName("a composite key collides only when every column repeats")
    void compositeKey() {
        UniqueKeyGuard guard = UniqueKeyGuard.forTable(table(new UniqueKey(List.of("id", "day"), false)), Map.of("day", SHIFT));

        assertThat(guard.admit(row(1, LocalDate.of(2025, 6, 2)))).isTrue();
        assertThat(guard.admit(row(2, LocalDate.of(2025, 6, 2)))).isTrue();
        assertThat(guard.admit(row(1, LocalDate.of(2025, 6, 2)))).isFalse();
    }

    @Test
    void setGrowsBeyondItsInitialCapacity() {
        UniqueKeyGuard.LongHashSet set = new UniqueKeyGuard.LongHashSet();
        for (long i = 1; i <= 100_000; i++) set.add(i * 0x9E3779B97F4A7C15L);
        assertThat(set.contains(77 * 0x9E3779B97F4A7C15L)).isTrue();
        assertThat(set.contains(123_456_789L)).isFalse();
    }

    private static TableMetadata table(UniqueKey key) {
        return new TableMetadata("holiday", List.of(new ColumnMetadata("id", "bigint", false),
                new ColumnMetadata("day", "date", true)), List.of(), List.of("id"), Set.of(), List.of(key));
    }

    private static ExtractedRow row(long id, LocalDate day) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("day", day);
        return new ExtractedRow("holiday", data);
    }
}
