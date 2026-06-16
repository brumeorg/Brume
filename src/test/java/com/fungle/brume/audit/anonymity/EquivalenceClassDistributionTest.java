package com.fungle.brume.audit.anonymity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EquivalenceClassDistribution} — the bucket arithmetic.
 */
class EquivalenceClassDistributionTest {

    @Test
    @DisplayName("empty list yields the empty distribution with k_min=-1")
    void emptyDistribution() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of());
        assertThat(d.totalClasses()).isZero();
        assertThat(d.totalRows()).isZero();
        assertThat(d.kMin()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("singletons go to the k=1 bucket and pin k_min to 1")
    void singletonsBucket() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(1L, 1L, 1L));
        assertThat(d.singletons()).isEqualTo(3);
        assertThat(d.kMin()).isEqualTo(1L);
        assertThat(d.totalRows()).isEqualTo(3L);
    }

    @Test
    @DisplayName("buckets boundaries: 4→k2to4, 5→k5to9, 9→k5to9, 10→k10to99, 100→k100plus")
    void bucketBoundaries() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(
                List.of(2L, 4L, 5L, 9L, 10L, 99L, 100L, 500L));
        assertThat(d.k2to4()).isEqualTo(2);     // 2, 4
        assertThat(d.k5to9()).isEqualTo(2);     // 5, 9
        assertThat(d.k10to99()).isEqualTo(2);   // 10, 99
        assertThat(d.k100plus()).isEqualTo(2);  // 100, 500
        assertThat(d.singletons()).isZero();
        assertThat(d.kMin()).isEqualTo(2L);
    }

    @Test
    @DisplayName("k_average = total / classes (arithmetic mean)")
    void average() {
        EquivalenceClassDistribution d = EquivalenceClassDistribution.from(List.of(1L, 3L, 8L));
        assertThat(d.totalRows()).isEqualTo(12L);
        assertThat(d.totalClasses()).isEqualTo(3L);
        assertThat(d.kAverage()).isEqualTo(4.0);
    }
}
