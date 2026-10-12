package io.github.savsuth.reservecore.inventory;

import io.github.savsuth.reservecore.web.ApiProblem;
import io.github.savsuth.reservecore.web.RequestIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final JdbcClient jdbc;

    public InventoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the SKU and its STOCK_CREATED movement in one transaction. */
    @Transactional
    public InventoryView create(UUID merchantId, String sku, int initialQuantity) {
        record Created(UUID id, String sku, int initialQuantity, int available, int held, int sold,
                Instant createdAt, Instant updatedAt) {}
        Created row = jdbc.sql("""
                INSERT INTO inventory (merchant_id, sku, initial_quantity, available, held, sold)
                VALUES (:m, :sku, :q, :q, 0, 0)
                ON CONFLICT (merchant_id, sku) DO NOTHING
                RETURNING id, sku, initial_quantity, available, held, sold, created_at, updated_at
                """).param("m", merchantId).param("sku", sku).param("q", initialQuantity)
                .query(Created.class).optional()
                .orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS",
                        "SKU " + sku + " already exists."));
        jdbc.sql("""
                INSERT INTO inventory_movement
                    (merchant_id, inventory_id, kind, quantity, available_after, held_after, sold_after, request_id)
                VALUES (:m, :i, 'STOCK_CREATED', :q, :q, 0, 0, :requestId)
                """).param("m", merchantId).param("i", row.id()).param("q", initialQuantity)
                .param("requestId", MDC.get(RequestIdFilter.MDC_KEY)).update();
        return new InventoryView(row.sku(), row.initialQuantity(), row.available(), row.held(), row.sold(),
                row.createdAt(), row.updatedAt());
    }

    /** Keyset page over the SKU's movements: ids below {@code before}, newest first. */
    @Transactional(readOnly = true)
    public MovementPage movements(UUID merchantId, String sku, int limit, long before) {
        UUID inventoryId = jdbc.sql("SELECT id FROM inventory WHERE merchant_id = :m AND sku = :sku")
                .param("m", merchantId).param("sku", sku).query(UUID.class).optional()
                .orElseThrow(() -> ApiProblem.skuNotFound(sku));
        List<MovementPage.Movement> rows = jdbc.sql("""
                SELECT id, kind, quantity, reservation_id, available_after, held_after, sold_after, request_id,
                       created_at
                FROM inventory_movement
                WHERE merchant_id = :m AND inventory_id = :i AND id < :before
                ORDER BY id DESC
                LIMIT :fetch
                """).param("m", merchantId).param("i", inventoryId).param("before", before)
                .param("fetch", limit + 1)
                .query(MovementPage.Movement.class).list();
        if (rows.size() <= limit) {
            return new MovementPage(rows, null);
        }
        List<MovementPage.Movement> page = rows.subList(0, limit);
        return new MovementPage(page, page.getLast().id());
    }

    public InventoryView get(UUID merchantId, String sku) {
        return jdbc.sql("""
                SELECT sku, initial_quantity, available, held, sold, created_at, updated_at
                FROM inventory WHERE merchant_id = :m AND sku = :sku
                """).param("m", merchantId).param("sku", sku)
                .query(InventoryView.class).optional()
                .orElseThrow(() -> ApiProblem.skuNotFound(sku));
    }
}
