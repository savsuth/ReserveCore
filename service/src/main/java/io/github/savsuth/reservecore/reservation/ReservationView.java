package io.github.savsuth.reservecore.reservation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record ReservationView(
        UUID id, String sku, int quantity,
        @Schema(description = "ACTIVE, CONFIRMED, CANCELLED or EXPIRED. An ACTIVE hold at or past expiresAt reads as "
                + "EXPIRED because it can no longer be confirmed or cancelled.")
        String status,
        Instant expiresAt, Instant createdAt, Instant resolvedAt,
        @Schema(description = "True while an overdue hold's units are still held, until the expiration worker or the "
                + "next confirm or cancel releases them.")
        boolean reclaimPending) {}
