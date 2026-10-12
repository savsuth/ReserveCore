package io.github.savsuth.reservecore.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Expected values are worked out by hand with the nearest-rank method: rank = ceil(p / 100 * n). */
class LatencySummaryTest {

    private static final long MS = 1_000_000L;

    @Test
    void percentilesOfOneToHundredMillisecondsUseNearestRank() {
        List<Long> nanos = new ArrayList<>();
        for (long i = 100; i >= 1; i--) {
            nanos.add(i * MS);
        }

        LatencySummary summary = LatencySummary.of(nanos, 2_000 * MS);

        assertThat(summary.count()).isEqualTo(100);
        assertThat(summary.p50Ms()).isEqualTo(50.0);
        assertThat(summary.p95Ms()).isEqualTo(95.0);
        assertThat(summary.p99Ms()).isEqualTo(99.0);
        assertThat(summary.maxMs()).isEqualTo(100.0);
        assertThat(summary.throughputPerSecond()).isEqualTo(50.0);
    }

    @Test
    void smallSamplesRoundTheRankUp() {
        LatencySummary summary = LatencySummary.of(List.of(40 * MS, 10 * MS, 30 * MS, 20 * MS), 500 * MS);

        assertThat(summary.p50Ms()).isEqualTo(20.0);
        assertThat(summary.p95Ms()).isEqualTo(40.0);
        assertThat(summary.p99Ms()).isEqualTo(40.0);
        assertThat(summary.maxMs()).isEqualTo(40.0);
        assertThat(summary.throughputPerSecond()).isEqualTo(8.0);
    }

    @Test
    void subMillisecondLatenciesKeepTheirFraction() {
        LatencySummary summary = LatencySummary.of(List.of(1_500_000L), 1_000 * MS);

        assertThat(summary.p50Ms()).isEqualTo(1.5);
        assertThat(summary.maxMs()).isEqualTo(1.5);
    }

    @Test
    void emptySampleIsRejected() {
        assertThatThrownBy(() -> LatencySummary.of(List.of(), MS)).isInstanceOf(IllegalArgumentException.class);
    }
}
