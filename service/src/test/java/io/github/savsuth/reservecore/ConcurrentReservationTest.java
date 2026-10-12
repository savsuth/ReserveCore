package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Many independent requests compete for limited stock on one SKU. All requests are released together
 * by a latch (no sleeps), so they hit the inventory row lock at the same time.
 */
class ConcurrentReservationTest extends ApiTestSupport {

    @Test
    void fiveHundredOneUnitRequestsForTwentyUnitsYieldExactlyTwentyReservations() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"HOODIE\", \"initialQuantity\": 20}");

        List<Response> responses = reserveConcurrently(merchant, "HOODIE", 1, 500);

        Map<String, Long> outcomes = countOutcomes(responses);
        assertThat(outcomes).containsOnly(Map.entry("201", 20L), Map.entry("409 INSUFFICIENT_STOCK", 480L));
        assertThat(responses.stream().filter(r -> r.status() == 201).map(r -> r.body().get("id").asString())
                .distinct().count()).isEqualTo(20);
        assertStock(merchant, "HOODIE", 0, 20);
    }

    @Test
    void multiUnitRequestsNeverOversellOrPartiallyReserve() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"BUNDLE\", \"initialQuantity\": 20}");

        List<Response> responses = reserveConcurrently(merchant, "BUNDLE", 3, 200);

        // 6 x 3 = 18 units fit; the remaining 2 units cannot satisfy any 3-unit request.
        assertThat(countOutcomes(responses))
                .containsOnly(Map.entry("201", 6L), Map.entry("409 INSUFFICIENT_STOCK", 194L));
        assertStock(merchant, "BUNDLE", 2, 18);
    }

    private List<Response> reserveConcurrently(Merchant merchant, String sku, int quantity, int requests)
            throws Exception {
        String body = "{\"sku\": \"" + sku + "\", \"quantity\": " + quantity + "}";
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post(merchant.apiKey(), "/v1/reservations", body, newKey());
                }));
            }
            ready.await();
            start.countDown();
        }
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get());
        }
        return responses;
    }

    private static Map<String, Long> countOutcomes(List<Response> responses) {
        Function<Response, String> outcome = r -> r.status() == 201
                ? "201"
                : r.status() + " " + (r.body() == null ? "" : r.body().path("code").asString());
        return responses.stream().collect(Collectors.groupingBy(outcome, Collectors.counting()));
    }

    private void assertStock(Merchant merchant, String sku, int available, int held) throws Exception {
        Response stock = get(merchant.apiKey(), "/v1/skus/" + sku);
        assertThat(stock.body().get("initialQuantity").asInt()).isEqualTo(20);
        assertThat(stock.body().get("available").asInt()).isEqualTo(available);
        assertThat(stock.body().get("held").asInt()).isEqualTo(held);
        assertThat(stock.body().get("sold").asInt()).isEqualTo(0);
    }
}
