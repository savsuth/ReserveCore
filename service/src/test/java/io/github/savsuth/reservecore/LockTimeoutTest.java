package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * API transactions wait at most {@code reservecore.lock-timeout} (2 s) for a row lock. The lock is held by a
 * separate connection that the test controls, so the request really waits; no sleeps.
 */
class LockTimeoutTest extends ApiTestSupport {

    @Autowired
    DataSource dataSource;

    @Test
    void reservationBlockedByAHeldStockRowFailsFastWith503AndCanBeRetried() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"LOCKED\", \"initialQuantity\": 5}");
        String key = newKey();
        String body = "{\"sku\": \"LOCKED\", \"quantity\": 1}";

        Response blocked;
        Duration waited;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(holder, "SELECT id FROM inventory WHERE merchant_id = ? AND sku = 'LOCKED' FOR UPDATE", merchant.id());
            long start = System.nanoTime();
            blocked = post(merchant.apiKey(), "/v1/reservations", body, key);
            waited = Duration.ofNanos(System.nanoTime() - start);
            holder.rollback();
        }

        assertThat(blocked.status()).isEqualTo(503);
        assertThat(blocked.body().get("code").asString()).isEqualTo("SERVICE_BUSY");
        assertThat(blocked.header("Retry-After")).isEqualTo("1");
        assertThat(waited).isBetween(Duration.ofMillis(1500), Duration.ofMillis(4500));

        Response retry = post(merchant.apiKey(), "/v1/reservations", body, key);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isNull();
        Response stock = get(merchant.apiKey(), "/v1/skus/LOCKED");
        assertThat(stock.body().get("available").asInt()).isEqualTo(4);
        assertThat(stock.body().get("held").asInt()).isEqualTo(1);
    }

    @Test
    void confirmBlockedByAHeldReservationRowFailsFastWith503AndCanBeRetried() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"LOCKED-R\", \"initialQuantity\": 5}");
        String reservation = post(merchant.apiKey(), "/v1/reservations",
                "{\"sku\": \"LOCKED-R\", \"quantity\": 2}", newKey()).header("Location");
        UUID id = UUID.fromString(reservation.substring(reservation.lastIndexOf('/') + 1));
        String key = newKey();

        Response blocked;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(holder, "SELECT id FROM reservation WHERE id = ? FOR UPDATE", id);
            blocked = post(merchant.apiKey(), reservation + "/confirm", "", key);
            holder.rollback();
        }

        assertThat(blocked.status()).isEqualTo(503);
        assertThat(blocked.body().get("code").asString()).isEqualTo("SERVICE_BUSY");
        Response retry = post(merchant.apiKey(), reservation + "/confirm", "", key);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.header("Idempotent-Replayed")).isNull();
        assertThat(retry.body().get("status").asString()).isEqualTo("CONFIRMED");
    }

    private static void lock(Connection holder, String sql, UUID id) throws Exception {
        try (PreparedStatement statement = holder.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeQuery().close();
        }
    }
}
