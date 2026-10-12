package io.github.savsuth.reservecore.reservation;

import io.github.savsuth.reservecore.web.ApiProblem;
import io.github.savsuth.reservecore.web.RequestIdFilter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation writes. Every write that touches a SKU's inventory row is ONE data-modifying statement (a CTE
 * that moves stock, writes the reservation and appends the ledger row), so the hot inventory row lock is held
 * for that statement plus the commit only. docs/ARCHITECTURE.md, "Hot path".
 */
@Service
public class ReservationService {

    private final JdbcClient jdbc;

    public ReservationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserves units in one transaction (inside the idempotency transaction). The conditional
     * {@code UPDATE ... WHERE available >= :q} is the check and the deduction in one: competitors serialize on the
     * row lock and each re-checks the committed value, and once stock is gone the UPDATE matches nothing without
     * waiting (PostgreSQL does not lock a row that fails the WHERE clause). Stock, reservation and ledger row are
     * written by this one statement. No lock-free precheck: measured A/B, it only added a round trip
     * (docs/BENCHMARK.md, "Hot-path A/B").
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiProblem.class)
    public ReservationView create(UUID merchantId, String sku, int quantity, int holdSeconds) {
        return jdbc.sql("""
                WITH inv AS (
                    UPDATE inventory
                    SET available = available - :q, held = held + :q, updated_at = now()
                    WHERE merchant_id = :m AND sku = :sku AND available >= :q
                    RETURNING id, available, held, sold
                ), t AS (
                    SELECT clock_timestamp() AS ts
                ), r AS (
                    INSERT INTO reservation (merchant_id, inventory_id, quantity, status, expires_at, created_at)
                    SELECT :m, inv.id, :q, 'ACTIVE', t.ts + make_interval(secs => :hold), t.ts FROM inv, t
                    RETURNING id, quantity, status, expires_at, created_at, resolved_at
                ), ledger AS (
                    INSERT INTO inventory_movement (merchant_id, inventory_id, reservation_id, kind, quantity,
                        available_after, held_after, sold_after, request_id)
                    SELECT :m, inv.id, r.id, 'RESERVED', :q, inv.available, inv.held, inv.sold, :requestId
                    FROM inv, r
                )
                SELECT r.id, :sku AS sku, r.quantity, r.status, r.expires_at, r.created_at, r.resolved_at,
                       false AS reclaim_pending
                FROM r
                """).param("m", merchantId).param("q", quantity).param("hold", holdSeconds)
                .param("sku", sku).param("requestId", MDC.get(RequestIdFilter.MDC_KEY))
                .query(ReservationView.class).optional()
                .orElseThrow(() -> notReservable(merchantId, sku, quantity));
    }

    /** Terminal states an API call can move an ACTIVE reservation to. */
    public enum Transition {
        CONFIRM("CONFIRMED"),
        CANCEL("CANCELLED");

        final String status;

        Transition(String status) {
            this.status = status;
        }
    }

    /**
     * Confirms or cancels in one transaction. Lock order: reservation row ({@code FOR UPDATE}), then the inventory
     * row inside the single resolving statement. Expiry is judged with {@code clock_timestamp()} in that statement,
     * which runs with the reservation lock already held, so time spent waiting for the lock counts.
     *
     * <p>Expire on touch: a hold found at or past {@code expires_at} is expired by this request (units released,
     * EXPIRED ledger row with this request's id) and the answer is 409 RESERVATION_EXPIRED; that state change is
     * committed together with the recorded rejection.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiProblem.class)
    public ReservationView transition(UUID merchantId, UUID id, Transition transition) {
        String status = jdbc.sql("SELECT status FROM reservation WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchantId).param("id", id).query(String.class).optional()
                .orElseThrow(ReservationService::reservationNotFound);
        if (!status.equals("ACTIVE")) {
            ApiProblem problem = new ApiProblem(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                    "Reservation is " + status + " and cannot be " + transition.status.toLowerCase() + ".");
            problem.getBody().setProperty("currentStatus", status);
            throw problem;
        }
        ReservationView resolved = jdbc.sql("""
                WITH r AS (
                    UPDATE reservation
                    SET status = CASE WHEN clock_timestamp() < expires_at THEN :target ELSE 'EXPIRED' END,
                        resolved_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :id AND status = 'ACTIVE'
                    RETURNING id, inventory_id, quantity, status, expires_at, created_at, resolved_at
                ), inv AS (
                    UPDATE inventory i
                    SET held = i.held - r.quantity,
                        sold = i.sold + CASE WHEN r.status = 'CONFIRMED' THEN r.quantity ELSE 0 END,
                        available = i.available + CASE WHEN r.status = 'CONFIRMED' THEN 0 ELSE r.quantity END,
                        updated_at = now()
                    FROM r
                    WHERE i.merchant_id = :m AND i.id = r.inventory_id
                    RETURNING i.sku, i.available, i.held, i.sold
                ), ledger AS (
                    INSERT INTO inventory_movement (merchant_id, inventory_id, reservation_id, kind, quantity,
                        available_after, held_after, sold_after, request_id)
                    SELECT :m, r.inventory_id, r.id, r.status, r.quantity, inv.available, inv.held, inv.sold,
                           :requestId
                    FROM r, inv
                )
                SELECT r.id, inv.sku, r.quantity, r.status, r.expires_at, r.created_at, r.resolved_at,
                       false AS reclaim_pending
                FROM r, inv
                """).param("target", transition.status).param("m", merchantId).param("id", id)
                .param("requestId", MDC.get(RequestIdFilter.MDC_KEY))
                .query(ReservationView.class).single();
        if (resolved.status().equals("EXPIRED")) {
            throw new ApiProblem(HttpStatus.CONFLICT, "RESERVATION_EXPIRED",
                    "Reservation expired and can no longer be " + transition.status.toLowerCase() + ".");
        }
        return resolved;
    }

    /**
     * Reads a reservation with its effective status: an ACTIVE hold at or past {@code expires_at} can no longer
     * be confirmed or cancelled, so it reads as EXPIRED with {@code reclaimPending} true until a worker pass or
     * the next confirm or cancel releases its units.
     */
    public ReservationView get(UUID merchantId, UUID id) {
        return jdbc.sql("""
                SELECT r.id, i.sku, r.quantity,
                       CASE WHEN r.status = 'ACTIVE' AND r.expires_at <= clock_timestamp() THEN 'EXPIRED'
                            ELSE r.status END AS status,
                       r.expires_at, r.created_at, r.resolved_at,
                       (r.status = 'ACTIVE' AND r.expires_at <= clock_timestamp()) AS reclaim_pending
                FROM reservation r JOIN inventory i ON i.merchant_id = r.merchant_id AND i.id = r.inventory_id
                WHERE r.merchant_id = :m AND r.id = :id
                """).param("m", merchantId).param("id", id)
                .query(ReservationView.class).optional()
                .orElseThrow(ReservationService::reservationNotFound);
    }

    static ApiProblem reservationNotFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation does not exist.");
    }

    /** After the conditional UPDATE matched nothing: unknown SKU (404) or not enough stock (409). */
    private ApiProblem notReservable(UUID merchantId, String sku, int quantity) {
        Integer available = jdbc.sql("SELECT available FROM inventory WHERE merchant_id = :m AND sku = :sku")
                .param("m", merchantId).param("sku", sku).query(Integer.class).optional().orElse(null);
        if (available == null) {
            return ApiProblem.skuNotFound(sku);
        }
        return new ApiProblem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Requested " + quantity + " of SKU " + sku + " but only " + available + " available.");
    }
}
