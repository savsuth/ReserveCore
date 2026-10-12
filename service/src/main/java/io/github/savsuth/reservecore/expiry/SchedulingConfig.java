package io.github.savsuth.reservecore.expiry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduled workers (expiration, idempotency purge). Tests switch them off with
 * {@code reservecore.workers.enabled=false} and call the pass methods directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "reservecore.workers.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
