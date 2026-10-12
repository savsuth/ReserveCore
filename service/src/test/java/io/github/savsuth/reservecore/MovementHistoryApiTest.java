package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MovementHistoryApiTest extends ApiTestSupport {

    @Test
    void historyRecordsEveryStockChangeNewestFirst() throws Exception {
        Merchant merchant = newMerchant();
        Response created = post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"AUDIT\", \"initialQuantity\": 10}");
        Response first = post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"AUDIT\", \"quantity\": 2}", newKey());
        Response second = post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"AUDIT\", \"quantity\": 3}", newKey());
        Response confirmed = post(merchant.apiKey(), first.header("Location") + "/confirm", "", newKey());
        Response cancelled = post(merchant.apiKey(), second.header("Location") + "/cancel", "", newKey());

        Response history = get(merchant.apiKey(), "/v1/skus/AUDIT/movements");

        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("nextBefore").isNull()).isTrue();
        List<JsonNode> items = items(history);
        assertThat(items).hasSize(5);
        assertMovement(items.get(0), "CANCELLED", 3, 8, 0, 2, second, cancelled);
        assertMovement(items.get(1), "CONFIRMED", 2, 5, 3, 2, first, confirmed);
        assertMovement(items.get(2), "RESERVED", 3, 5, 5, 0, second, second);
        assertMovement(items.get(3), "RESERVED", 2, 8, 2, 0, first, first);
        assertMovement(items.get(4), "STOCK_CREATED", 10, 10, 0, 0, null, created);
    }

    @Test
    void historyPagesWithABeforeCursor() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"PAGED\", \"initialQuantity\": 100}");
        for (int i = 0; i < 6; i++) {
            post(merchant.apiKey(), "/v1/reservations", "{\"sku\": \"PAGED\", \"quantity\": 1}", newKey());
        }
        List<Long> all = ids(items(get(merchant.apiKey(), "/v1/skus/PAGED/movements?limit=200")));

        Response page1 = get(merchant.apiKey(), "/v1/skus/PAGED/movements?limit=3");
        Response page2 = get(merchant.apiKey(),
                "/v1/skus/PAGED/movements?limit=3&before=" + page1.body().get("nextBefore").asLong());
        Response page3 = get(merchant.apiKey(),
                "/v1/skus/PAGED/movements?limit=3&before=" + page2.body().get("nextBefore").asLong());

        assertThat(all).hasSize(7).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(items(page1)).hasSize(3);
        assertThat(items(page2)).hasSize(3);
        assertThat(items(page3)).hasSize(1);
        assertThat(page3.body().get("nextBefore").isNull()).isTrue();
        List<Long> paged = new ArrayList<>(ids(items(page1)));
        paged.addAll(ids(items(page2)));
        paged.addAll(ids(items(page3)));
        assertThat(paged).isEqualTo(all);
    }

    @Test
    void historyRejectsBadParametersAndOtherMerchantsSkus() throws Exception {
        Merchant alice = newMerchant();
        Merchant bob = newMerchant();
        post(alice.apiKey(), "/v1/skus", "{\"sku\": \"PRIVATE\", \"initialQuantity\": 1}");

        for (String query : new String[] {"limit=0", "limit=201", "limit=abc", "before=0", "before=-5"}) {
            Response response = get(alice.apiKey(), "/v1/skus/PRIVATE/movements?" + query);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.body().get("code").asString()).as(query).isEqualTo("VALIDATION_FAILED");
        }
        Response bobs = get(bob.apiKey(), "/v1/skus/PRIVATE/movements");
        assertThat(bobs.status()).isEqualTo(404);
        assertThat(bobs.body().get("code").asString()).isEqualTo("SKU_NOT_FOUND");
    }

    private static List<JsonNode> items(Response response) {
        List<JsonNode> items = new ArrayList<>();
        response.body().get("items").forEach(items::add);
        return items;
    }

    private static List<Long> ids(List<JsonNode> items) {
        return items.stream().map(item -> item.get("id").asLong()).toList();
    }

    private static void assertMovement(JsonNode movement, String kind, int quantity, int available, int held,
            int sold, Response reservation, Response cause) {
        assertThat(movement.get("kind").asString()).isEqualTo(kind);
        assertThat(movement.get("quantity").asInt()).as(kind).isEqualTo(quantity);
        assertThat(movement.get("availableAfter").asInt()).as(kind).isEqualTo(available);
        assertThat(movement.get("heldAfter").asInt()).as(kind).isEqualTo(held);
        assertThat(movement.get("soldAfter").asInt()).as(kind).isEqualTo(sold);
        if (reservation == null) {
            assertThat(movement.get("reservationId").isNull()).as(kind).isTrue();
        } else {
            assertThat(movement.get("reservationId").asString()).as(kind)
                    .isEqualTo(reservation.body().get("id").asString());
        }
        assertThat(movement.get("requestId").asString()).as(kind).isEqualTo(cause.header("X-Request-Id"));
        assertThat(movement.get("createdAt").asString()).isNotEmpty();
    }
}
