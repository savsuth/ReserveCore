-- ReserveCore inventory consistency check (docs/RUNBOOK.md, "Investigating an inventory discrepancy").
-- Read-only. Each check prints the number of offending rows; the script fails (non-zero psql exit with
-- ON_ERROR_STOP) when any check finds one. Usage:
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U reservecore -d reservecore < ops/consistency-check.sql

CREATE TEMP TABLE check_result (name text, offending bigint);

-- 1. Balance: initial = available + held + sold (also enforced by a CHECK constraint).
INSERT INTO check_result
SELECT 'balance: initial <> available + held + sold', count(*)
FROM inventory WHERE initial_quantity <> available + held + sold;

-- 2. held equals the units of ACTIVE reservations; sold equals the units of CONFIRMED reservations.
INSERT INTO check_result
SELECT 'held/sold differ from ACTIVE/CONFIRMED reservations', count(*) FROM (
    SELECT i.id
    FROM inventory i
    LEFT JOIN reservation r ON r.merchant_id = i.merchant_id AND r.inventory_id = i.id
    GROUP BY i.id, i.held, i.sold
    HAVING i.held <> coalesce(sum(r.quantity) FILTER (WHERE r.status = 'ACTIVE'), 0)
        OR i.sold <> coalesce(sum(r.quantity) FILTER (WHERE r.status = 'CONFIRMED'), 0)
) x;

-- 3. The newest movement of each SKU records the SKU's current quantities. Movements of one SKU are
--    written while its inventory row is locked, so id order is commit order per SKU.
INSERT INTO check_result
SELECT 'latest movement differs from current quantities', count(*)
FROM inventory i
LEFT JOIN LATERAL (
    SELECT available_after, held_after, sold_after FROM inventory_movement m
    WHERE m.inventory_id = i.id ORDER BY m.id DESC LIMIT 1
) m ON true
WHERE (i.available, i.held, i.sold) IS DISTINCT FROM (m.available_after, m.held_after, m.sold_after);

-- 4. Every reservation has exactly one RESERVED movement, and exactly one resolution movement matching
--    its status once it has left ACTIVE (none while ACTIVE).
INSERT INTO check_result
SELECT 'reservation without exactly one RESERVED and one matching resolution movement', count(*) FROM (
    SELECT r.id
    FROM reservation r
    LEFT JOIN inventory_movement m ON m.merchant_id = r.merchant_id AND m.reservation_id = r.id
    GROUP BY r.id, r.status
    HAVING count(*) FILTER (WHERE m.kind = 'RESERVED') <> 1
        OR count(*) FILTER (WHERE m.kind IN ('CONFIRMED', 'CANCELLED', 'EXPIRED'))
           <> CASE WHEN r.status = 'ACTIVE' THEN 0 ELSE 1 END
        OR count(*) FILTER (WHERE m.kind IN ('CONFIRMED', 'CANCELLED', 'EXPIRED') AND m.kind <> r.status) > 0
) x;

-- 5. Each SKU has exactly one STOCK_CREATED movement and it carries the initial quantity.
INSERT INTO check_result
SELECT 'missing or wrong STOCK_CREATED movement', count(*) FROM (
    SELECT i.id
    FROM inventory i
    LEFT JOIN inventory_movement m ON m.inventory_id = i.id AND m.kind = 'STOCK_CREATED'
    GROUP BY i.id, i.initial_quantity
    HAVING count(m.id) <> 1 OR min(m.quantity) <> i.initial_quantity
) x;

-- Informational: ACTIVE reservations more than a minute past expiry (the worker should have taken them).
INSERT INTO check_result
SELECT 'info, not a failure: ACTIVE more than 1 minute past expires_at', count(*)
FROM reservation WHERE status = 'ACTIVE' AND expires_at < now() - interval '1 minute';

SELECT name AS check, offending FROM check_result;

DO $$
DECLARE
    failures bigint;
BEGIN
    SELECT coalesce(sum(offending), 0) INTO failures FROM check_result WHERE name NOT LIKE 'info%';
    IF failures > 0 THEN
        RAISE EXCEPTION 'consistency check failed: % offending rows', failures;
    END IF;
    RAISE NOTICE 'consistency check passed';
END $$;
