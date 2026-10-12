package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.savsuth.reservecore.idempotency.IdempotencyPurger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyRetentionTest extends ApiTestSupport {

    @Autowired
    IdempotencyPurger purger;

    @Test
    void keysOlderThanRetentionArePurgedAndThenTreatedAsNew() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"OLD-KEYS\", \"initialQuantity\": 10}");
        String body = "{\"sku\": \"OLD-KEYS\", \"quantity\": 1}";
        String oldKey = newKey();
        String recentKey = newKey();
        Response original = post(merchant.apiKey(), "/v1/reservations", body, oldKey);
        Response recent = post(merchant.apiKey(), "/v1/reservations", body, recentKey);
        // Age one record past the 24 hour default retention (database time, no waiting).
        jdbc.sql("""
                UPDATE idempotency_record SET created_at = now() - interval '25 hours'
                WHERE merchant_id = :m AND idempotency_key = :k
                """).param("m", merchant.id()).param("k", oldKey).update();

        while (purger.purgeOnce() > 0) {
            // drain every expired record, including ones left by other tests
        }
        Response afterPurge = post(merchant.apiKey(), "/v1/reservations", body, oldKey);
        Response recentReplay = post(merchant.apiKey(), "/v1/reservations", body, recentKey);

        assertThat(afterPurge.status()).isEqualTo(201);
        assertThat(afterPurge.header("Idempotent-Replayed")).isNull();
        assertThat(afterPurge.body().get("id").asString()).isNotEqualTo(original.body().get("id").asString());
        assertThat(recentReplay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(recentReplay.body()).isEqualTo(recent.body());
        assertThat(get(merchant.apiKey(), "/v1/skus/OLD-KEYS").body().get("held").asInt()).isEqualTo(3);
    }
}
