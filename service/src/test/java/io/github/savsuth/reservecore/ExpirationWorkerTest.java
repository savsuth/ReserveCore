package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.savsuth.reservecore.expiry.ExpirationWorker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ExpirationWorkerTest extends ApiTestSupport {

    @Autowired
    ExpirationWorker worker;

    @Autowired
    MeterRegistry meters;

    @Test
    void workerExpiresDueReservationsAndReleasesTheirUnitsOnce() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"DUE\", \"initialQuantity\": 10}");
        String due1 = reserve(merchant, "DUE", 2);
        String due2 = reserve(merchant, "DUE", 3);
        expireAllActive(merchant);
        String notDue = reserve(merchant, "DUE", 1);
        String confirmed = reserve(merchant, "DUE", 1);
        post(merchant.apiKey(), confirmed + "/confirm", "", newKey());

        worker.runOnce();
        worker.runOnce();

        assertStatus(merchant, due1, "EXPIRED");
        assertStatus(merchant, due2, "EXPIRED");
        assertStatus(merchant, notDue, "ACTIVE");
        assertStatus(merchant, confirmed, "CONFIRMED");
        assertThat(get(merchant.apiKey(), due1).body().get("resolvedAt").isNull()).isFalse();
        assertStock(merchant, "DUE", 8, 1, 1);
        List<JsonNode> expired = movements(merchant, "DUE", "EXPIRED");
        assertThat(expired).hasSize(2);
        assertThat(expired.stream().mapToInt(m -> m.get("quantity").asInt()).sum()).isEqualTo(5);
        // The last EXPIRED movement written carries the SKU's final quantities.
        assertThat(expired.getFirst().get("availableAfter").asInt()).isEqualTo(8);
        assertThat(expired.getFirst().get("heldAfter").asInt()).isEqualTo(1);
    }

    @Test
    void confirmAfterTheWorkerExpiredTheReservationIsRejected() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"ORDER1\", \"initialQuantity\": 4}");
        String reservation = reserve(merchant, "ORDER1", 2);
        expireAllActive(merchant);

        worker.runOnce();
        Response confirm = post(merchant.apiKey(), reservation + "/confirm", "", newKey());

        assertThat(confirm.status()).isEqualTo(409);
        assertThat(confirm.body().get("code").asString()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(confirm.body().get("currentStatus").asString()).isEqualTo("EXPIRED");
        assertStock(merchant, "ORDER1", 4, 0, 0);
        assertThat(movements(merchant, "ORDER1", "EXPIRED")).hasSize(1);
    }

    @Test
    void confirmPastExpiryExpiresTheHoldItselfAndTheWorkerFindsNothingLeft() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"ORDER2\", \"initialQuantity\": 4}");
        String reservation = reserve(merchant, "ORDER2", 2);
        expireAllActive(merchant);

        Response confirm = post(merchant.apiKey(), reservation + "/confirm", "", newKey());
        assertReclaimed(merchant, reservation);
        assertStock(merchant, "ORDER2", 4, 0, 0);
        worker.runOnce();

        assertThat(confirm.status()).isEqualTo(409);
        assertThat(confirm.body().get("code").asString()).isEqualTo("RESERVATION_EXPIRED");
        assertStock(merchant, "ORDER2", 4, 0, 0);
        List<JsonNode> expired = movements(merchant, "ORDER2", "EXPIRED");
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().get("requestId").asString()).isEqualTo(confirm.header("X-Request-Id"));
        assertThat(movements(merchant, "ORDER2", "CONFIRMED")).isEmpty();
    }

    @Test
    void overdueHoldReadsAsExpiredUntilTheWorkerReclaimsIt() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"OVERDUE\", \"initialQuantity\": 3}");
        String overdue = reserve(merchant, "OVERDUE", 1);
        expireAllActive(merchant);
        String fresh = reserve(merchant, "OVERDUE", 1);

        Response before = get(merchant.apiKey(), overdue);
        assertThat(before.body().get("status").asString()).isEqualTo("EXPIRED");
        assertThat(before.body().get("reclaimPending").asBoolean()).isTrue();
        assertThat(before.body().get("resolvedAt").isNull()).isTrue();
        assertThat(get(merchant.apiKey(), fresh).body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(get(merchant.apiKey(), fresh).body().get("reclaimPending").asBoolean()).isFalse();
        assertStock(merchant, "OVERDUE", 1, 2, 0);

        while (worker.runOnce() > 0) {
            // drain everything due
        }

        assertReclaimed(merchant, overdue);
        assertThat(get(merchant.apiKey(), overdue).body().get("resolvedAt").isNull()).isFalse();
        assertStock(merchant, "OVERDUE", 2, 1, 0);
    }

    @Test
    void confirmedReservationIsLeftAloneOnceItsExpiryPasses() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"ORDER3\", \"initialQuantity\": 4}");
        String reservation = reserve(merchant, "ORDER3", 2);
        assertThat(post(merchant.apiKey(), reservation + "/confirm", "", newKey()).status()).isEqualTo(200);
        jdbc.sql("UPDATE reservation SET expires_at = clock_timestamp() - interval '1 second' WHERE id = :id")
                .param("id", idOf(reservation)).update();

        worker.runOnce();

        assertStatus(merchant, reservation, "CONFIRMED");
        assertStock(merchant, "ORDER3", 2, 0, 2);
        assertThat(movements(merchant, "ORDER3", "EXPIRED")).isEmpty();
    }

    @Test
    void confirmsRacingWorkersOnExpiredReservationsNeverSucceed() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"STALE\", \"initialQuantity\": 30}");
        List<String> reservations = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            reservations.add(reserve(merchant, "STALE", 1));
        }
        expireAllActive(merchant);

        List<Callable<Object>> calls = new ArrayList<>();
        for (String reservation : reservations) {
            calls.add(() -> post(merchant.apiKey(), reservation + "/confirm", "", newKey()));
        }
        for (int i = 0; i < 4; i++) {
            calls.add(worker::runOnce);
        }
        List<Object> results = runTogether(calls);
        worker.runOnce();

        for (Object result : results) {
            if (result instanceof Response confirm) {
                assertThat(confirm.status()).isEqualTo(409);
                assertThat(confirm.body().get("code").asString())
                        .isIn("RESERVATION_EXPIRED", "INVALID_STATE_TRANSITION");
            }
        }
        for (String reservation : reservations) {
            assertStatus(merchant, reservation, "EXPIRED");
        }
        assertStock(merchant, "STALE", 30, 0, 0);
        assertThat(movements(merchant, "STALE", "EXPIRED")).hasSize(30);
    }

    @Test
    void confirmsRacingWorkersAtTheExpiryBoundaryResolveEachReservationOnce() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"EDGE\", \"initialQuantity\": 30}");
        List<String> reservations = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            reservations.add(reserve(merchant, "EDGE", 1));
        }
        // Expiry lands while the confirms and worker passes below are in flight.
        jdbc.sql("""
                UPDATE reservation SET expires_at = clock_timestamp() + interval '30 milliseconds'
                WHERE merchant_id = :m
                """).param("m", merchant.id()).update();

        List<Callable<Object>> calls = new ArrayList<>();
        for (String reservation : reservations) {
            calls.add(() -> post(merchant.apiKey(), reservation + "/confirm", "", newKey()));
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        for (int i = 0; i < 4; i++) {
            calls.add(() -> {
                // Real passes, repeated until this merchant has nothing ACTIVE left (bounded, no sleep).
                while (activeCount(merchant) > 0) {
                    if (Instant.now().isAfter(deadline)) {
                        throw new AssertionError("reservations still ACTIVE after the deadline");
                    }
                    worker.runOnce();
                }
                return null;
            });
        }
        List<Object> results = runTogether(calls);

        int confirmed = 0;
        for (int i = 0; i < reservations.size(); i++) {
            Response confirm = (Response) results.get(i);
            String status = get(merchant.apiKey(), reservations.get(i)).body().get("status").asString();
            if (confirm.status() == 200) {
                confirmed++;
                assertThat(status).isEqualTo("CONFIRMED");
            } else {
                assertThat(confirm.status()).isEqualTo(409);
                assertThat(confirm.body().get("code").asString())
                        .isIn("RESERVATION_EXPIRED", "INVALID_STATE_TRANSITION");
                assertThat(status).isEqualTo("EXPIRED");
            }
        }
        System.out.printf("boundary race: %d confirmed, %d expired%n", confirmed, 30 - confirmed);
        assertStock(merchant, "EDGE", 30 - confirmed, 0, confirmed);
        assertThat(movements(merchant, "EDGE", "CONFIRMED")).hasSize(confirmed);
        assertThat(movements(merchant, "EDGE", "EXPIRED")).hasSize(30 - confirmed);
    }

    @Test
    void concurrentWorkersExpireEachReservationExactlyOnce() throws Exception {
        Merchant merchant = newMerchant();
        List<String> reservations = new ArrayList<>();
        for (String sku : new String[] {"FLEET-A", "FLEET-B"}) {
            post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"" + sku + "\", \"initialQuantity\": 150}");
            for (int i = 0; i < 100; i++) {
                reservations.add(reserve(merchant, sku, 1));
            }
        }
        expireAllActive(merchant);

        // Four "instances": each thread repeats passes until one finds nothing, each batch in its own transaction.
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> {
                int total = 0;
                for (int expired = worker.runOnce(); expired > 0; expired = worker.runOnce()) {
                    total += expired;
                }
                return total;
            });
        }
        List<Object> perWorker = runTogether(calls);
        System.out.println("expired per concurrent worker: " + perWorker);

        assertThat(activeCount(merchant)).isZero();
        for (String reservation : reservations) {
            assertStatus(merchant, reservation, "EXPIRED");
        }
        for (String sku : new String[] {"FLEET-A", "FLEET-B"}) {
            assertStock(merchant, sku, 150, 0, 0);
            List<JsonNode> expired = movements(merchant, sku, "EXPIRED");
            assertThat(expired).hasSize(100);
            assertThat(expired.stream().map(m -> m.get("reservationId").asString()).distinct()).hasSize(100);
            // 100 rows at batch size 10 cannot fit in one batch.
            assertThat(expired.stream().map(m -> m.get("requestId").asString()).distinct().count())
                    .isGreaterThan(1);
        }
    }

    @Test
    void failedBatchRollsBackEntirelyAndIsRetriedByTheNextPass() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"BROKEN\", \"initialQuantity\": 6}");
        List<String> reservations = List.of(reserve(merchant, "BROKEN", 1), reserve(merchant, "BROKEN", 2),
                reserve(merchant, "BROKEN", 3));
        while (worker.runOnce() > 0) {
            // a successful pass first, so the gauges hold values from it
        }
        long successfulPassEnded = System.nanoTime();
        double lagAfterSuccess = gauge("reservecore.expiry.lag");
        expireAllActive(merchant);
        double failuresBefore = meters.counter("reservecore.expiry.batch.failures").count();
        double expiredBefore = meters.counter("reservecore.expiry.expired").count();
        // Fail the last write of the batch: the EXPIRED movement of this merchant's largest reservation.
        jdbc.sql("""
                CREATE FUNCTION test_fail_expired_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.kind = 'EXPIRED' AND NEW.merchant_id = '%s' AND NEW.quantity = 3 THEN
                        RAISE EXCEPTION 'injected worker failure';
                    END IF;
                    RETURN NEW;
                END $$
                """.formatted(merchant.id())).update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_expired_movement BEFORE INSERT ON inventory_movement
                FOR EACH ROW EXECUTE FUNCTION test_fail_expired_movement()
                """).update();
        try {
            assertThatThrownBy(worker::runOnce).hasMessageContaining("injected worker failure");
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_expired_movement ON inventory_movement").update();
            jdbc.sql("DROP FUNCTION test_fail_expired_movement()").update();
        }

        assertThat(meters.counter("reservecore.expiry.batch.failures").count()).isEqualTo(failuresBefore + 1);
        for (String reservation : reservations) {
            // Still ACTIVE in the database: reads report it overdue and not yet reclaimed.
            Response current = get(merchant.apiKey(), reservation);
            assertThat(current.body().get("status").asString()).isEqualTo("EXPIRED");
            assertThat(current.body().get("reclaimPending").asBoolean()).as(reservation).isTrue();
        }
        assertStock(merchant, "BROKEN", 0, 6, 0);
        assertThat(movements(merchant, "BROKEN", "EXPIRED")).isEmpty();
        // A failed pass leaves the lag at the last successful pass's value and does not reset the age.
        assertThat(gauge("reservecore.expiry.lag")).isEqualTo(lagAfterSuccess);
        double elapsedSinceSuccess = (System.nanoTime() - successfulPassEnded) / 1e9;
        double ageAfterFailure = gauge("reservecore.expiry.last.success.age");
        assertThat(ageAfterFailure).isGreaterThanOrEqualTo(elapsedSinceSuccess);

        while (worker.runOnce() > 0) {
            // drain everything due, including rows from other tests
        }

        for (String reservation : reservations) {
            assertStatus(merchant, reservation, "EXPIRED");
        }
        assertStock(merchant, "BROKEN", 6, 0, 0);
        assertThat(movements(merchant, "BROKEN", "EXPIRED")).hasSize(3);
        assertThat(meters.counter("reservecore.expiry.expired").count()).isGreaterThanOrEqualTo(expiredBefore + 3);
        assertThat(gauge("reservecore.expiry.lag")).isZero();
        assertThat(gauge("reservecore.expiry.last.success.age")).isLessThan(ageAfterFailure);
    }

    @Test
    void lagReportsTheOldestDueReservationLeftAfterAPass() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"BACKLOG\", \"initialQuantity\": 150}");
        for (int i = 0; i < 150; i++) {
            reserve(merchant, "BACKLOG", 1);
        }
        jdbc.sql("UPDATE reservation SET expires_at = now() - interval '1 hour' WHERE merchant_id = :m")
                .param("m", merchant.id()).update();

        // One pass handles at most batch-size x max-batches-per-tick = 10 x 10 = 100 in tests.
        worker.runOnce();
        double lagWithBacklog = gauge("reservecore.expiry.lag");
        while (worker.runOnce() > 0) {
            // drain the rest
        }

        assertThat(lagWithBacklog).isGreaterThanOrEqualTo(3600.0);
        assertThat(gauge("reservecore.expiry.lag")).isZero();
        assertStock(merchant, "BACKLOG", 150, 0, 0);
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private int activeCount(Merchant merchant) {
        return jdbc.sql("SELECT count(*) FROM reservation WHERE merchant_id = :m AND status = 'ACTIVE'")
                .param("m", merchant.id()).query(Integer.class).single();
    }

    private static UUID idOf(String reservationPath) {
        return UUID.fromString(reservationPath.substring(reservationPath.lastIndexOf('/') + 1));
    }

    private static List<Object> runTogether(List<Callable<Object>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            ready.await();
            start.countDown();
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    String reserve(Merchant merchant, String sku, int quantity) throws Exception {
        Response response = post(merchant.apiKey(), "/v1/reservations",
                "{\"sku\": \"" + sku + "\", \"quantity\": " + quantity + "}", newKey());
        assertThat(response.status()).isEqualTo(201);
        return response.header("Location");
    }

    void assertReclaimed(Merchant merchant, String reservation) throws Exception {
        Response current = get(merchant.apiKey(), reservation);
        assertThat(current.body().get("status").asString()).as(reservation).isEqualTo("EXPIRED");
        assertThat(current.body().get("reclaimPending").asBoolean()).as(reservation).isFalse();
    }

    void assertStatus(Merchant merchant, String reservation, String status) throws Exception {
        assertThat(get(merchant.apiKey(), reservation).body().get("status").asString()).as(reservation)
                .isEqualTo(status);
    }

    void assertStock(Merchant merchant, String sku, int available, int held, int sold) throws Exception {
        Response stock = get(merchant.apiKey(), "/v1/skus/" + sku);
        assertThat(stock.body().get("available").asInt()).as("available").isEqualTo(available);
        assertThat(stock.body().get("held").asInt()).as("held").isEqualTo(held);
        assertThat(stock.body().get("sold").asInt()).as("sold").isEqualTo(sold);
    }

    /** Movements of one kind, newest first, read through the history API. */
    List<JsonNode> movements(Merchant merchant, String sku, String kind) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        String path = "/v1/skus/" + sku + "/movements?limit=200";
        while (path != null) {
            Response page = get(merchant.apiKey(), path);
            page.body().get("items").forEach(item -> {
                if (item.get("kind").asString().equals(kind)) {
                    result.add(item);
                }
            });
            JsonNode next = page.body().get("nextBefore");
            path = next.isNull() ? null : "/v1/skus/" + sku + "/movements?limit=200&before=" + next.asLong();
        }
        return result;
    }
}
