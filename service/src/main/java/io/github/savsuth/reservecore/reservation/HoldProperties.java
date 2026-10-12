package io.github.savsuth.reservecore.reservation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reservation hold duration in seconds: used when a request omits holdSeconds, and the accepted range. */
@ConfigurationProperties("reservecore.hold")
public record HoldProperties(int defaultSeconds, int minSeconds, int maxSeconds) {

    public HoldProperties {
        if (minSeconds < 1 || minSeconds > maxSeconds || defaultSeconds < minSeconds || defaultSeconds > maxSeconds) {
            throw new IllegalArgumentException("reservecore.hold needs 1 <= min-seconds <= default-seconds <= max-seconds");
        }
    }
}
