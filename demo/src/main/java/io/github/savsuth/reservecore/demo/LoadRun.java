package io.github.savsuth.reservecore.demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends {@code requests} independent one-SKU reservation requests, each with its own Idempotency-Key,
 * from {@code concurrency} virtual threads, alternating between instances. Outcomes are split into
 * created (201), business rejections (409 INSUFFICIENT_STOCK) and unexpected (anything else).
 */
final class LoadRun {

    record Result(List<Api.Reply> replies, long wallNanos) {

        long created() {
            return replies.stream().filter(r -> r.status() == 201).count();
        }

        long insufficientStock() {
            return replies.stream().filter(LoadRun::isInsufficientStock).count();
        }

        List<Api.Reply> unexpected() {
            return replies.stream().filter(r -> r.status() != 201 && !isInsufficientStock(r)).toList();
        }

        LatencySummary all() {
            return LatencySummary.of(replies.stream().map(Api.Reply::nanos).toList(), wallNanos);
        }
    }

    private LoadRun() {
    }

    static Result run(Api api, String sku, int requests, int concurrency, int holdSeconds, String keyPrefix)
            throws InterruptedException {
        String body = "{\"sku\": \"" + sku + "\", \"quantity\": 1, \"holdSeconds\": " + holdSeconds + "}";
        Api.Reply[] replies = new Api.Reply[requests];
        AtomicInteger next = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        long[] startedAt = new long[1];
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < concurrency; worker++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = next.getAndIncrement(); i < requests; i = next.getAndIncrement()) {
                        replies[i] = api.post(i, "/v1/reservations", body, keyPrefix + "-" + i);
                    }
                    return null;
                });
            }
            ready.await();
            startedAt[0] = System.nanoTime();
            start.countDown();
        }
        long wallNanos = System.nanoTime() - startedAt[0];
        return new Result(new ArrayList<>(Arrays.asList(replies)), wallNanos);
    }

    private static boolean isInsufficientStock(Api.Reply reply) {
        return reply.status() == 409 && reply.code().equals("INSUFFICIENT_STOCK");
    }
}
