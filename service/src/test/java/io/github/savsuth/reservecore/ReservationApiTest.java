package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationApiTest extends ApiTestSupport {

    @Test
    void reservationMovesUnitsFromAvailableToHeld() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "HOODIE-M", 10);

        Response created = post(merchant.apiKey(), "/v1/reservations", """
                {"sku": "HOODIE-M", "quantity": 3, "holdSeconds": 60}
                """, newKey());

        assertThat(created.status()).isEqualTo(201);
        String id = created.body().get("id").asString();
        assertThat(created.header("Location")).isEqualTo("/v1/reservations/" + id);
        assertThat(created.body().get("sku").asString()).isEqualTo("HOODIE-M");
        assertThat(created.body().get("quantity").asInt()).isEqualTo(3);
        assertThat(created.body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.body().get("resolvedAt").isNull()).isTrue();
        assertThat(hold(created)).isEqualTo(Duration.ofSeconds(60));

        Response fetched = get(merchant.apiKey(), "/v1/reservations/" + id);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(created.body());

        Response stock = get(merchant.apiKey(), "/v1/skus/HOODIE-M");
        assertThat(stock.body().get("available").asInt()).isEqualTo(7);
        assertThat(stock.body().get("held").asInt()).isEqualTo(3);
        assertThat(stock.body().get("sold").asInt()).isEqualTo(0);
    }

    @Test
    void holdDefaultsTo600Seconds() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "MUG", 1);

        Response created = post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"MUG\", \"quantity\": 1}", newKey());

        assertThat(created.status()).isEqualTo(201);
        assertThat(hold(created)).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void requestBeyondAvailableStockIsRejectedWithoutChangingStock() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "CAP", 2);

        Response tooMany = post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"CAP\", \"quantity\": 3}", newKey());
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.body().get("code").asString()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(get(merchant.apiKey(), "/v1/skus/CAP").body().get("available").asInt()).isEqualTo(2);

        assertThat(post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"CAP\", \"quantity\": 2}", newKey()).status())
                .isEqualTo(201);
        Response soldOut = post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"CAP\", \"quantity\": 1}", newKey());
        assertThat(soldOut.status()).isEqualTo(409);
        assertThat(soldOut.body().get("code").asString()).isEqualTo("INSUFFICIENT_STOCK");

        Response stock = get(merchant.apiKey(), "/v1/skus/CAP");
        assertThat(stock.body().get("available").asInt()).isEqualTo(0);
        assertThat(stock.body().get("held").asInt()).isEqualTo(2);
    }

    @Test
    void unknownSkuAndOtherMerchantsResourcesAreNotFound() throws Exception {
        Merchant alice = newMerchant();
        Merchant bob = newMerchant();
        createSku(alice, "ALICE-SKU", 5);
        String alicesReservation = post(alice.apiKey(), "/v1/reservations",
                "{\"sku\": \"ALICE-SKU\", \"quantity\": 1}", newKey()).body().get("id").asString();

        Response unknownSku = post(bob.apiKey(), "/v1/reservations", "{\"sku\": \"ALICE-SKU\", \"quantity\": 1}", newKey());
        assertThat(unknownSku.status()).isEqualTo(404);
        assertThat(unknownSku.body().get("code").asString()).isEqualTo("SKU_NOT_FOUND");

        for (String id : new String[] {alicesReservation, "not-a-uuid", "00000000-0000-0000-0000-000000000000"}) {
            Response response = get(bob.apiKey(), "/v1/reservations/" + id);
            assertThat(response.status()).as(id).isEqualTo(404);
            assertThat(response.body().get("code").asString()).as(id).isEqualTo("RESERVATION_NOT_FOUND");
        }
        assertThat(get(alice.apiKey(), "/v1/skus/ALICE-SKU").body().get("held").asInt()).isEqualTo(1);
    }

    @Test
    void invalidReservationRequestsAreRejected() throws Exception {
        Merchant merchant = newMerchant();
        createSku(merchant, "TEE", 5);

        for (String body : new String[] {
                "{\"sku\": \"TEE\", \"quantity\": 0}",
                "{\"sku\": \"TEE\", \"quantity\": -1}",
                "{\"sku\": \"TEE\"}"}) {
            Response response = post(merchant.apiKey(), "/v1/reservations", body, newKey());
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("code").asString()).as(body).isEqualTo("INVALID_QUANTITY");
        }
        for (String body : new String[] {
                "{\"sku\": \"TEE\", \"quantity\": 1, \"holdSeconds\": 4}",
                "{\"sku\": \"TEE\", \"quantity\": 1, \"holdSeconds\": 3601}",
                "{\"quantity\": 1}",
                "{\"sku\": \"TEE\", \"quantity\": 1.5}"}) {
            Response response = post(merchant.apiKey(), "/v1/reservations", body, newKey());
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("code").asString()).as(body).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(get(merchant.apiKey(), "/v1/skus/TEE").body().get("available").asInt()).isEqualTo(5);
    }

    private void createSku(Merchant merchant, String sku, int quantity) throws Exception {
        Response response = post(merchant.apiKey(), "/v1/skus",
                "{\"sku\": \"" + sku + "\", \"initialQuantity\": " + quantity + "}");
        assertThat(response.status()).isEqualTo(201);
    }

    private static Duration hold(Response reservation) {
        return Duration.between(Instant.parse(reservation.body().get("createdAt").asString()),
                Instant.parse(reservation.body().get("expiresAt").asString()));
    }
}
