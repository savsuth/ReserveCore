package io.github.savsuth.reservecore.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How long idempotency records are kept, and how the purge removes older ones. */
@ConfigurationProperties("reservecore.idempotency")
public record IdempotencyProperties(Duration retention, int purgeBatchSize, Duration purgeInterval) {

    public IdempotencyProperties {
        if (retention.isNegative() || retention.isZero() || purgeBatchSize < 1) {
            throw new IllegalArgumentException("reservecore.idempotency needs retention > 0 and purge-batch-size >= 1");
        }
    }
}
