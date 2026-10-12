# Operational runbook

Commands assume the compose stack (`docker compose`, service `postgres`, database and user `reservecore`).
For another deployment, run the same SQL with `psql` against its database. Every procedure marked
"drilled" was run against the compose stack on 2026-10-12; see docs/PLAN.md section 13, increment 7.

## Configuration

Set as Spring properties or environment variables (`reservecore.expiry.batch-size` is
`RESERVECORE_EXPIRY_BATCHSIZE`, and so on).

| Property | Default | Meaning |
|---|---|---|
| `spring.datasource.url`, `.username`, `.password` | none (required) | PostgreSQL connection; no defaults ship with the app |
| `spring.profiles.active` | none | `dev` seeds the demo merchants and keys. Never in production |
| `reservecore.lock-timeout` | 2s | Longest wait for a row lock inside an API transaction; past it the request rolls back and gets 503 SERVICE_BUSY with `Retry-After: 1` |
| `reservecore.hold.default-seconds` | 600 | Hold used when a request omits `holdSeconds` |
| `reservecore.hold.min-seconds` / `max-seconds` | 5 / 3600 | Accepted `holdSeconds` range; startup fails if `min <= default <= max` does not hold |
| `reservecore.workers.enabled` | true | Runs the expiration worker and idempotency purge on this instance |
| `reservecore.expiry.interval` | 1s | Delay between expiration passes |
| `reservecore.expiry.batch-size` | 100 | Reservations per batch transaction |
| `reservecore.expiry.max-batches-per-tick` | 10 | Batches per pass before waiting for the next tick |
| `reservecore.idempotency.retention` | 24h | Age after which idempotency records are deleted |
| `reservecore.idempotency.purge-batch-size` | 1000 | Records deleted per statement |
| `reservecore.idempotency.purge-interval` | 1m | Delay between purge runs |
| `spring.datasource.hikari.maximum-pool-size` | 10 | Connections per instance; keep `instances x pool` well below PostgreSQL `max_connections` (100 by default) |
| `spring.datasource.hikari.connection-timeout` | 5000 (ms) | Longest wait for a pooled connection; requests and readiness fail after this while PostgreSQL is unreachable. Under the heaviest benchmark up to 465 requests per instance waited for a connection, with every response within 1.4 s |
| `logging.structured.format.console` | ecs | JSON log format |

Health and metrics: `/actuator/health/liveness`, `/actuator/health/readiness` (503 when PostgreSQL is not
reachable), `/actuator/prometheus`. These need no API key and share the app port: keep `/actuator` off the
public network edge.

## Start, stop, migrate, scale

- Start: instances run Flyway migrations at startup; concurrent startups are serialized by Flyway's lock.
  A failed migration stops the instance from starting; fix it and redeploy, do not edit applied migrations.
- Stop: any instance can be stopped or killed at any time. Open transactions roll back; clients retry with the
  same Idempotency-Key. Drilled: demo `worker-restart` kills every instance with SIGKILL and restarts them.
- Scale: add identical instances. All of them run the workers; `SKIP LOCKED` divides the work. To run API-only
  instances, set `reservecore.workers.enabled=false` on them and keep it on for at least one instance.

## Signals to alert on

| Signal | Meaning | First action |
|---|---|---|
| `reservecore_expiry_last_success_age_seconds` above about 30 s on every worker-enabled instance | no expiration pass has succeeded: database unreachable or batches failing | "Worker failures" below |
| `reservecore_expiry_lag_seconds` above about 10 s | the last successful pass left due reservations behind (backlog) | "Worker failures" below, point 5 |
| `reservecore_expiry_batch_failures_total` increasing | batches roll back | check ERROR lines `Expiration batch <id> failed` |
| readiness 503 on all instances | PostgreSQL unreachable | "Database unavailable" below |
| `reservecore_operations_total{outcome="SERVICE_BUSY"}` rising | requests gave up waiting for a row lock (2 s): a stalled transaction, or extreme contention on one SKU | check `pg_stat_activity` for long transactions (`now() - xact_start`) and lock waits; clients retry with the same key |
| 5xx rate (`http_server_requests_seconds_count{status=~"5.."}`) | unexpected failures | find the `requestId` of a failing call in the `request completed` lines, then that request's ERROR line |
| consistency check failing | ledger and stock disagree | "Investigating an inventory discrepancy" below |

## API keys (drilled)

Merchants and keys are created with SQL; there is no admin API. Generate a key, store only its prefix and
SHA-256 hash, and hand the key to the merchant over a secure channel. It is not recoverable later.

```
PREFIX=$(openssl rand -hex 4)
SECRET=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')     # 43 characters
KEY="rcsk_${PREFIX}_${SECRET}"
HASH=$(printf %s "$KEY" | shasum -a 256 | cut -d' ' -f1)           # sha256sum on Linux
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U reservecore -d reservecore -c \
  "WITH m AS (INSERT INTO merchant (name) VALUES ('Acme Store') RETURNING id)
   INSERT INTO api_key (merchant_id, key_prefix, key_hash)
   SELECT id, '$PREFIX', decode('$HASH', 'hex') FROM m RETURNING merchant_id"
echo "$KEY"    # deliver to the merchant, then clear the shell history
```

Additional key for an existing merchant: the same `INSERT INTO api_key` with that `merchant_id`.
Revoke (effective on the next request, on every instance):
`UPDATE api_key SET revoked_at = now() WHERE key_prefix = '<prefix>';`

## Backups and restore (drilled)

`pg_dump` takes a consistent snapshot while the application runs:

