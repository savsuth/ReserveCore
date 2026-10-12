package io.github.savsuth.reservecore.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Throughput and latency percentiles of one load run. Percentiles use the nearest-rank method
 * (rank = ceil(p / 100 * n) in the sorted sample), so every reported value is an observed latency.
 */
public record LatencySummary(int count, double throughputPerSecond, double p50Ms, double p95Ms, double p99Ms,
        double maxMs) {

    public static LatencySummary of(List<Long> latencyNanos, long wallNanos) {
        if (latencyNanos.isEmpty()) {
            throw new IllegalArgumentException("no latencies to summarize");
        }
        List<Long> sorted = new ArrayList<>(latencyNanos);
        Collections.sort(sorted);
        return new LatencySummary(sorted.size(), sorted.size() / (wallNanos / 1e9),
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99), sorted.getLast() / 1e6);
    }

    private static double percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(rank - 1) / 1e6;
    }
}
