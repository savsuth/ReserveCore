# Architecture notes

How ReserveCore keeps stock correct under concurrency, retries, crashes and multiple instances. The full
design log is `docs/PLAN.md`; settled choices are in `docs/DECISIONS.md`.

## Shape

- One Spring Boot 4.1 application (Java 25, virtual threads) that serves the HTTP API and runs two
  scheduled workers: reservation expiration and idempotency-record purge. Any number of identical
  instances run against one PostgreSQL 18 database; compose runs two.
- PostgreSQL is the only state. Instances keep no in-memory state that matters for correctness: no
  in-process locks, no caches, no per-reservation timers. Coordination happens through row locks,
  conditional updates, unique indexes and `FOR UPDATE SKIP LOCKED`.
- Data access is explicit SQL through Spring `JdbcClient`; schema changes are Flyway migrations
  (`service/src/main/resources/db/migration`). Instances starting together are safe: Flyway serializes
  migration with a database lock (observed: one instance applied V1, the other found it up to date).

Code map (`service/src/main/java/io/github/savsuth/reservecore/`):

| Package | Responsibility |
|---|---|
| `web` | `RequestIdFilter` (request id, access log), problem-details error rendering, OpenAPI metadata |
| `auth` | `ApiKeyAuthInterceptor` on `/v1/**`: Bearer API key to merchant id |
| `inventory` | SKU creation, availability, movement history |
| `reservation` | Reservation create, read, confirm, cancel |
| `idempotency` | `Idempotency` (transaction owner, record and replay), `IdempotencyPurger` |
| `expiry` | `ExpirationWorker`, scheduling switch |

## Data model and what the database enforces

| Table | Purpose | Enforced by PostgreSQL |
|---|---|---|
| `merchant`, `api_key` | Merchants and their key hashes | `key_prefix` unique; hash is 32 bytes |
| `inventory` | One row per SKU: `initial_quantity`, `available`, `held`, `sold` | all quantities `>= 0`; `initial_quantity = available + held + sold`; `(merchant_id, sku)` unique |
| `reservation` | One SKU, quantity, status, `expires_at`, `resolved_at` | status in the four states; `resolved_at` set exactly when not ACTIVE; FK `(merchant_id, inventory_id)` |
| `inventory_movement` | Audit trail: every stock change with the quantities after it and the causing request id | at most one CONFIRMED/CANCELLED/EXPIRED row per reservation (partial unique index); FKs include `merchant_id` |
| `idempotency_record` | Stored responses keyed by `(merchant_id, operation, idempotency_key)` | primary key on that triple |

Quantities: `available` can be reserved now; `held` belongs to ACTIVE reservations (including ones past
`expires_at` that the worker has not processed yet); `sold` belongs to CONFIRMED reservations, meaning the
merchant reported a completed purchase. None of them describe physical warehouse stock.

So even a bug in application code cannot oversell (negative `available` is rejected), unbalance a SKU,
link one merchant's reservation to another merchant's SKU, or release a reservation's units twice.

## State machine

| From | Event | To | Stock change |
|---|---|---|---|
| (new) | reserve, enough `available` | ACTIVE | `available - q`, `held + q` |
| ACTIVE | confirm, before `expires_at` | CONFIRMED | `held - q`, `sold + q` |
| ACTIVE | cancel, before `expires_at` | CANCELLED | `held - q`, `available + q` |
| ACTIVE | worker, at or after `expires_at` | EXPIRED | `held - q`, `available + q` |
| ACTIVE | confirm or cancel, at or after `expires_at` (expire on touch) | EXPIRED, answer 409 RESERVATION_EXPIRED | `held - q`, `available + q` |

