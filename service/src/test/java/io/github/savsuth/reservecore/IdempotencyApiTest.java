package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdempotencyApiTest extends ApiTestSupport {

    @Test
    void reservationRequiresAValidIdempotencyKey() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "KEYED", 5);
        String body = "{\"sku\": \"KEYED\", \"quantity\": 1}";

        Response missing = post(merchant.apiKey(), "/v1/reservations", body, null);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_MISSING");

        Response tooLong = post(merchant.apiKey(), "/v1/reservations", body, "k".repeat(256));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        assertStock(merchant, "KEYED", 5, 0, 0);
    }

    @Test
    void retryAfterLostResponseReplaysTheOriginalReservation() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "RETRY", 5);
        String key = newKey();
        String body = "{\"sku\": \"RETRY\", \"quantity\": 2}";

        // The client never sees this response (lost after commit); from the server's side the retry is identical.
        Response lost = post(merchant.apiKey(), "/v1/reservations", body, key);
        Response retry = post(merchant.apiKey(), "/v1/reservations", body, key);

        assertThat(lost.status()).isEqualTo(201);
        assertThat(lost.header("Idempotent-Replayed")).isNull();
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.header("Location")).isEqualTo(lost.header("Location"));
        assertThat(retry.body()).isEqualTo(lost.body());
        assertStock(merchant, "RETRY", 3, 2, 0);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "SHIRT", 5);
        createSku(merchant, "SOCKS", 5);
        String key = newKey();
        post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"SHIRT\", \"quantity\": 1}", key);

        for (String body : new String[] {
                "{\"sku\": \"SHIRT\", \"quantity\": 2}",
                "{\"sku\": \"SOCKS\", \"quantity\": 1}",
                "{\"sku\": \"SHIRT\", \"quantity\": 1, \"holdSeconds\": 30}"}) {
            Response conflict = post(merchant.apiKey(), "/v1/reservations", body, key);
            assertThat(conflict.status()).as(body).isEqualTo(409);
            assertThat(conflict.body().get("code").asString()).as(body).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertThat(conflict.header("Idempotent-Replayed")).isNull();
        }
        assertStock(merchant, "SHIRT", 4, 1, 0);
        assertStock(merchant, "SOCKS", 5, 0, 0);
    }

    @Test
    void keysAreScopedByMerchantAndOperation() throws Exception {
        Merchant alice = newMerchant();
        Merchant bob = newMerchant();
        createSku(alice, "SHARED", 5);
        createSku(bob, "SHARED", 5);
        String key = newKey();

        Response alicesReservation = post(alice.apiKey(), "/v1/reservations", "{\"sku\": \"SHARED\", \"quantity\": 1}", key);
        Response bobsReservation = post(bob.apiKey(), "/v1/reservations", "{\"sku\": \"SHARED\", \"quantity\": 3}", key);
        Response aliceConfirms = post(alice.apiKey(),
                alicesReservation.header("Location") + "/confirm", "", key);

        assertThat(bobsReservation.status()).isEqualTo(201);
        assertThat(bobsReservation.header("Idempotent-Replayed")).isNull();
        assertThat(aliceConfirms.status()).isEqualTo(200);
        assertThat(aliceConfirms.header("Idempotent-Replayed")).isNull();
        assertStock(alice, "SHARED", 4, 0, 1);
        assertStock(bob, "SHARED", 2, 3, 0);
    }

    @Test
    void businessRejectionIsRecordedAndReplayedEvenAfterStockFreesUp() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "LAST-ONE", 1);
        String body = "{\"sku\": \"LAST-ONE\", \"quantity\": 1}";
        Response first = post(merchant.apiKey(), "/v1/reservations", body, newKey());
        String rejectedKey = newKey();

        Response rejected = post(merchant.apiKey(), "/v1/reservations", body, rejectedKey);
        post(merchant.apiKey(), first.header("Location") + "/cancel", "", newKey());
        Response replayed = post(merchant.apiKey(), "/v1/reservations", body, rejectedKey);
        Response fresh = post(merchant.apiKey(), "/v1/reservations", body, newKey());

        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.body().get("code").asString()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(replayed.status()).isEqualTo(409);
        assertThat(replayed.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replayed.body()).isEqualTo(rejected.body());
        assertThat(fresh.status()).isEqualTo(201);
        assertStock(merchant, "LAST-ONE", 0, 1, 0);
    }

    @Test
    void concurrentRequestsWithOneKeyPerformOneReservation() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "RUSH", 10);
        String key = newKey();
        int requests = 50;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"RUSH\", \"quantity\": 2}", key);
                }));
            }
            ready.await();
            start.countDown();
        }
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get());
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(responses.stream().map(r -> r.body().get("id").asString()).distinct()).hasSize(1);
        assertThat(responses.stream().filter(r -> r.header("Idempotent-Replayed") == null)).hasSize(1);
        assertStock(merchant, "RUSH", 8, 2, 0);
    }

    private void createSku(Merchant merchant, String sku, int quantity) throws Exception {
        Response response = post(merchant.apiKey(), "/v1/skus",
                "{\"sku\": \"" + sku + "\", \"initialQuantity\": " + quantity + "}");
        assertThat(response.status()).isEqualTo(201);
    }

    private void assertStock(Merchant merchant, String sku, int available, int held, int sold) throws Exception {
        Response stock = get(merchant.apiKey(), "/v1/skus/" + sku);
        assertThat(stock.body().get("available").asInt()).as("available").isEqualTo(available);
        assertThat(stock.body().get("held").asInt()).as("held").isEqualTo(held);
        assertThat(stock.body().get("sold").asInt()).as("sold").isEqualTo(sold);
    }
}
