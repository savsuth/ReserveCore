package io.github.savsuth.reservecore.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of a SKU's movement history, newest first. {@code nextBefore} is the cursor for the next
 * (older) page, or null when this is the last page.
 */
public record MovementPage(List<Movement> items, Long nextBefore) {

    /** One stock change and the SKU's quantities right after it. */
    public record Movement(long id, String kind, int quantity, UUID reservationId, int availableAfter,
            int heldAfter, int soldAfter, String requestId, Instant createdAt) {}
}