CONFIRMED, CANCELLED and EXPIRED are terminal. Expiry is judged by database time. Once `expires_at` has
passed, a hold can no longer be confirmed or cancelled. The first confirm or cancel that finds it overdue
expires it in the same transaction (units released now, EXPIRED ledger row carrying that request's id) and
answers 409 RESERVATION_EXPIRED; otherwise the worker expires it. Both paths take the reservation row lock and
require `status = 'ACTIVE'`, and the partial unique index allows one resolution row, so the units are released
exactly once whichever path wins.

Reads report the effective status: an ACTIVE hold at or past `expires_at` reads as EXPIRED with
`reclaimPending: true` until its units are released, so clients never see a stale ACTIVE.

## Transaction boundaries

One transaction per API write and per worker batch; no network calls inside them.

| Operation | Statements, in order (one transaction) |
|---|---|
| Create SKU | insert inventory (`ON CONFLICT DO NOTHING` gives 409) ; insert STOCK_CREATED movement |
| Reserve | `SET LOCAL lock_timeout` ; insert idempotency record ; ONE statement: `WITH inv AS (UPDATE inventory ... WHERE ... AND available >= q RETURNING ...), r AS (INSERT INTO reservation ...), ledger AS (INSERT INTO inventory_movement ...) SELECT ...` ; store response |
| Confirm / cancel | `SET LOCAL lock_timeout` ; insert idempotency record ; `SELECT status ... FOR UPDATE` reservation ; status check ; ONE statement: `WITH r AS (UPDATE reservation SET status = CASE WHEN clock_timestamp() < expires_at THEN target ELSE 'EXPIRED' END ...), inv AS (UPDATE inventory ...), ledger AS (INSERT ...) SELECT ...` ; store response |
| Expiration batch | `WITH due AS (SELECT ... WHERE status = 'ACTIVE' AND expires_at <= now() ORDER BY expires_at LIMIT n FOR UPDATE SKIP LOCKED) UPDATE reservation ... RETURNING` ; per SKU in ascending id: update inventory, then all its EXPIRED movements as one JDBC batch |
| Idempotency purge | one `DELETE` of up to `purge-batch-size` records older than the retention |

Reservation, inventory, movement and idempotency changes therefore commit together or not at all.
Business rejections are decided before the first write of a transaction, so they can be committed with
their idempotency record without leaving partial changes. The one deliberate exception is expire on touch,
whose state change is meant to be committed with its 409.

Every API transaction waits at most `reservecore.lock-timeout` (2 s) for any row lock (`SET LOCAL lock_timeout`,
scoped to that transaction, so Flyway and the worker are unaffected). On timeout PostgreSQL raises SQLSTATE
55P03, the transaction rolls back, nothing is recorded for idempotency, and the client gets 503 SERVICE_BUSY with
`Retry-After: 1`; retrying with the same key is safe. Without this, one stalled transaction on a hot SKU would
queue every request for that SKU, and with it the connection pool, indefinitely.

## Concurrency control

- Availability check and deduction are one conditional `UPDATE`. Concurrent requests for a SKU queue on its
  row lock; each re-evaluates `available >= q` against the latest committed value when it gets the lock
  (PostgreSQL re-checks the `WHERE` clause on the updated row), so the last units go to exactly as many
  requests as fit.
- Lock order, everywhere: idempotency record, then reservation row(s), then inventory row(s). Only the worker
  locks several inventory rows, and it does so in ascending id order, so two workers cannot deadlock.
- Expiry check: a `SELECT ... FOR UPDATE` computes its output before it waits for the lock, so the expiry
  comparison is made in the following `UPDATE` with `clock_timestamp()`, after the lock is held. Time spent
  waiting for the lock counts.
- Worker versus confirm/cancel: whoever locks the reservation row first wins; the other sees the new status
  (409 INVALID_STATE_TRANSITION, `currentStatus` in the body) or, for the worker, skips the locked row and
  takes it on a later pass if it is still due. The worker's `now()` is its transaction start, never later
  than a concurrent confirm's `clock_timestamp()`, so the worker never expires a reservation a confirm could
  still accept.
- Multiple workers: `SKIP LOCKED` hands disjoint rows to concurrent batches; there are no leases to expire
  and no stale-worker problem, because a batch only exists as an open transaction.

## Hot path and contention (measured)

Every request for one SKU serializes on that SKU's inventory row, so per-SKU throughput is bounded by how long
a request holds the row: from the conditional UPDATE to COMMIT. Measured, not assumed
(docs/BENCHMARK.md, "Hot-path engineering"):

- Wait-event sampling of PostgreSQL under 1,000 concurrent clients: 73% of active session time was queued on
  the row lock (`Lock:transactionid` 49%, `Lock:tuple` 24%), 13% in `ClientRead` (inside a transaction,
  waiting for the application's next statement), about 6% WAL I/O, 3% CPU. The workload is lock-bound, and
  the holder's time is dominated by application round trips, not by query execution or disk (`fdatasync`
  measured at about 60 µs).
- Reserve, confirm and cancel now write stock, reservation and ledger row in ONE data-modifying statement,
  removing two round trips from the critical section. An interleaved A/B (9 runs per cell per variant, client
  inside the Docker network) found no difference larger than the run-to-run variance (about ±20%) on this
  laptop; the change is kept for fewer statements per write and one atomic statement, not for a throughput
  claim.
- A lock-free availability precheck was tried and removed: PostgreSQL does not lock a row that fails the
  `WHERE` clause, so once stock is gone the conditional UPDATE already returns without waiting, and in a burst
  every request reads the pre-burst value anyway. It only added a round trip.
- The remaining critical section is two application round trips (store the idempotency response, COMMIT)
  plus JSON serialization between them. Removing them means a server-only transaction (idempotency record
  built in SQL, or replays reconstructed from the reservation row): a change to the idempotency storage
  contract, proposed rather than done.

## Idempotency

- Required on reserve, confirm and cancel; scoped by merchant and operation. The record is inserted first in
  the business transaction. A concurrent request with the same key blocks on the primary key until the first
  finishes: if it committed, the second replays the stored response (same status and body,
  `Idempotent-Replayed: true`) or answers 409 IDEMPOTENCY_KEY_REUSED when its request differs (SHA-256 of a
  canonical form of the request); if it rolled back, the second runs normally.
- Recorded and replayed: successes and business rejections (404 not found, 409 insufficient stock, expired,
  invalid transition). A replay shows what happened originally; clients read the resource for current state.
- Not recorded: validation errors, missing or invalid keys, auth failures, key-reuse conflicts, and any
  failure that rolls back (5xx). A retry after a 5xx executes afresh.
- Records are purged after 24 hours by default; the same key then counts as new.

## Expiration and recovery

- The worker runs every second on every instance (`reservecore.expiry.*`), in batches of 100, up to 10
  batches per pass. Between `expires_at` and the next pass (or the next confirm or cancel of that hold), the
  units stay in `held`; reads report the hold as EXPIRED with `reclaimPending: true`. Each successful pass
  records in the `reservecore.expiry.lag` gauge the age of the oldest due reservation it left behind;
  `reservecore.expiry.last.success.age` is computed in memory and keeps rising while passes fail or stop.
  Neither gauge queries the database when read, so metrics stay available during a database outage.
- Crash at any point: an open transaction is rolled back by PostgreSQL, so a request or batch either
  happened completely or not at all. Rows a crashed worker had claimed are unlocked and picked up by the next
  pass on any instance. A client whose request was in flight retries with the same Idempotency-Key and gets
  either the recorded result or a fresh execution.
- Restart of every instance: holds that expired while nothing was running are reclaimed by the first passes
  after start (demo `worker-restart`: 30 holds reclaimed within about 100 ms of the instances reporting ready).

## Security and isolation

- API keys `rcsk_<8-char prefix>_<43-char secret>`; the database stores the prefix and SHA-256 of the full
  key; lookup by prefix, constant-time comparison; revocation takes effect on the next request.
- Every query filters by the authenticated merchant; child rows reference parents through keys that include
  `merchant_id`. Another merchant's resources answer 404, never 403.
- Keys, headers and bodies are never logged.

## Observability

ECS JSON logs with `requestId` and `merchantId` on every line of a request and one `request completed` line
per request; `X-Request-Id` on every response; liveness and readiness (readiness includes the database);
Prometheus metrics including `http_server_requests_*`, HikariCP, and the expiration worker's
`reservecore_expiry_expired_total`, `_batch_failures_total`, `_batch_duration_seconds`, `_lag_seconds`,
`_last_success_age_seconds`, and `reservecore_operations_total{operation, outcome, replayed}`: one count per
write request with its outcome (`SUCCESS` or the error code such as `INSUFFICIENT_STOCK`, `SERVICE_BUSY`) and
whether it was an idempotent replay, which HTTP status metrics cannot distinguish. Actuator probes and scrapes
are not access-logged. The pool waits at most 5 s for a connection, so requests and readiness fail fast when
PostgreSQL is unreachable.

## Where each property is verified

| Property | Evidence |
|---|---|
| No overselling under contention | `ConcurrentReservationTest` (500 vs 20, 200 x 3 vs 20); demo `contention`; benchmark checks |
| Bounded lock waits, safe retry after 503 | `LockTimeoutTest` (row lock held by the test on a second connection) |
| Expire on touch, effective status on read | `ReservationLifecycleApiTest`, `ExpirationWorkerTest.overdueHoldReadsAsExpired...` |
| Rollback leaves no partial change | `ReservationRollbackTest`, `ExpirationWorkerTest.failedBatchRollsBack...` |
| One logical operation per key, concurrent or retried | `IdempotencyApiTest`, `ReservationLifecycleApiTest`; demo `duplicates` |
| Conflicting payload on a key | `IdempotencyApiTest.sameKeyWithDifferentPayloadIsRejected` |
| Confirm/cancel races, confirmed at most once | `ConcurrentTransitionTest` |
| Confirm racing expiration | `ExpirationWorkerTest` (fixed orders, already-expired race, boundary run) |
| Multiple workers | `ExpirationWorkerTest.concurrentWorkers...`; compose run with two instances |
| Restart and recovery | demo `worker-restart` (SIGKILL of every instance); PLAN.md increment 5 compose drill |
| Cross-merchant access | `MerchantIsolationTest`, `ReservationApiTest`, `MovementHistoryApiTest`; demo `lifecycle` |
| Ledger consistency | `ops/consistency-check.sql` after demo and benchmark runs, and on a restored backup |
