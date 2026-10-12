package io.github.savsuth.reservecore.demo;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** The demonstration scenarios. Each prints what it does and one PASS/FAIL line per check. */
final class Scenarios {

    private final Api api;
    private final Api otherMerchant;
    private final Checks checks;
    private final String runId;
    private final List<String> compose;
    private final List<String> services;

    Scenarios(Api api, Api otherMerchant, Checks checks, String runId, List<String> compose, List<String> services) {
        this.api = api;
        this.otherMerchant = otherMerchant;
        this.checks = checks;
        this.runId = runId;
        this.compose = compose;
        this.services = services;
    }

    /** Full lifecycle on two instances: reserve, confirm, reserve, cancel, history, isolation, auth. */
    void lifecycle() {
        System.out.println("== lifecycle: reserve -> confirm, reserve -> cancel, across both instances");
        String sku = "LIFE-" + runId;
        createSku(sku, 5);
        Api.Reply held = reserve(0, sku, 2, 600, key());
        checks.expect("reserve 2 units on instance 1 -> 201", 201, held.status());
        Api.Reply confirmed = api.post(1, path(held) + "/confirm", "", key());
        checks.expect("confirm on instance 2 -> 200", 200, confirmed.status());
        checks.expect("confirmed status", "CONFIRMED", confirmed.text("status"));
        Api.Reply second = reserve(1, sku, 1, 600, key());
        Api.Reply cancelled = api.post(0, path(second) + "/cancel", "", key());
        checks.expect("cancel on instance 1 -> 200", 200, cancelled.status());
        checks.expect("cancelled status", "CANCELLED", cancelled.text("status"));
        expectStock(sku, 3, 0, 2);
        checks.expect("movement history, newest first",
                List.of("CANCELLED", "RESERVED", "CONFIRMED", "RESERVED", "STOCK_CREATED"),
                movements(sku).stream().map(m -> m.path("kind").asString()).toList());
        Api.Reply foreign = otherMerchant.get(0, "/v1/skus/" + sku);
        checks.expect("another merchant reading this SKU -> 404 SKU_NOT_FOUND", "404 SKU_NOT_FOUND", foreign.describe());
        Api.Reply anonymous = api.withKey(null).get(1, "/v1/skus/" + sku);
        checks.expect("request without an API key -> 401 UNAUTHORIZED", "401 UNAUTHORIZED", anonymous.describe());
    }

    /** The brief's demonstration: 20 units, 1,000 independent one-unit requests with distinct keys. */
    void contention(int concurrency) throws InterruptedException {
        System.out.println("== contention: 20 units, 1,000 one-unit requests, distinct keys, concurrency "
                + concurrency + ", alternating instances");
        String sku = "CONTENDED-" + runId;
        createSku(sku, 20);
        LoadRun.Result result = LoadRun.run(api, sku, 1000, concurrency, 3600, "contention-" + runId);
        System.out.println("  " + Bench.describe(result));
        checks.expect("reservations created", 20L, result.created());
        checks.expect("409 INSUFFICIENT_STOCK rejections", 980L, result.insufficientStock());
        checks.check("no unexpected responses", result.unexpected().isEmpty(), Bench.unexpectedSummary(result));
        for (int instance = 0; instance < api.instances(); instance++) {
            Api.Reply stock = api.get(instance, "/v1/skus/" + sku);
            checks.expect("stock seen by instance " + (instance + 1) + " (available/held/sold)", "0/20/0",
                    stock.number("available") + "/" + stock.number("held") + "/" + stock.number("sold"));
        }
        checks.expect("RESERVED movements", 20L, movements(sku).stream()
                .filter(m -> m.path("kind").asString().equals("RESERVED")).count());
    }

