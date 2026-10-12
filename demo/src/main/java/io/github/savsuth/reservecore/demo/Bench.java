package io.github.savsuth.reservecore.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Benchmark: for each scenario and client concurrency, one fresh SKU per run and 1,000 one-unit reservation
 * requests with distinct keys, alternating instances. A warm-up run per scenario is not recorded.
 * Prints one Markdown row per run and the per-cell median, from measurements only.
 */
final class Bench {

    private record Scenario(String name, int units) {}

    private record Row(String scenario, int concurrency, int run, LoadRun.Result result, LatencySummary latency) {}

    private Bench() {
    }

    static void run(Scenarios scenarios, Api api, Checks checks, String runId, List<Integer> concurrencies, int runs)
            throws InterruptedException {
        List<Scenario> scenarioList = List.of(new Scenario("contended", 20), new Scenario("ample", 1000));
        System.out.println("== benchmark: 1,000 one-unit requests per run, " + runs + " runs per cell, instances "
                + api.instances() + ", client JVM " + System.getProperty("java.version") + ", client CPUs "
                + Runtime.getRuntime().availableProcessors());
        List<Row> rows = new ArrayList<>();
        for (Scenario scenario : scenarioList) {
            String warmupSku = scenario.name().toUpperCase(Locale.ROOT) + "-WARMUP-" + runId;
            scenarios.createSku(warmupSku, scenario.units());
            LoadRun.Result warmup = LoadRun.run(api, warmupSku, 1000, 200, 600, warmupSku);
            System.out.println("  warm-up " + scenario.name() + " (not recorded): " + describe(warmup));
            for (int concurrency : concurrencies) {
                for (int run = 1; run <= runs; run++) {
                    String sku = scenario.name().toUpperCase(Locale.ROOT) + "-C" + concurrency + "-R" + run + "-" + runId;
                    scenarios.createSku(sku, scenario.units());
                    LoadRun.Result result = LoadRun.run(api, sku, 1000, concurrency, 600, sku);
                    rows.add(new Row(scenario.name(), concurrency, run, result, result.all()));
                    long expectedCreated = Math.min(scenario.units(), 1000);
                    checks.check(scenario.name() + " c=" + concurrency + " run " + run + ": " + expectedCreated
                                    + " created, " + (1000 - expectedCreated) + " rejected, 0 unexpected",
                            result.created() == expectedCreated && result.insufficientStock() == 1000 - expectedCreated
                                    && result.unexpected().isEmpty(),
                            describe(result) + " " + unexpectedSummary(result));
                }
            }
        }
        System.out.println();
        System.out.println("| scenario | concurrency | run | created | 409 insufficient | unexpected | wall s | req/s | p50 ms | p95 ms | p99 ms | max ms |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (Row row : rows) {
            System.out.printf(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %.3f | %.0f | %.1f | %.1f | %.1f | %.1f |%n",
                    row.scenario(), row.concurrency(), row.run(), row.result().created(),
                    row.result().insufficientStock(), row.result().unexpected().size(),
                    row.result().wallNanos() / 1e9, row.latency().throughputPerSecond(), row.latency().p50Ms(),
                    row.latency().p95Ms(), row.latency().p99Ms(), row.latency().maxMs());
        }
        System.out.println();
        System.out.println("Median of the runs per cell:");
        System.out.println();
        System.out.println("| scenario | concurrency | req/s | p50 ms | p95 ms | p99 ms | max ms | unexpected (total) |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        Map<String, List<Row>> cells = rows.stream().collect(Collectors.groupingBy(
                r -> r.scenario() + "|" + r.concurrency(), LinkedHashMap::new, Collectors.toList()));
        for (List<Row> cell : cells.values()) {
            System.out.printf(Locale.ROOT, "| %s | %d | %.0f | %.1f | %.1f | %.1f | %.1f | %d |%n",
                    cell.getFirst().scenario(), cell.getFirst().concurrency(),
                    median(cell, r -> r.latency().throughputPerSecond()), median(cell, r -> r.latency().p50Ms()),
                    median(cell, r -> r.latency().p95Ms()), median(cell, r -> r.latency().p99Ms()),
                    median(cell, r -> r.latency().maxMs()),
                    cell.stream().mapToInt(r -> r.result().unexpected().size()).sum());
        }
    }

    static String describe(LoadRun.Result result) {
        LatencySummary latency = result.all();
        return String.format(Locale.ROOT,
                "created %d, 409 insufficient %d, unexpected %d; %.0f req/s; p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms",
                result.created(), result.insufficientStock(), result.unexpected().size(),
                latency.throughputPerSecond(), latency.p50Ms(), latency.p95Ms(), latency.p99Ms(), latency.maxMs());
    }

    static String unexpectedSummary(LoadRun.Result result) {
        return result.unexpected().stream().collect(Collectors.groupingBy(Api.Reply::describe, Collectors.counting()))
                .toString();
    }

    /** Median of an odd or even number of values (mean of the middle two). */
    private static double median(List<Row> cell, ToDoubleFunction<Row> metric) {
        double[] values = cell.stream().mapToDouble(metric).sorted().toArray();
        int mid = values.length / 2;
        return values.length % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2;
    }
}
