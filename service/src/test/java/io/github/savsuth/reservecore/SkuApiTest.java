package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SkuApiTest extends ApiTestSupport {

    @Test
    void createdSkuStartsWithAllStockAvailable() throws Exception {
        Merchant merchant = newMerchant();

        Response created = post(merchant.apiKey(), "/v1/skus", """
                {"sku": "HOODIE-M", "initialQuantity": 20}
                """);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).isEqualTo("/v1/skus/HOODIE-M");
        assertQuantities(created, 20, 20, 0, 0);
        assertThat(Instant.parse(created.body().get("createdAt").asString())).isBefore(Instant.now().plusSeconds(5));

        Response fetched = get(merchant.apiKey(), "/v1/skus/HOODIE-M");
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body().get("sku").asString()).isEqualTo("HOODIE-M");
        assertQuantities(fetched, 20, 20, 0, 0);
    }

    @Test
    void duplicateSkuIsRejectedAndOriginalIsUnchanged() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"MUG\", \"initialQuantity\": 5}");

        Response duplicate = post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"MUG\", \"initialQuantity\": 99}");

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().get("code").asString()).isEqualTo("SKU_ALREADY_EXISTS");
        assertQuantities(get(merchant.apiKey(), "/v1/skus/MUG"), 5, 5, 0, 0);
    }

    @Test
    void skuCodesAreScopedToTheMerchant() throws Exception {
        Merchant alice = newMerchant();
        Merchant bob = newMerchant();
        post(alice.apiKey(), "/v1/skus", "{\"sku\": \"CAP\", \"initialQuantity\": 3}");
        post(alice.apiKey(), "/v1/skus", "{\"sku\": \"ALICE-ONLY\", \"initialQuantity\": 3}");

        Response bobsCap = post(bob.apiKey(), "/v1/skus", "{\"sku\": \"CAP\", \"initialQuantity\": 7}");
        Response bobReadsAlice = get(bob.apiKey(), "/v1/skus/ALICE-ONLY");

        assertThat(bobsCap.status()).isEqualTo(201);
        assertQuantities(get(alice.apiKey(), "/v1/skus/CAP"), 3, 3, 0, 0);
        assertQuantities(get(bob.apiKey(), "/v1/skus/CAP"), 7, 7, 0, 0);
        assertThat(bobReadsAlice.status()).isEqualTo(404);
        assertThat(bobReadsAlice.body().get("code").asString()).isEqualTo("SKU_NOT_FOUND");
    }

    @Test
    void invalidQuantityIsRejected() throws Exception {
        Merchant merchant = newMerchant();

        for (String body : new String[] {
                "{\"sku\": \"Q\", \"initialQuantity\": 0}",
                "{\"sku\": \"Q\", \"initialQuantity\": -5}",
                "{\"sku\": \"Q\"}"}) {
            Response response = post(merchant.apiKey(), "/v1/skus", body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("code").asString()).as(body).isEqualTo("INVALID_QUANTITY");
        }
        for (String body : new String[] {
                "{\"sku\": \"Q\", \"initialQuantity\": 1.5}",
                "{\"sku\": \"Q\", \"initialQuantity\": \"ten\"}",
                "{\"sku\": \"Q\", \"initialQuantity\": 3000000000}"}) {
            Response response = post(merchant.apiKey(), "/v1/skus", body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("code").asString()).as(body).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(get(merchant.apiKey(), "/v1/skus/Q").status()).isEqualTo(404);
    }

    @Test
    void invalidSkuCodeOrBodyIsRejected() throws Exception {
        Merchant merchant = newMerchant();

        for (String body : new String[] {
                "{\"sku\": \"has space\", \"initialQuantity\": 1}",
                "{\"sku\": \"" + "X".repeat(65) + "\", \"initialQuantity\": 1}",
                "{\"initialQuantity\": 1}",
                "{not json"}) {
            Response response = post(merchant.apiKey(), "/v1/skus", body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("code").asString()).as(body).isEqualTo("VALIDATION_FAILED");
            assertThat(response.body().get("requestId").asString()).isEqualTo(response.header("X-Request-Id"));
        }
    }

    private static void assertQuantities(Response response, int initial, int available, int held, int sold) {
        assertThat(response.body().get("initialQuantity").asInt()).isEqualTo(initial);
        assertThat(response.body().get("available").asInt()).isEqualTo(available);
        assertThat(response.body().get("held").asInt()).isEqualTo(held);
        assertThat(response.body().get("sold").asInt()).isEqualTo(sold);
    }
}