```
docker compose exec -T postgres pg_dump -U reservecore -d reservecore -Fc > reservecore-$(date -u +%Y%m%dT%H%M%SZ).dump
```

Restore into a new database, check it, then point the application at it:

```
docker compose exec -T postgres createdb -U reservecore reservecore_restore
docker compose exec -T postgres pg_restore -U reservecore -d reservecore_restore --no-owner --exit-on-error < reservecore-....dump
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U reservecore -d reservecore_restore < ops/consistency-check.sql
```

Drill result: a 3.2 MB dump of 20,532 reservations restored with identical row counts and Flyway history,
and the consistency check passed on the copy.

A dump loses everything after it was taken, including reservations clients believe they hold. For production
use continuous WAL archiving with point-in-time recovery (managed PostgreSQL or pgBackRest); that setup is not
part of this repository and has not been drilled. After restoring to an earlier point, idempotency records
from after that point are gone too, so client retries of those requests execute again.

## Worker failures

Symptoms: last-success age rising, lag gauge high, failure counter increasing, ERROR `Expiration batch <id> failed and was rolled
back`, or reservations ACTIVE well past `expires_at`.

1. Check that some instance runs workers (`reservecore.workers.enabled`) and is ready.
2. Read the ERROR line's exception. A failed batch changes nothing; its rows stay ACTIVE and are retried on
   the next pass, so a transient database error needs no action.
3. A persistent error (for example a row that always fails) blocks only the batches that contain it. Find
   the oldest stuck rows:
   `SELECT id, merchant_id, inventory_id, quantity, expires_at FROM reservation WHERE status = 'ACTIVE' AND expires_at < now() - interval '1 minute' ORDER BY expires_at LIMIT 20;`
4. Do not release stock by hand-editing `inventory`; the worker's transaction keeps reservation, stock and
   movements in step. Fix the cause, and the next pass reclaims everything that is due.
5. Backlog: one pass handles up to `batch-size x max-batches-per-tick` (1,000 by default) per instance per
   interval. Raise either setting, or add instances, if the lag keeps growing while batches succeed.

While holds wait to be reclaimed, their units stay unavailable (`held`). Clients can no longer confirm or
cancel them, so nothing is sold that should not be.

## Idempotency records

The purge runs every minute on each worker-enabled instance and deletes records older than the retention.
Table size check: `SELECT count(*), min(created_at) FROM idempotency_record;` A `min(created_at)` much older
than the retention means the purge is not running. Reservations and movements are never purged by the
application; archive them by your own retention policy if needed.

## Investigating an inventory discrepancy

1. Run the automated check (read-only, drilled):
   ```
   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U reservecore -d reservecore < ops/consistency-check.sql
   ```
   It verifies the balance, that `held` and `sold` equal the ACTIVE and CONFIRMED reservations, that each
   SKU's newest movement matches its current quantities, that every reservation has exactly one RESERVED and
   at most one matching resolution movement, and each SKU's STOCK_CREATED movement. It prints the offending
   row count per check and exits non-zero if any is above zero. It passed after the demo and benchmark runs
   (20,532 reservations, 20,655 movements) and failed, as intended, when a movement was deleted inside a
   rolled-back transaction.
2. For one SKU, read its history through the API (`GET /v1/skus/{sku}/movements`) or SQL:
   `SELECT id, kind, quantity, reservation_id, available_after, held_after, sold_after, request_id, created_at FROM inventory_movement WHERE inventory_id = (SELECT id FROM inventory WHERE merchant_id = '<merchant>' AND sku = '<sku>') ORDER BY id;`
   Each row shows the quantities right after that change and the `request_id` that caused it
   (`expiry-<batch id>` for the worker; an EXPIRED row with an API request id was expired on touch by a late
   confirm or cancel). Search the logs for that id to see the request. Actuator probes and scrapes are not in
   the access log.
3. Customer reports "I was charged but the reservation expired": the reservation's status and `resolved_at`
   show what happened. Confirmation at or after `expires_at` is refused by design (409 RESERVATION_EXPIRED);
   the storefront must not charge before a successful confirm.
4. Remember what the quantities mean: `sold` counts confirmations reported by merchants, not shipments, and
   `held` includes expired holds not yet reclaimed (see the lag gauge).

## Database unavailable

Drilled on 2026-10-12 with the current configuration: the PostgreSQL container was stopped
(`docker stop reservecore-postgres-1`) while both app instances kept running; their start times were
unchanged afterwards, so neither was restarted.

- API calls fail with 500 INTERNAL_ERROR after at most the pool's `connection-timeout` (5 s here; observed
  0.05 s for the first request, 5.03 s for later ones). Nothing is half-applied: each operation is one
  transaction, and a 500 is not recorded for idempotency, so a retry with the same key executes afresh
  (observed: 201 after the outage).
- Liveness stays 200; readiness answers 503 with `db` DOWN (observed after 5.03 s).
- `/actuator/prometheus` keeps answering (observed 200 in 0.06 s during the outage), because the worker
  gauges never query the database when read: `reservecore_expiry_last_success_age_seconds` keeps rising
  (15.6 s observed) and `reservecore_expiry_batch_failures_total` counts the failed batches.
- After PostgreSQL started again, both instances were ready within 2 s without a restart, a hold that had
  expired during the outage was reclaimed within 1 s, and `ops/consistency-check.sql` passed.

Before the 5 s timeout and the in-memory gauges (an earlier drill the same day), probes and scrapes did not
answer within 30 s during an outage.

Clients should retry with the same Idempotency-Key once the service is ready again.

## Local development reset

`docker compose down -v` removes the database volume; the next `docker compose up` starts empty, with the
dev merchants seeded again.
