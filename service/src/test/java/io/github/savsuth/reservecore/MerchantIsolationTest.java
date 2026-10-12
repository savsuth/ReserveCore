package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Every merchant-scoped endpoint, called by another merchant with Alice's identifiers: always 404 (existence
 * is not revealed), and nothing of Alice's changes.
 */
class MerchantIsolationTest extends ApiTestSupport {

    @Test
    void anotherMerchantCannotReadOrChangeAnything() throws Exception {
        Merchant alice = newMerchant();
        Merchant bob = newMerchant();
        post(alice.apiKey(), "/v1/skus", "{\"sku\": \"ALICE-ITEM\", \"initialQuantity\": 5}");
        String reservation = post(alice.apiKey(), "/v1/reservations",
                "{\"sku\": \"ALICE-ITEM\", \"quantity\": 2}", newKey()).header("Location");
        Response aliceStockBefore = get(alice.apiKey(), "/v1/skus/ALICE-ITEM");
        Response aliceHistoryBefore = get(alice.apiKey(), "/v1/skus/ALICE-ITEM/movements");
        Response aliceReservationBefore = get(alice.apiKey(), reservation);

        assertNotFound(get(bob.apiKey(), "/v1/skus/ALICE-ITEM"), "SKU_NOT_FOUND");
        assertNotFound(get(bob.apiKey(), "/v1/skus/ALICE-ITEM/movements"), "SKU_NOT_FOUND");
        assertNotFound(post(bob.apiKey(), "/v1/reservations",
                "{\"sku\": \"ALICE-ITEM\", \"quantity\": 1}", newKey()), "SKU_NOT_FOUND");
        assertNotFound(get(bob.apiKey(), reservation), "RESERVATION_NOT_FOUND");
        assertNotFound(post(bob.apiKey(), reservation + "/confirm", "", newKey()), "RESERVATION_NOT_FOUND");
        assertNotFound(post(bob.apiKey(), reservation + "/cancel", "", newKey()), "RESERVATION_NOT_FOUND");

        assertThat(get(alice.apiKey(), "/v1/skus/ALICE-ITEM").body()).isEqualTo(aliceStockBefore.body());
        assertThat(get(alice.apiKey(), "/v1/skus/ALICE-ITEM/movements").body()).isEqualTo(aliceHistoryBefore.body());
        assertThat(get(alice.apiKey(), reservation).body()).isEqualTo(aliceReservationBefore.body());
    }

    @Test
    void sameSkuCodeAndIdempotencyKeyAreIndependentPerMerchant() throws Exception {
        Merchant alice = newMerchant();
        Merchant bob = newMerchant();
        String key = newKey();
        post(alice.apiKey(), "/v1/skus", "{\"sku\": \"SHARED-CODE\", \"initialQuantity\": 5}");
        post(bob.apiKey(), "/v1/skus", "{\"sku\": \"SHARED-CODE\", \"initialQuantity\": 5}");

        Response alices = post(alice.apiKey(), "/v1/reservations", "{\"sku\": \"SHARED-CODE\", \"quantity\": 1}", key);
        Response bobs = post(bob.apiKey(), "/v1/reservations", "{\"sku\": \"SHARED-CODE\", \"quantity\": 1}", key);

        assertThat(alices.status()).isEqualTo(201);
        assertThat(bobs.status()).isEqualTo(201);
        assertThat(bobs.header("Idempotent-Replayed")).isNull();
        assertThat(bobs.body().get("id")).isNotEqualTo(alices.body().get("id"));
        assertThat(get(alice.apiKey(), "/v1/skus/SHARED-CODE").body().get("held").asInt()).isEqualTo(1);
        assertThat(get(bob.apiKey(), "/v1/skus/SHARED-CODE").body().get("held").asInt()).isEqualTo(1);
    }

    private static void assertNotFound(Response response, String code) {
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().get("code").asString()).isEqualTo(code);
    }
}
