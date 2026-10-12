package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Conflicting transitions on the same reservation, released together by a latch (no sleeps). */
class ConcurrentTransitionTest extends ApiTestSupport {

    @Test
    void cancelRacingConfirmResolvesEachReservationExactlyOnce() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"RACE\", \"initialQuantity\": 40}");
        List<String> reservations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reservations.add(post(merchant.apiKey(), "/v1/reservations",
                    "{\"sku\": \"RACE\", \"quantity\": 2}", newKey()).header("Location"));
        }

        List<Callable<Response>> calls = new ArrayList<>();
        for (String reservation : reservations) {
            calls.add(() -> post(merchant.apiKey(), reservation + "/confirm", "", newKey()));
            calls.add(() -> post(merchant.apiKey(), reservation + "/cancel", "", newKey()));
        }
        List<Response> responses = runTogether(calls);

        int confirmed = 0;
        for (int i = 0; i < reservations.size(); i++) {
            Response confirm = responses.get(2 * i);
            Response cancel = responses.get(2 * i + 1);
            assertThat(List.of(confirm.status(), cancel.status())).as(reservations.get(i))
                    .containsExactlyInAnyOrder(200, 409);
            Response loser = confirm.status() == 409 ? confirm : cancel;
            assertThat(loser.body().get("code").asString()).isEqualTo("INVALID_STATE_TRANSITION");
            String finalStatus = get(merchant.apiKey(), reservations.get(i)).body().get("status").asString();
            assertThat(finalStatus).isEqualTo(confirm.status() == 200 ? "CONFIRMED" : "CANCELLED");
            if (confirm.status() == 200) {
                confirmed++;
            }
        }
        Response stock = get(merchant.apiKey(), "/v1/skus/RACE");
        assertThat(stock.body().get("held").asInt()).isZero();
        assertThat(stock.body().get("sold").asInt()).isEqualTo(2 * confirmed);
        assertThat(stock.body().get("available").asInt()).isEqualTo(40 - 2 * confirmed);
    }

    @Test
    void concurrentConfirmationsWithDifferentKeysConfirmOnce() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"ONCE\", \"initialQuantity\": 5}");
        String reservation = post(merchant.apiKey(), "/v1/reservations",
                "{\"sku\": \"ONCE\", \"quantity\": 3}", newKey()).header("Location");

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> post(merchant.apiKey(), reservation + "/confirm", "", newKey()));
        }
        List<Response> responses = runTogether(calls);

        assertThat(responses.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.status() == 409
                && r.body().get("code").asString().equals("INVALID_STATE_TRANSITION"))).hasSize(9);
        Response stock = get(merchant.apiKey(), "/v1/skus/ONCE");
        assertThat(stock.body().get("available").asInt()).isEqualTo(2);
        assertThat(stock.body().get("held").asInt()).isZero();
        assertThat(stock.body().get("sold").asInt()).isEqualTo(3);
    }

    private static List<Response> runTogether(List<Callable<Response>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<Response> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
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
}
