package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A failure at the last step of reservation creation (the movement insert) must roll back the stock
 * deduction and the reservation insert that ran before it. The failure is injected with a test-only
 * trigger scoped to one merchant. A failed request is not recorded for idempotency, so a retry with the
 * same key executes normally.
 */
class ReservationRollbackTest extends ApiTestSupport {

    @Test
    void failureAfterStockDeductionLeavesNoPartialChange() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"FRAGILE\", \"initialQuantity\": 4}");
        jdbc.sql("""
                CREATE FUNCTION test_fail_reserved_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.kind = 'RESERVED' AND NEW.merchant_id = '%s' THEN
                        RAISE EXCEPTION 'injected failure';
                    END IF;
                    RETURN NEW;
                END $$
                """.formatted(merchant.id())).update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_reserved_movement BEFORE INSERT ON inventory_movement
                FOR EACH ROW EXECUTE FUNCTION test_fail_reserved_movement()
                """).update();
        String key = newKey();
        String body = "{\"sku\": \"FRAGILE\", \"quantity\": 3}";
        Response failed;
        try {
            failed = post(merchant.apiKey(), "/v1/reservations", body, key);
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_reserved_movement ON inventory_movement").update();
            jdbc.sql("DROP FUNCTION test_fail_reserved_movement()").update();
        }

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body().get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.body().get("detail").asString()).doesNotContain("injected");
        assertThat(failed.body().get("requestId").asString()).isEqualTo(failed.header("X-Request-Id"));

        Response stock = get(merchant.apiKey(), "/v1/skus/FRAGILE");
        assertThat(stock.body().get("available").asInt()).isEqualTo(4);
        assertThat(stock.body().get("held").asInt()).isEqualTo(0);
        Response history = get(merchant.apiKey(), "/v1/skus/FRAGILE/movements");
        assertThat(history.body().get("items")).hasSize(1);
        assertThat(history.body().get("items").get(0).get("kind").asString()).isEqualTo("STOCK_CREATED");

        Response retry = post(merchant.apiKey(), "/v1/reservations", body, key);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isNull();
        Response stockAfterRetry = get(merchant.apiKey(), "/v1/skus/FRAGILE");
        assertThat(stockAfterRetry.body().get("available").asInt()).isEqualTo(1);
        assertThat(stockAfterRetry.body().get("held").asInt()).isEqualTo(3);
    }
}
