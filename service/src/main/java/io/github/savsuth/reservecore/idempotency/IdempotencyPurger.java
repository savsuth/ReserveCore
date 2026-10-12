package io.github.savsuth.reservecore.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes idempotency records older than {@code reservecore.idempotency.retention} in bounded batches.
 * After deletion a key is treated as new. Safe on every instance: concurrent deletes of the same rows
 * just find nothing left to delete.
 */
@Component
public class IdempotencyPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurger.class);

    private final JdbcClient jdbc;
    private final IdempotencyProperties properties;

    public IdempotencyPurger(JdbcClient jdbc, IdempotencyProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${reservecore.idempotency.purge-interval}")
    void scheduledPurge() {
        try {
            int deleted;
            do {
                deleted = purgeOnce();
            } while (deleted == properties.purgeBatchSize());
        } catch (RuntimeException e) {
            log.error("Idempotency purge failed; the next run retries", e);
        }
    }

    /** Deletes up to one batch of expired records (one statement, its own transaction). */
    public int purgeOnce() {
        int deleted = jdbc.sql("""
                DELETE FROM idempotency_record
                WHERE (merchant_id, operation, idempotency_key) IN (
                    SELECT merchant_id, operation, idempotency_key FROM idempotency_record
                    WHERE created_at < now() - make_interval(secs => :retention)
                    ORDER BY created_at
                    LIMIT :batch)
                """).param("retention", properties.retention().toSeconds())
                .param("batch", properties.purgeBatchSize()).update();
        if (deleted > 0) {
            log.info("Purged {} idempotency records older than {}", deleted, properties.retention());
        }
        return deleted;
    }
}
