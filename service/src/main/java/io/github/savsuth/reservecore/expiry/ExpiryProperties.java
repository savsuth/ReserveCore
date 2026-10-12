package io.github.savsuth.reservecore.expiry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Expiration worker settings: delay between passes, reservations per batch transaction, and the most
 * batches one pass runs before yielding to the next tick.
 */
@ConfigurationProperties("reservecore.expiry")
public record ExpiryProperties(Duration interval, int batchSize, int maxBatchesPerTick) {

    public ExpiryProperties {
        if (batchSize < 1 || maxBatchesPerTick < 1) {
            throw new IllegalArgumentException("reservecore.expiry batch-size and max-batches-per-tick must be >= 1");
        }
    }
}