    /** Short holds expire; the worker reclaims them; a confirm after expiry is rejected. */
    void expiration() throws InterruptedException {
        System.out.println("== expiration: three 5 s holds, one confirmed in time, two left to expire");
        String sku = "EXPIRE-" + runId;
        createSku(sku, 3);
        Api.Reply kept = reserve(0, sku, 1, 5, key());
        Api.Reply abandoned1 = reserve(1, sku, 1, 5, key());
        Api.Reply abandoned2 = reserve(0, sku, 1, 5, key());
        checks.expect("confirm the first before its hold expires -> 200", 200,
                api.post(1, path(kept) + "/confirm", "", key()).status());
        expectStock(sku, 0, 2, 1);
        Instant expiresAt = Instant.parse(abandoned2.text("expiresAt"));
        System.out.println("  waiting for expiresAt " + expiresAt + " and the expiration worker...");
        boolean reclaimed = waitUntil(Duration.ofSeconds(30), () ->
                status(abandoned1).equals("EXPIRED") && status(abandoned2).equals("EXPIRED"));
        checks.check("both abandoned holds become EXPIRED", reclaimed,
                "statuses " + status(abandoned1) + ", " + status(abandoned2));
        System.out.println("  reclaimed " + Duration.between(expiresAt, Instant.now()).toMillis()
                + " ms after expiresAt (worker interval 1 s)");
        Api.Reply late = api.post(0, path(abandoned1) + "/confirm", "", key());
        checks.check("confirm after expiry -> 409", late.status() == 409
                && Set.of("RESERVATION_EXPIRED", "INVALID_STATE_TRANSITION").contains(late.code()), late.describe());
        expectStock(sku, 2, 0, 1);
        List<JsonNode> expired = movements(sku).stream()
                .filter(m -> m.path("kind").asString().equals("EXPIRED")).toList();
        checks.expect("EXPIRED movements", 2, expired.size());
        checks.check("EXPIRED movements written by the worker (requestId expiry-<batch>)",
                expired.stream().allMatch(m -> m.path("requestId").asString("").startsWith("expiry-")),
                expired.toString());
    }

    /** Duplicate and retried requests: one effect each, replays marked, conflicting reuse rejected. */
    void duplicates() throws Exception {
        System.out.println("== duplicates: 50 concurrent copies of one reservation, 20 of one confirmation");
        String sku = "DUP-" + runId;
        createSku(sku, 10);
        String reserveKey = key();
        String body = "{\"sku\": \"" + sku + "\", \"quantity\": 2}";
        List<Api.Reply> copies = concurrently(50, i -> api.post(i, "/v1/reservations", body, reserveKey));
        checks.expect("all 50 copies -> 201", Set.of(201), copies.stream().map(Api.Reply::status).collect(Collectors.toSet()));
        checks.expect("distinct reservation ids", 1L, copies.stream().map(r -> r.text("id")).distinct().count());
        checks.expect("responses not marked Idempotent-Replayed (the one that executed)", 1L,
                copies.stream().filter(r -> !r.replayed()).count());
        Api.Reply retry = api.post(1, "/v1/reservations", body, reserveKey);
        checks.check("retry after a lost response replays the same reservation",
                retry.status() == 201 && retry.replayed() && retry.text("id").equals(copies.getFirst().text("id")),
                retry.describe());
        Api.Reply reused = api.post(0, "/v1/reservations", "{\"sku\": \"" + sku + "\", \"quantity\": 3}", reserveKey);
        checks.expect("same key, different request -> 409 IDEMPOTENCY_KEY_REUSED", "409 IDEMPOTENCY_KEY_REUSED",
                reused.describe());
        String confirmKey = key();
        String confirmPath = path(copies.getFirst()) + "/confirm";
        List<Api.Reply> confirms = concurrently(20, i -> api.post(i, confirmPath, "", confirmKey));
        checks.expect("all 20 confirmation copies -> 200", Set.of(200),
                confirms.stream().map(Api.Reply::status).collect(Collectors.toSet()));
        checks.expect("confirmations that executed", 1L, confirms.stream().filter(r -> !r.replayed()).count());
        Api.Reply again = api.post(0, confirmPath, "", key());
        checks.expect("confirm again with a new key -> 409 INVALID_STATE_TRANSITION", "409 INVALID_STATE_TRANSITION",
                again.describe());
        expectStock(sku, 8, 0, 2);
        checks.expect("movement history", List.of("CONFIRMED", "RESERVED", "STOCK_CREATED"),
                movements(sku).stream().map(m -> m.path("kind").asString()).toList());
    }

