package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReservationLifecycleApiTest extends ApiTestSupport {

    @Test
    void confirmationTurnsHeldUnitsIntoSoldUnits() throws Exception {
        Merchant merchant = newMerchant();
        String reservation = reserve(merchant, "TEE", 5, 2);

        Response confirmed = post(merchant.apiKey(), reservation + "/confirm", "", newKey());

        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body().get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.body().get("resolvedAt").isNull()).isFalse();
        assertThat(get(merchant.apiKey(), reservation).body()).isEqualTo(confirmed.body());
        assertStock(merchant, "TEE", 3, 0, 2);
    }

    @Test
    void cancellationReturnsHeldUnitsToAvailable() throws Exception {
        Merchant merchant = newMerchant();
        String reservation = reserve(merchant, "TEE", 5, 2);

        Response cancelled = post(merchant.apiKey(), reservation + "/cancel", "", newKey());

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(get(merchant.apiKey(), reservation).body()).isEqualTo(cancelled.body());
        assertStock(merchant, "TEE", 5, 0, 0);
    }

    @Test
    void repeatedConfirmationAppliesOnce() throws Exception {
        Merchant merchant = newMerchant();
        String reservation = reserve(merchant, "MUG", 5, 2);
        String key = newKey();

        Response first = post(merchant.apiKey(), reservation + "/confirm", "", key);
        Response sameKey = post(merchant.apiKey(), reservation + "/confirm", "", key);
        Response newKey = post(merchant.apiKey(), reservation + "/confirm", "", newKey());
        Response cancelAfter = post(merchant.apiKey(), reservation + "/cancel", "", newKey());

        assertThat(first.status()).isEqualTo(200);
        assertThat(sameKey.status()).isEqualTo(200);
        assertThat(sameKey.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(sameKey.body()).isEqualTo(first.body());
        assertInvalidTransition(newKey, "CONFIRMED");
        assertInvalidTransition(cancelAfter, "CONFIRMED");
        assertStock(merchant, "MUG", 3, 0, 2);
    }

    @Test
    void repeatedCancellationAppliesOnce() throws Exception {
        Merchant merchant = newMerchant();
        String reservation = reserve(merchant, "MUG", 5, 2);
        String key = newKey();

        Response first = post(merchant.apiKey(), reservation + "/cancel", "", key);
        Response sameKey = post(merchant.apiKey(), reservation + "/cancel", "", key);
        Response newKey = post(merchant.apiKey(), reservation + "/cancel", "", newKey());
        Response confirmAfter = post(merchant.apiKey(), reservation + "/confirm", "", newKey());

        assertThat(first.status()).isEqualTo(200);
        assertThat(sameKey.status()).isEqualTo(200);
        assertThat(sameKey.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(sameKey.body()).isEqualTo(first.body());
        assertInvalidTransition(newKey, "CANCELLED");
        assertInvalidTransition(confirmAfter, "CANCELLED");
        assertStock(merchant, "MUG", 5, 0, 0);
    }

    @Test
    void retryAfterLostConfirmResponseReplaysIt() throws Exception {
        Merchant merchant = newMerchant();
        String reservation = reserve(merchant, "CAP", 3, 1);
        String key = newKey();

        Response lost = post(merchant.apiKey(), reservation + "/confirm", "", key);
        Response retry = post(merchant.apiKey(), reservation + "/confirm", "", key);

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.body()).isEqualTo(lost.body());
        assertStock(merchant, "CAP", 2, 0, 1);
    }

    @Test
    void firstConfirmOrCancelAfterExpiryExpiresTheHoldAndReleasesItsUnits() throws Exception {
        Merchant merchant = newMerchant();
        String reservation = reserve(merchant, "LATE", 4, 1);
        // Database time decides expiry; move expires_at into the past instead of sleeping.
        jdbc.sql("UPDATE reservation SET expires_at = clock_timestamp() - interval '1 millisecond' WHERE id = :id")
                .param("id", java.util.UUID.fromString(reservation.substring(reservation.lastIndexOf('/') + 1)))
                .update();

        Response confirm = post(merchant.apiKey(), reservation + "/confirm", "", newKey());
        Response cancel = post(merchant.apiKey(), reservation + "/cancel", "", newKey());

        assertThat(confirm.status()).isEqualTo(409);
        assertThat(confirm.body().get("code").asString()).isEqualTo("RESERVATION_EXPIRED");
        // The confirm already expired it, so the cancel finds a final state.
        assertInvalidTransition(cancel, "EXPIRED");
        Response current = get(merchant.apiKey(), reservation);
        assertThat(current.body().get("status").asString()).isEqualTo("EXPIRED");
        assertThat(current.body().get("reclaimPending").asBoolean()).isFalse();
        assertThat(current.body().get("resolvedAt").isNull()).isFalse();
        assertStock(merchant, "LATE", 4, 0, 0);
        // The release is in the ledger, attributed to the confirm that found the hold overdue.
        tools.jackson.databind.JsonNode latest = get(merchant.apiKey(), "/v1/skus/LATE/movements").body().get("items").get(0);
        assertThat(latest.get("kind").asString()).isEqualTo("EXPIRED");
        assertThat(latest.get("requestId").asString()).isEqualTo(confirm.header("X-Request-Id"));
    }

    @Test
    void unknownAndOtherMerchantsReservationsAreNotFound() throws Exception {
        Merchant alice = newMerchant();
        Merchant bob = newMerchant();
        String alicesReservation = reserve(alice, "ALICE", 2, 1);

        for (String path : new String[] {
                alicesReservation + "/confirm", alicesReservation + "/cancel",
                "/v1/reservations/00000000-0000-0000-0000-000000000000/confirm",
                "/v1/reservations/not-a-uuid/cancel"}) {
            Response response = post(bob.apiKey(), path, "", newKey());
            assertThat(response.status()).as(path).isEqualTo(404);
            assertThat(response.body().get("code").asString()).as(path).isEqualTo("RESERVATION_NOT_FOUND");
        }
        assertThat(get(alice.apiKey(), alicesReservation).body().get("status").asString()).isEqualTo("ACTIVE");
        assertStock(alice, "ALICE", 1, 1, 0);
    }

    @Test
    void confirmAndCancelRequireAnIdempotencyKey() throws Exception {
        Merchant merchant = newMerchant();
        String reservation = reserve(merchant, "KEY", 2, 1);

        for (String action : new String[] {"/confirm", "/cancel"}) {
            Response response = post(merchant.apiKey(), reservation + action, "", null);
            assertThat(response.status()).as(action).isEqualTo(400);
            assertThat(response.body().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_MISSING");
        }
        assertStock(merchant, "KEY", 1, 1, 0);
    }

    /** Creates the SKU, reserves {@code quantity} units, and returns the reservation path. */
    private String reserve(Merchant merchant, String sku, int stock, int quantity) throws Exception {
        assertThat(post(merchant.apiKey(), "/v1/skus",
                "{\"sku\": \"" + sku + "\", \"initialQuantity\": " + stock + "}").status()).isEqualTo(201);
        Response reserved = post(merchant.apiKey(), "/v1/reservations",
                "{\"sku\": \"" + sku + "\", \"quantity\": " + quantity + "}", newKey());
        assertThat(reserved.status()).isEqualTo(201);
        return reserved.header("Location");
    }

    private static void assertInvalidTransition(Response response, String currentStatus) {
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("code").asString()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(response.body().get("currentStatus").asString()).isEqualTo(currentStatus);
        assertThat(response.header("Idempotent-Replayed")).isNull();
    }

    private void assertStock(Merchant merchant, String sku, int available, int held, int sold) throws Exception {
        Response stock = get(merchant.apiKey(), "/v1/skus/" + sku);
        assertThat(stock.body().get("available").asInt()).as("available").isEqualTo(available);
        assertThat(stock.body().get("held").asInt()).as("held").isEqualTo(held);
        assertThat(stock.body().get("sold").asInt()).as("sold").isEqualTo(sold);
    }
}
