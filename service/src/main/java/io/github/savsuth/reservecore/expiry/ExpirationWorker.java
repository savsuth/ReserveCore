package io.github.savsuth.reservecore.expiry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves ACTIVE reservations at or past {@code expires_at} to EXPIRED and returns their units to available
 * (docs/PLAN.md section 8). Every app instance runs it; {@code FOR UPDATE SKIP LOCKED} hands concurrent
 * passes disjoint rows, and a row locked by a confirm or cancel is skipped until the next pass. Each batch
 * is one transaction: a failure rolls the whole batch back and the rows are picked up again later. There
 * are no leases and no in-memory timers.
 *
 * <p>Its gauges never query the database when read, so metrics stay available during a database outage:
 * the lag is measured at the end of each successful pass, and the age of the last successful pass is
 * computed in memory and keeps growing while passes fail or stop.
 */
@Component
public class ExpirationWorker {

    private static final Logger log = LoggerFactory.getLogger(ExpirationWorker.class);

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;
    private final TransactionTemplate transaction;
    private final ExpiryProperties properties;
    private final Counter expired;
    private final Counter failedBatches;
    private final Timer batchDuration;
    private volatile double lagSeconds;
    private volatile long lastSuccessNanos = System.nanoTime();

    public ExpirationWorker(JdbcClient jdbc, NamedParameterJdbcTemplate batch,
            PlatformTransactionManager transactionManager, ExpiryProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.expired = Counter.builder("reservecore.expiry.expired")
                .description("Reservations moved to EXPIRED by the worker").register(registry);
        this.failedBatches = Counter.builder("reservecore.expiry.batch.failures")
                .description("Expiration batches rolled back after an error").register(registry);
        this.batchDuration = Timer.builder("reservecore.expiry.batch.duration")
                .description("Time per expiration batch transaction").register(registry);
        Gauge.builder("reservecore.expiry.lag", this, worker -> worker.lagSeconds)
                .description("Age of the oldest ACTIVE reservation past expires_at, as of the last successful pass")
                .baseUnit("seconds").register(registry);
        Gauge.builder("reservecore.expiry.last.success.age", this,
                        worker -> (System.nanoTime() - worker.lastSuccessNanos) / 1e9)
                .description("Time since the last successful expiration pass (since startup before the first)")
                .baseUnit("seconds").register(registry);
    }

    @Scheduled(fixedDelayString = "${reservecore.expiry.interval}")
    void scheduledPass() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.debug("Expiration pass stopped after a failed batch; the next tick retries");
        }
    }

    /**
     * One worker pass: batches until one comes back short or {@code max-batches-per-tick} is reached.
     * A failed batch is counted, logged and rethrown; batches committed before it stay committed.
     *
     * @return reservations expired by this pass
     */
    public int runOnce() {
        int total = 0;
        for (int i = 0; i < properties.maxBatchesPerTick(); i++) {
            int count = expireBatch();
            total += count;
            if (count < properties.batchSize()) {
                break;
            }
        }
        lagSeconds = measureLag();
        lastSuccessNanos = System.nanoTime();
        return total;
    }

    private int expireBatch() {
        String batchId = UUID.randomUUID().toString();
        Timer.Sample sample = Timer.start();
        try {
            int count = transaction.execute(status -> expire(batchId));
            expired.increment(count);
            if (count > 0) {
                log.info("Expired {} reservations in batch {}", count, batchId);
            }
            return count;
        } catch (RuntimeException e) {
            failedBatches.increment();
            log.error("Expiration batch {} failed and was rolled back", batchId, e);
            throw e;
        } finally {
            sample.stop(batchDuration);
        }
    }

    private int expire(String batchId) {
        record Claimed(UUID id, UUID merchantId, UUID inventoryId, int quantity) {}
        List<Claimed> claimed = jdbc.sql("""
                WITH due AS (
                    SELECT id FROM reservation
                    WHERE status = 'ACTIVE' AND expires_at <= now()
                    ORDER BY expires_at
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE reservation r SET status = 'EXPIRED', resolved_at = now()
                FROM due WHERE r.id = due.id
                RETURNING r.id, r.merchant_id, r.inventory_id, r.quantity
                """).param("batch", properties.batchSize()).query(Claimed.class).list();

        // Lock order across concurrent workers: inventory rows in ascending id. UUID.toString() is lowercase
        // hex at fixed positions, so its string order matches PostgreSQL's uuid order.
        Map<String, List<Claimed>> byInventory = new TreeMap<>();
        for (Claimed c : claimed) {
            byInventory.computeIfAbsent(c.inventoryId().toString(), k -> new ArrayList<>()).add(c);
        }
        for (List<Claimed> group : byInventory.values()) {
            group.sort(Comparator.comparing(c -> c.id().toString()));
            Claimed first = group.getFirst();
            int released = group.stream().mapToInt(Claimed::quantity).sum();
            record Stock(int available, int held, int sold) {}
            Stock after = jdbc.sql("""
                    UPDATE inventory SET held = held - :q, available = available + :q, updated_at = now()
                    WHERE merchant_id = :m AND id = :i
                    RETURNING available, held, sold
                    """).param("q", released).param("m", first.merchantId()).param("i", first.inventoryId())
                    .query(Stock.class).single();
            // One movement per reservation, with the quantities as they stood after that reservation alone, sent
            // as one JDBC batch so the inventory row lock is not held across a round trip per reservation.
            int available = after.available() - released;
            int held = after.held() + released;
            MapSqlParameterSource[] rows = new MapSqlParameterSource[group.size()];
            for (int k = 0; k < group.size(); k++) {
                Claimed c = group.get(k);
                available += c.quantity();
                held -= c.quantity();
                rows[k] = new MapSqlParameterSource().addValue("m", c.merchantId()).addValue("i", c.inventoryId())
                        .addValue("r", c.id()).addValue("q", c.quantity()).addValue("available", available)
                        .addValue("held", held).addValue("sold", after.sold()).addValue("requestId", "expiry-" + batchId);
            }
            batch.batchUpdate("""
                    INSERT INTO inventory_movement (merchant_id, inventory_id, reservation_id, kind, quantity,
                        available_after, held_after, sold_after, request_id)
                    VALUES (:m, :i, :r, 'EXPIRED', :q, :available, :held, :sold, :requestId)
                    """, rows);
        }
        return claimed.size();
    }

    private double measureLag() {
        return jdbc.sql("""
                SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(expires_at)), 0)
                FROM reservation WHERE status = 'ACTIVE' AND expires_at <= now()
                """).query(Double.class).single();
    }
}
