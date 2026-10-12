package io.github.savsuth.reservecore.inventory;

import java.time.Instant;

/** Availability of one SKU. See docs/PLAN.md section 3 for what each quantity means. */
public record InventoryView(
        String sku, int initialQuantity, int available, int held, int sold, Instant createdAt, Instant updatedAt) {}