    /** All instances killed while holds are active; after restart the workers reclaim every hold once. */
    void workerRestart() throws Exception {
        System.out.println("== worker restart: 30 holds of 5 s, every instance killed (SIGKILL), restarted after expiry");
        String sku = "RESTART-" + runId;
        createSku(sku, 30);
        List<Api.Reply> holds = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            holds.add(reserve(i, sku, 1, 5, key()));
        }
        Instant firstExpiry = holds.stream().map(r -> Instant.parse(r.text("expiresAt"))).min(Instant::compareTo).orElseThrow();
        Instant lastExpiry = holds.stream().map(r -> Instant.parse(r.text("expiresAt"))).max(Instant::compareTo).orElseThrow();
        checks.expect("`" + String.join(" ", compose) + " kill` exit code", 0, compose("kill"));
        Instant killedAt = Instant.now();
        checks.check("instances killed before the first hold expired", killedAt.isBefore(firstExpiry),
                "killed at " + killedAt + ", first expiry " + firstExpiry);
        Instant resumeAfter = lastExpiry.plusSeconds(2);
        System.out.println("  all instances down; waiting until " + resumeAfter + " so every hold expires with no worker running");
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), resumeAfter).toMillis()));
        checks.expect("`" + String.join(" ", compose) + " start` exit code", 0, compose("start"));
        boolean ready = waitUntil(Duration.ofSeconds(120), () -> {
            for (int instance = 0; instance < api.instances(); instance++) {
                if (!api.ready(instance)) {
                    return false;
                }
            }
            return true;
        });
        checks.check("all instances ready again", ready, "not ready within 120 s");
        Instant readyAt = Instant.now();
        boolean reclaimed = waitUntil(Duration.ofSeconds(30),
                () -> holds.stream().allMatch(h -> status(h).equals("EXPIRED")));
        checks.check("all 30 holds EXPIRED after restart", reclaimed, "some holds still not EXPIRED");
        System.out.println("  reclaimed within " + Duration.between(readyAt, Instant.now()).toMillis()
                + " ms of all instances reporting ready");
        expectStock(sku, 30, 0, 0);
        List<JsonNode> expired = movements(sku).stream()
                .filter(m -> m.path("kind").asString().equals("EXPIRED")).toList();
        checks.expect("EXPIRED movements", 30, expired.size());
        checks.expect("distinct reservations expired", 30L,
                expired.stream().map(m -> m.path("reservationId").asString()).distinct().count());
    }

    void createSku(String sku, int quantity) {
        Api.Reply reply = api.post(0, "/v1/skus", "{\"sku\": \"" + sku + "\", \"initialQuantity\": " + quantity + "}", null);
        if (reply.status() != 201) {
            throw new IllegalStateException("could not create SKU " + sku + ": " + reply.describe());
        }
    }

    private Api.Reply reserve(int instance, String sku, int quantity, int holdSeconds, String key) {
        return api.post(instance, "/v1/reservations",
                "{\"sku\": \"" + sku + "\", \"quantity\": " + quantity + ", \"holdSeconds\": " + holdSeconds + "}", key);
    }

    private void expectStock(String sku, int available, int held, int sold) {
        Api.Reply stock = api.get(0, "/v1/skus/" + sku);
        checks.expect("stock (available/held/sold)", available + "/" + held + "/" + sold,
                stock.number("available") + "/" + stock.number("held") + "/" + stock.number("sold"));
    }

    /** Effective status; an overdue hold whose units are not released yet reads "EXPIRED (reclaim pending)". */
    private String status(Api.Reply reservation) {
        Api.Reply current = api.get(0, path(reservation));
        if (current.status() != 200) {
            return current.describe();
        }
        return current.body().path("reclaimPending").asBoolean() ? current.text("status") + " (reclaim pending)"
                : current.text("status");
    }

    private List<JsonNode> movements(String sku) {
        List<JsonNode> all = new ArrayList<>();
        String path = "/v1/skus/" + sku + "/movements?limit=200";
        while (path != null) {
            Api.Reply page = api.get(0, path);
            page.body().path("items").forEach(all::add);
            JsonNode next = page.body().path("nextBefore");
            path = next.isNull() || next.isMissingNode() ? null
                    : "/v1/skus/" + sku + "/movements?limit=200&before=" + next.asLong();
        }
        return all;
    }

    private static String path(Api.Reply reservation) {
        return "/v1/reservations/" + reservation.text("id");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private int compose(String action) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(compose);
        command.add(action);
        command.addAll(services);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    interface Call {
        Api.Reply send(int index) throws Exception;
    }

    private static List<Api.Reply> concurrently(int copies, Call call) throws Exception {
        CountDownLatch ready = new CountDownLatch(copies);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Api.Reply>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < copies; i++) {
                int index = i;
                Callable<Api.Reply> task = () -> {
                    ready.countDown();
                    start.await();
                    return call.send(index);
                };
                futures.add(executor.submit(task));
            }
            ready.await();
            start.countDown();
        }
        List<Api.Reply> replies = new ArrayList<>();
        for (Future<Api.Reply> future : futures) {
            replies.add(future.get());
        }
        return replies;
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    /** Polls every 250 ms until the condition holds or the timeout passes (real-time demo, not a test). */
    private static boolean waitUntil(Duration timeout, Condition condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.holds()) {
                    return true;
                }
            } catch (Exception e) {
                System.out.println("  (still waiting: " + e.getClass().getSimpleName() + ")");
            }
            Thread.sleep(250);
        }
        return false;
    }
}
