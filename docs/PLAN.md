# ReserveCore Implementation Plan

Derived from `docs/PROCEDURE.md` (brief) and `docs/DECISIONS.md` (settled stack). Created 2026-10-11.
This file is the spec and the progress log. Update the checklist at the end of each increment.

## 1. Product spec

ReserveCore holds limited stock for a storefront while a shopper checks out. A merchant creates a
SKU with an initial quantity. The storefront reserves units for a bounded time, then confirms
(the external checkout succeeded) or cancels. Reservations that are neither confirmed nor cancelled
before `expires_at` are expired by a background worker, and their units return to availability.

Guarantees: no overselling, no double-applied request, no cross-merchant access, and no partially
applied transition after a crash or restart.

Out of scope for version one: payments, multi-item checkout, restocking or adjusting quantity after
creation, merchant self-signup (merchants and keys are bootstrapped), Redis, message brokers,
physical fulfilment.

Assumptions (not stated in the brief, chosen as the simplest consistent reading):

- A SKU is identified by a merchant-chosen code (`sku`), unique per merchant.
- Merchants are created by bootstrap SQL only; there is no merchant admin API.
- An ACTIVE reservation at or past `expires_at` cannot be confirmed or cancelled by the API.
  Only the worker moves it, and only to EXPIRED. This keeps one release path for expired holds.
- Cross-merchant access returns 404, not 403, so resource existence is not revealed.

## 2. State machine

```
            confirm (before expires_at)
  ACTIVE ------------------------------> CONFIRMED
    |   \
    |    \  cancel (before expires_at)
    |     -----------------------------> CANCELLED
    |
    |  worker (at or after expires_at)
    +----------------------------------> EXPIRED
```

- Terminal states: CONFIRMED, CANCELLED, EXPIRED. They never transition.
- Expiry is evaluated with database time while the reservation row lock is held:
  `clock_timestamp() >= expires_at` means expired (boundary: at `expires_at`, confirm fails).
  `clock_timestamp()` is used, not `now()`, because `now()` is the transaction start time and a
  transaction may wait on the row lock past `expires_at`.
- The worker selects `expires_at <= now()`. Its `now()` is never later than any concurrent
  confirm's `clock_timestamp()`, so the worker never expires a reservation a confirm could still
  accept; the row lock plus the `status = 'ACTIVE'` check makes the two mutually exclusive.

## 3. Quantities and invariant

Per SKU row (`inventory`):

| Quantity | Meaning |
|---|---|
| `initial_quantity` | Units the merchant put up for sale at SKU creation. Never changes in v1. |
| `available` | Units a new reservation can take right now. |
| `held` | Units in reservations whose status is ACTIVE, including ACTIVE reservations past `expires_at` that the worker has not reclaimed yet. |
| `sold` | Units in CONFIRMED reservations. |

Invariant, enforced by a CHECK constraint on every row write:
`initial_quantity = available + held + sold`, with all four `>= 0`.

"Sold" means the merchant reported a completed purchase. It says nothing about shipping or the
physical warehouse.

Unreclaimed stock: between `expires_at` and the worker's next pass, expired-but-unprocessed units
stay in `held` and are not available. The delay is bounded by the worker interval plus backlog
(batch size times batches per tick). The availability endpoint returns `held` so the lag is
visible, and the worker exports the age of the oldest unreclaimed reservation as a metric.

## 4. API outline

Base path `/v1`. JSON. Auth header `Authorization: Bearer <api key>` on every `/v1` call.
Every response carries `X-Request-Id` (client value echoed if it matches `[A-Za-z0-9._-]{1,64}`,
otherwise a generated UUID). Replayed idempotent responses add `Idempotent-Replayed: true`.

| Method and path | Purpose | Success | Errors |
|---|---|---|---|
| `POST /v1/skus` | Create SKU with initial quantity | 201 | 400 VALIDATION_FAILED / INVALID_QUANTITY, 409 SKU_ALREADY_EXISTS |
| `GET /v1/skus/{sku}` | Availability | 200 | 404 SKU_NOT_FOUND |
| `GET /v1/skus/{sku}/movements?limit=&before=` | Movement history, newest first, keyset paginated on movement id (`limit` 1..200, default 50) | 200 | 400 VALIDATION_FAILED, 404 SKU_NOT_FOUND |
| `POST /v1/reservations` (Idempotency-Key) | Reserve `quantity` of `sku` for `holdSeconds` | 201 | 400 INVALID_QUANTITY / VALIDATION_FAILED / IDEMPOTENCY_KEY_MISSING / IDEMPOTENCY_KEY_INVALID, 404 SKU_NOT_FOUND, 409 INSUFFICIENT_STOCK, 409 IDEMPOTENCY_KEY_REUSED |
| `GET /v1/reservations/{id}` | Read reservation | 200 | 404 RESERVATION_NOT_FOUND |
| `POST /v1/reservations/{id}/confirm` (Idempotency-Key) | ACTIVE -> CONFIRMED | 200 | 400 IDEMPOTENCY_KEY_MISSING / INVALID, 404, 409 RESERVATION_EXPIRED, 409 INVALID_STATE_TRANSITION, 409 IDEMPOTENCY_KEY_REUSED |
| `POST /v1/reservations/{id}/cancel` (Idempotency-Key) | ACTIVE -> CANCELLED | 200 | 400 IDEMPOTENCY_KEY_MISSING / INVALID, 404, 409 RESERVATION_EXPIRED, 409 INVALID_STATE_TRANSITION, 409 IDEMPOTENCY_KEY_REUSED |

All `/v1` endpoints also return 401 UNAUTHORIZED for a missing, malformed, unknown, or revoked key.
`holdSeconds` is optional: default 600, bounds 5..3600, configurable via
`reservecore.hold.default/min/max`. Out-of-bounds values are 400 VALIDATION_FAILED.
`quantity` and `initialQuantity` must be integers >= 1: a missing, zero, or negative value is
400 INVALID_QUANTITY; a non-integer (`1.5`, `"ten"`) or out-of-range value fails JSON binding and is
400 VALIDATION_FAILED. `sku` must match `[A-Za-z0-9._-]{1,64}` (400 VALIDATION_FAILED).
Malformed reservation ids in a path are 404 RESERVATION_NOT_FOUND.
Spring MVC's own errors (malformed JSON, unknown path, wrong method) use the same schema with generic
codes (VALIDATION_FAILED, NOT_FOUND, METHOD_NOT_ALLOWED, ...). Unexpected failures are
500 INTERNAL_ERROR with no internal detail; the exception is logged with the request id.

Error schema: RFC 9457 problem details (`application/problem+json`, Spring `ProblemDetail`) with two
extension members, `code` (stable machine string from the tables above) and `requestId`:

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "Requested 3 of SKU HOODIE-M but only 1 available.",
  "instance": "/v1/reservations",
  "code": "INSUFFICIENT_STOCK",
  "requestId": "6f1c2b0e-5c1e-4c55-9d7c-1f8f3b2f8a10"
}
```

`type` is omitted, which RFC 9457 defines as `about:blank`.

Movement history page: `{"items": [{"id", "kind", "quantity", "reservationId", "availableAfter",
"heldAfter", "soldAfter", "requestId", "createdAt"}], "nextBefore": <id or null>}`. Pass `nextBefore`
as `before` to get the next (older) page. `requestId` is the API request that caused the movement, or
`expiry-<batch uuid>` for worker expirations (the same id appears in the worker's log line).

Sample: create reservation.

```
POST /v1/reservations
Authorization: Bearer rcsk_...
Idempotency-Key: checkout-81723-attempt-1
Content-Type: application/json

{"sku": "HOODIE-M", "quantity": 1, "holdSeconds": 600}

201 Created
Location: /v1/reservations/0199d0a4-...
{"id": "0199d0a4-...", "sku": "HOODIE-M", "quantity": 1, "status": "ACTIVE",
 "expiresAt": "2026-10-11T18:10:00Z", "createdAt": "2026-10-11T18:00:00Z", "resolvedAt": null}
```

## 5. Table design (Flyway V1)

IDs are `uuid` with `DEFAULT uuidv7()` (PostgreSQL 18), except `inventory_movement` which uses a
bigint identity for cheap keyset pagination.

- `merchant(id, name, created_at)`
- `api_key(id, merchant_id -> merchant, key_prefix UNIQUE, key_hash bytea (SHA-256, 32 bytes), created_at, revoked_at)`
- `inventory(id, merchant_id -> merchant, sku, initial_quantity, available, held, sold, created_at, updated_at)`
  - `UNIQUE (merchant_id, sku)`, `UNIQUE (merchant_id, id)` (target of composite FKs)
  - CHECK all quantities `>= 0`, `initial_quantity >= 1`, invariant CHECK
- `reservation(id, merchant_id, inventory_id, quantity, status, expires_at, created_at, resolved_at)`
  - `FOREIGN KEY (merchant_id, inventory_id) REFERENCES inventory (merchant_id, id)`
  - CHECK `quantity >= 1`, status in the four values, `(status = 'ACTIVE') = (resolved_at IS NULL)`
  - partial index `(expires_at) WHERE status = 'ACTIVE'` for the worker
- `inventory_movement(id bigint identity, merchant_id, inventory_id, reservation_id, kind, quantity, available_after, held_after, sold_after, request_id, created_at)`
  - kinds: STOCK_CREATED, RESERVED, CONFIRMED, CANCELLED, EXPIRED
  - composite FKs including `merchant_id` to `inventory` and `reservation`
  - partial UNIQUE `(reservation_id) WHERE kind IN ('CONFIRMED','CANCELLED','EXPIRED')`:
    a reservation's held units can be released or sold at most once, enforced by the database
  - index `(inventory_id, id DESC)` for history paging
- `idempotency_record(merchant_id, operation, idempotency_key, request_hash bytea, response_status, response_body jsonb, created_at)`
  - PRIMARY KEY `(merchant_id, operation, idempotency_key)`; index on `created_at` for retention purge

## 6. Transaction boundaries and lock order

One database transaction per API write and per worker batch. No network calls inside a transaction.
Reservation change, inventory change, movement row, and idempotency record commit together or not at all.

Global lock order: `idempotency_record` row -> `reservation` row(s) -> `inventory` row(s).
When several inventory rows are locked (worker batch), they are updated in ascending `id` order.

Create reservation (one transaction):
1. `INSERT idempotency_record ... ON CONFLICT DO NOTHING RETURNING` (placeholder row; see section 7).
2. `UPDATE inventory SET available = available - :q, held = held + :q, updated_at = now()
   WHERE merchant_id = :m AND sku = :sku AND available >= :q RETURNING ...`.
   Zero rows: look up the SKU to choose 404 SKU_NOT_FOUND or 409 INSUFFICIENT_STOCK.
   The check and the deduction are the same statement; the row lock serializes competitors.
3. `INSERT reservation ... expires_at = clock_timestamp() + make_interval(secs => :hold)`.
4. `INSERT inventory_movement (RESERVED ...)`.
5. `UPDATE idempotency_record SET response_status, response_body`.

Confirm or cancel (one transaction):
1. Idempotency placeholder insert.
2. `SELECT ... FROM reservation WHERE id = :id AND merchant_id = :m FOR UPDATE` (404 if absent).
3. Status not ACTIVE: 409 INVALID_STATE_TRANSITION (body member `currentStatus`).
4. `UPDATE reservation SET status = :target, resolved_at = clock_timestamp()
   WHERE ... AND clock_timestamp() < expires_at`. Zero rows means expired: 409 RESERVATION_EXPIRED, no change.
   The check is in this separate statement, not in the step 2 SELECT, because a `SELECT ... FOR UPDATE`
   computes its output columns before it waits for the row lock; the UPDATE runs with the lock already held.
5. `UPDATE inventory` (confirm: `held - q, sold + q`; cancel: `held - q, available + q`).
6. `INSERT inventory_movement`, then store the idempotent response.

Implementation: `idempotency/Idempotency` owns the transaction (a `TransactionTemplate`); the service
methods are `@Transactional(propagation = MANDATORY, noRollbackFor = ApiProblem.class)`, so they only run
inside it and a business rejection does not mark it rollback-only.

## 7. Idempotency rules

- `Idempotency-Key` header (1..255 printable ASCII) is required on create, confirm, cancel.
  Missing: 400 IDEMPOTENCY_KEY_MISSING; malformed: 400 IDEMPOTENCY_KEY_INVALID; nothing recorded.
- Scope: `(merchant_id, operation, key)` where operation is CREATE_RESERVATION, CONFIRM_RESERVATION,
  or CANCEL_RESERVATION.
- Request hash: SHA-256 over a canonical form of the operation inputs: for create, the parsed body
  re-serialized as `{"sku", "quantity", "holdSeconds"}` as sent (an omitted `holdSeconds` stays null, so a
  change to the configured default cannot turn a retry into a conflict); for confirm and cancel,
  `reservation=<uuid>`. Changed from "holdSeconds defaulted before hashing" during increment 4 for that reason.
- First step of the business transaction inserts the record. A concurrent request with the same key
  blocks on the primary key until the first commits or rolls back:
  - first commits: the insert does nothing; the second reads the record. Same hash: replay the
    stored status and body with `Idempotent-Replayed: true`. Different hash: 409 IDEMPOTENCY_KEY_REUSED.
  - first rolls back: the second's insert succeeds and it executes normally.
- Lost response after commit: the retry finds the committed record and replays it.
- Replay format: stored status and body exactly (the first response is also served from the stored
  `jsonb::text`, so first and replayed bodies are byte-identical). A replayed problem keeps the original
  `requestId` in the body; the `X-Request-Id` header is the retry's own id. The Location header of a
  replayed 201 is rebuilt from the stored body's `id`. Bodies of the three idempotent endpoints are
  PostgreSQL's `jsonb` text form (`{"key": "value"}` spacing, jsonb key order).
- Retained outcomes (recorded and replayed): all success responses and deterministic business
  rejections decided inside the transaction (404 not found, 409 insufficient stock, 409 expired,
  409 invalid transition). A replay returns what happened originally; clients GET the resource for
  current state.
- Not retained: 400 validation and missing key, 401 auth, 409 key reuse, and any 5xx or database
  error (the transaction rolls back and removes the placeholder, so a retry executes afresh).
- Retention: 24 hours by default (`reservecore.idempotency.retention`). The worker deletes older
  records in bounded batches (increment 5). After deletion the same key is treated as new.

## 8. Expiration worker

- Spring `@Scheduled` fixed delay (default 1s, `reservecore.expiry.interval`) in every app instance.
  `reservecore.workers.enabled=false` turns the expiration and purge schedules off (tests do this).
  With virtual threads enabled, Spring runs fixed-delay tasks on one scheduler thread, so the expiry
  pass and the idempotency purge take turns on an instance
  (https://docs.spring.io/spring-framework/docs/7.0.x/javadoc-api/org/springframework/scheduling/annotation/Scheduled.html).
- Per batch, one transaction (`expiry/ExpirationWorker`):
  1. Claim and mark in one statement:
     `WITH due AS (SELECT id FROM reservation WHERE status = 'ACTIVE' AND expires_at <= now()
     ORDER BY expires_at LIMIT :batch FOR UPDATE SKIP LOCKED)
     UPDATE reservation r SET status = 'EXPIRED', resolved_at = now() FROM due WHERE r.id = due.id RETURNING ...`
  2. Per inventory row, in ascending id order (only the worker locks several inventory rows, and every
     worker uses this order): `UPDATE inventory SET held - s, available + s`.
  3. One EXPIRED movement per reservation, with `*_after` values as if the reservations were applied one
     by one (the last one carries the row's final quantities); `request_id = expiry-<batch uuid>`.
- Repeat up to `reservecore.expiry.max-batches-per-tick` (default 10) batches of
  `reservecore.expiry.batch-size` (default 100) while batches come back full.
- Multiple instances: `SKIP LOCKED` hands disjoint rows to concurrent workers. Confirm/cancel holding
  a row lock makes the worker skip that row; the worker holding it makes confirm/cancel wait, then
  see EXPIRED (409 INVALID_STATE_TRANSITION, `currentStatus` EXPIRED).
- No leases and no in-memory timers. A crash rolls the batch back; rows stay ACTIVE and the next pass
  on any instance picks them up. Double release is impossible: the row lock plus `status = 'ACTIVE'`
  in the claim, backed by the partial unique index on resolution movements.
- Observability (Micrometer): `reservecore.expiry.expired` counter, `reservecore.expiry.batch.failures`
  counter, `reservecore.expiry.batch.duration` timer, `reservecore.expiry.lag` gauge in seconds (age of the
  oldest due reservation left after the last successful pass) and `reservecore.expiry.last.success.age`
  gauge (seconds since the last successful pass, computed in memory, keeps rising while passes fail or stop).
  Neither gauge queries the database when read (changed 2026-10-12 from a query-on-read lag gauge, which
  made `/actuator/prometheus` hang during a database outage). A failed batch is logged at ERROR with its
  batch id and rethrown; the scheduled pass stops and the next tick retries.
- Idempotency purge (`idempotency/IdempotencyPurger`): every `reservecore.idempotency.purge-interval`
  (default 1m), deletes records older than `retention` (default 24h) in batches of `purge-batch-size`
  (default 1000), oldest first, until a batch comes back short.
- Testing without sleeps: tests set `expires_at` (or an idempotency record's `created_at`) in the past
  directly in the database, then call `ExpirationWorker.runOnce()` / `IdempotencyPurger.purgeOnce()`;
  races use latches. "Multiple instances" in tests are concurrent threads each calling `runOnce()`, each
  batch in its own transaction, which is all a separate instance is to PostgreSQL. The real scheduler on
  two instances is checked on the compose stack (section 13).

## 9. Security

- API key format `rcsk_<prefix>_<secret>`; the database stores the prefix and SHA-256 of the full key.
  Lookup by prefix, constant-time hash compare. Keys never logged; the request filter logs the merchant id.
- Implemented in increment 3 (moved forward from 6 so every endpoint is merchant-scoped from the start):
  `ApiKeyAuthInterceptor` on `/v1/**`; failures are 401 UNAUTHORIZED with `WWW-Authenticate: Bearer`.
- Revoked keys (`revoked_at` set) are rejected like unknown keys.
- Merchant isolation: every query filters by the authenticated `merchant_id`; composite FKs keep child
  rows tied to the same merchant. Another merchant's resources answer 404 on every endpoint
  (`MerchantIsolationTest`). Row-level security remains possible later hardening.
- Local bootstrap (increment 6): `db/dev/R__dev_merchants.sql`, a repeatable Flyway migration in a location
  that only `application-dev.yml` adds, inserts two demo merchants and the SHA-256 hashes of two documented
  development keys (README.md, "Development credentials"). Compose runs with `SPRING_PROFILES_ACTIVE=dev`.
  No merchant or key exists without that profile. Production keys are issued by the operator (runbook,
  increment 7): generate a key in the documented format, insert its prefix and SHA-256 hash.
- Actuator (`health`, `info`, `prometheus`) shares the app port and needs no API key (the interceptor
  covers `/v1/**` only). Accepted for v1 and documented: in a real deployment keep `/actuator` off the
  public network edge or move it to a management port.
- Readiness includes the database (`readinessState,db`): with PostgreSQL down an instance answers 503 on
  `/actuator/health/readiness` while liveness stays UP. Spring Boot's docs caution against shared
  dependencies in readiness; here no `/v1` request can be served without PostgreSQL, so not-ready is accurate.

## 9a. Observability

- Logs: Spring Boot structured logging, `logging.structured.format.console: ecs`
  (https://docs.spring.io/spring-boot/reference/features/logging.html). MDC `requestId` and `merchantId`
  become JSON fields. `RequestIdFilter` writes one `request completed` line per request with `method`,
  `path`, `status`, `durationMs` (SLF4J key-value pairs). Headers and bodies are never logged.
- Metrics: `micrometer-registry-prometheus` (Boot-managed, Micrometer 1.17.1) at `/actuator/prometheus`:
  `http_server_requests_seconds_*`, JVM, HikariCP, and the `reservecore_expiry_*` worker meters.
  `docker compose --profile observability up` adds `prom/prometheus:v3.15.0` scraping app1 and app2
  (`ops/prometheus.yml`). No Grafana (DECISIONS.md).
- OpenAPI: springdoc with `writer-with-order-by-keys: true` and a fixed server `/`, so output is
  deterministic; `override-with-generic-response: false` and explicit `@ApiResponse` per operation (the
  idempotent endpoints return raw JSON strings, so their schemas must be declared). `OpenApiSpecTest`
  fails when `docs/openapi.json` differs from the served spec; `./mvnw verify -Dopenapi.update=true`
  regenerates it. Swagger UI: `/swagger-ui.html`.
- CI: `.github/workflows/ci.yml` runs `./mvnw -B verify` on ubuntu-24.04 with Temurin 25; actions pinned
  to commit SHAs (`actions/checkout` v7.0.1, `actions/setup-java` v6.0.1).

## 10. Increments and checklist

1. Spec, state machine, invariants, API outline, plan
   - [x] Read brief and decisions; git init
   - [x] Verify versions on official pages (section 11)
   - [x] Write this plan
2. Application, PostgreSQL, migrations, local startup
   - [x] Maven wrapper, multi-module build (`service`, `demo`), pinned versions
   - [x] Flyway V1 schema with constraints
   - [x] Testcontainers smoke test on PostgreSQL 18 (migration applied, invariant CHECK enforced)
   - [x] docker-compose.yml: PostgreSQL 18 plus two app instances; both report health UP
   - [x] `./mvnw verify` green on Java 25
3. Inventory and reservation creation with concurrency protection
   - [x] Create SKU, get availability, create reservation, get reservation
   - [x] Problem-details error schema, request id filter
   - [x] API-key authentication (moved forward from increment 6, agreed 2026-10-11)
   - [x] Test: many requests competing for limited stock; rollback leaves no partial change
4. Confirmation, cancellation, persistent idempotency
   - [x] Confirm and cancel with lock order and DB-time expiry check
   - [x] Idempotency records in the business transaction
   - [x] Tests: concurrent same key, conflicting payload, lost response retry, repeated confirm/cancel,
         cancel racing confirm
5. Expiration and transactional audit history
   - [x] Worker with SKIP LOCKED batches; idempotency retention purge
   - [x] Switch `ReservationRollbackTest`'s direct table check to the movement history API
   - [x] Movement history endpoint
   - [x] Tests: confirm racing expiration, multiple workers, worker rollback and retry
   - [x] Restart and recovery: checked on the compose stack (both apps stopped while 300 holds expired);
         an automated restart test is part of increment 7's worker-restart demo
6. Authentication, merchant isolation, documentation, observability
   - [x] Dev bootstrap profile with documented keys; cross-merchant tests for confirm/cancel/movements
         (API-key auth itself landed in increment 3)
   - [x] OpenAPI spec committed and checked in CI; GitHub Actions workflow (written, not yet run on GitHub)
   - [x] Structured JSON logs, metrics, Prometheus optional compose profile
7. Demo, failure scenarios, measured results
   - [x] Demo client: 20 units vs 1,000 requests; expiration, duplicate-request, worker-restart scenarios
   - [x] Benchmark report with hardware, config, concurrency, dataset
   - [x] README, architecture notes, runbook, limitations
   - [x] CI demo job (compose stack, `demo all`, consistency check); written and run locally, not yet on GitHub

## 11. Pinned versions and sources (checked 2026-10-11)

| Component | Version | Source |
|---|---|---|
| Spring Boot (parent) | 4.1.1 | https://spring.io/projects/spring-boot (shows 4.1.1); https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml (latest GA 4.1.1; 4.2.0-M2 is a milestone) |
| springdoc-openapi (webmvc-ui) | 3.1.1 | https://springdoc.org/ ("springdoc-openapi 3.x is compatible with spring-boot 4", current 3.1.1, see `#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot`); its POM's parent is spring-boot-starter-parent 4.1.0 (https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi/3.1.1/springdoc-openapi-3.1.1.pom) |
| Testcontainers | 2.0.5 (Boot-managed) | https://repo.maven.apache.org/maven2/org/testcontainers/testcontainers-bom/maven-metadata.xml (latest 2.0.5); Boot 4.1.1 BOM `testcontainers.version` 2.0.5. 2.x artifact names: `testcontainers-postgresql`, `testcontainers-junit-jupiter`; class `org.testcontainers.postgresql.PostgreSQLContainer` |
| Flyway core + `flyway-database-postgresql` | 12.4.0 (Boot-managed) | Boot 4.1.1 BOM `flyway.version` 12.4.0 (https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom). Maven Central latest is 13.10.0, but DECISIONS.md settles on the Boot-managed version. Module requirement: https://docs.spring.io/spring-boot/how-to/data-initialization.html ("use `org.flywaydb:flyway-database-postgresql` with PostgreSQL") |
| PostgreSQL JDBC | 42.7.13 (Boot-managed) | Boot 4.1.1 BOM |
| JUnit Jupiter | 6.0.3 (Boot-managed) | Boot 4.1.1 BOM `junit-jupiter.version`. DECISIONS.md says "JUnit 5"; Boot 4.1 ships the JUnit 6 line, used as managed |
| Maven wrapper | 3.3.4, distribution Maven 3.9.16 | https://maven.apache.org/tools/wrapper/ (3.3.4, `only-script` default); https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml (latest 3.9.x is 3.9.16; 4.0.0 is still rc-7) |
| PostgreSQL image | `postgres:18.6` | Docker Hub tag `18.6` (updated 2026-10-07). PG 18 image volume is `/var/lib/postgresql` (https://github.com/docker-library/postgres/blob/master/18/trixie/Dockerfile) |
| JDK image | `eclipse-temurin:25-jdk-noble` / `25-jre-noble` | Docker Hub `eclipse-temurin` tags |
| Micrometer Prometheus registry | Boot-managed (Micrometer 1.17.1) | Boot 4.1.1 BOM `micrometer.version`; endpoint per https://docs.spring.io/spring-boot/reference/actuator/metrics.html |
| Prometheus image | `prom/prometheus:v3.15.0` | Docker Hub `prom/prometheus` tags (latest v3 release on 2026-10-11) |
| GitHub Actions | `actions/checkout` v7.0.1 (`3d3c42e5`), `actions/setup-java` v6.0.1 (`de7274f0`) | GitHub releases; SHAs from `git ls-remote --tags` |

Spring Boot docs pages under `/spring-boot/4.1/` redirect to the unversioned path, which states it
documents 4.1.1 (current stable).

## 12. Progress log

- 2026-10-11, increment 1: plan written, versions verified (section 11).
- 2026-10-11, increment 2: see section 13.
- 2026-10-11, increment 3: see section 13. Decisions agreed before coding: API-key auth pulled forward
  from increment 6; all increment 3 tests drive the real server over HTTP.
- 2026-10-11, increment 4: see section 13. Same HTTP test seam. Refinements recorded in sections 6 and 7:
  expiry check folded into the conditional UPDATE, IDEMPOTENCY_KEY_INVALID code, request hash as sent.
- 2026-10-11, increment 5: see section 13. Seams agreed before coding: tests call the worker and purge
  pass methods with the scheduler off; deterministic race orders plus a boundary run; worker metrics
  asserted through the MeterRegistry.
- 2026-10-11, increment 6: see section 13. Decisions agreed before coding: dev bootstrap as a dev-profile
  repeatable Flyway migration; OpenAPI drift checked by a test against the committed file; actuator on the
  app port, documented; structured logs tested through captured output.
- 2026-10-12, follow-up after increment 7: database-outage fix (in-memory worker gauges, 5 s pool
  connection timeout), utilisation run, open finding on lost connections. See section 13.
- 2026-10-12, increment 7: see section 13. Decisions agreed before coding: the worker-restart demo shells
  out to `docker compose kill/start`; benchmark of contended and ample stock at concurrency 50/200/1000,
  3 runs per cell; CI demo job; unit tests for the demo's statistics, scenarios checked live.
- 2026-10-12, senior review: code review of the hot paths, measured A/B, wait-event profiling; changes and
  evidence in section 13 ("Senior review"). Decisions agreed before coding: 2 s lock timeout with 503 SERVICE_BUSY;
  precheck plus one-statement CTEs (precheck later removed on evidence, see below); expire on touch; effective
  status on read. Not taken: an API-key cache (it would delay revocation).

## 13. Increment evidence

### Increment 2 (2026-10-11)

Hardware: Apple M4, 16 GB RAM, Docker 28.3.0 (10 CPU / ~10 GB). JDK: Homebrew OpenJDK 25.0.4.1.

Layout:
- `pom.xml` parent on `spring-boot-starter-parent` 4.1.1; modules `service`, `demo`.
- `service`: webmvc, jdbc, flyway + flyway-database-postgresql, actuator, springdoc 3.1.1, PostgreSQL driver.
  Tests: spring-boot-starter-test, spring-boot-testcontainers, testcontainers-postgresql, testcontainers-junit-jupiter.
- `service/src/main/resources/db/migration/V1__schema.sql`: full schema from section 5.
- `TestcontainersConfiguration`: `postgres:18.6` container as a `@ServiceConnection` bean (shared by the cached context).
- `SchemaSmokeTest` (4 tests): server is 18.x and V1 applied; invariant CHECK and non-negative CHECK reject bad
  updates; composite FK rejects a reservation pointing at another merchant's inventory; partial unique index
  rejects a second resolution movement for one reservation.
- `demo`: `DemoMain` checks `/actuator/health` on each given base URL; exit 0 only if all are UP.
- `Dockerfile` (temurin 25 JDK build stage, 25 JRE runtime, non-root), `docker-compose.yml`
  (`postgres:18.6`, app1 on 8081, app2 on 8082; DB published on host 55432 because 5432 is taken locally).

Evidence:
- `./mvnw -B verify` (Java 25): `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
  Flyway log: `Database: ... (PostgreSQL 18.6)`, `Successfully applied 1 migration ... now at version v1`.
  No Flyway version-support warning with Flyway 12.4.0 on PostgreSQL 18.6.
- `docker compose down -v && docker compose up -d --build --wait`: postgres, app1, app2 all `healthy`.
  On the empty volume app2 applied V1 and app1 logged `Schema "public" is up to date` (Flyway serialized
  the concurrent startup).
- `java -jar demo/target/reservecore-demo.jar http://localhost:8081 http://localhost:8082`: both `200 ... "status":"UP"`,
  exit 0; an unreachable URL gives exit 1.
- `GET http://localhost:8081/v3/api-docs`: 200.
- `node ~/.claude/tools/floor-guard.mjs`: clean (run on a scratch copy with an empty base commit, because
  the repo has no commits yet).

Known and accepted for now: springdoc warns that `/v3/api-docs` and Swagger UI are enabled by default
(intended, API docs are a deliverable); Mockito's byte-buddy agent prints the JDK dynamic-agent warning
during tests (harmless; can be configured as a `-javaagent` in Surefire later).

Local commands:
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./mvnw verify
docker compose up -d --build --wait
java -jar demo/target/reservecore-demo.jar http://localhost:8081 http://localhost:8082
docker compose down        # add -v to drop the database volume
```

### Increment 3 (2026-10-11)

Built (`service/src/main/java/io/github/savsuth/reservecore/`):
- `web/RequestIdFilter`: `X-Request-Id` echo-or-generate on every response, MDC, request attribute.
- `web/ApiProblem`, `web/ProblemDetailsAdvice`: problem details with `code` and `requestId` for our errors,
  Spring MVC errors, and unexpected exceptions (500 INTERNAL_ERROR, details only in the log).
- `auth/ApiKeyAuthInterceptor`: Bearer key, prefix lookup, constant-time SHA-256 compare, merchant id attribute.
- `inventory/`: `POST /v1/skus` (inventory row plus STOCK_CREATED movement in one transaction,
  `ON CONFLICT DO NOTHING` gives 409), `GET /v1/skus/{sku}`.
- `reservation/`: `POST /v1/reservations` (conditional `UPDATE ... WHERE available >= :q RETURNING`, then
  reservation and RESERVED movement in the same transaction; `expires_at` and `created_at` from one
  `clock_timestamp()`), `GET /v1/reservations/{id}`; `HoldProperties` (`reservecore.hold.*`, validated at startup).
- `spring.jackson.deserialization.accept-float-as-int: false` so `1.5` is rejected instead of truncated
  (property from https://docs.spring.io/spring-boot/how-to/spring-mvc.html#howto.spring-mvc.customize-jackson-jsonmapper, Boot 4.1.1).

Tests (all over HTTP against the real server on a random port, Testcontainers `postgres:18.6`):
- `AuthenticationApiTest` (4): missing, unknown, tampered, malformed keys give 401 problem details; request id echo/replace.
- `SkuApiTest` (5): create and read, duplicate 409, per-merchant SKU scoping, invalid quantity and SKU code, bad JSON.
- `ReservationApiTest` (5): stock moves available to held, default hold 600 s, insufficient stock without change,
  cross-merchant and unknown ids 404, invalid quantity / hold bounds / missing sku.
- `ConcurrentReservationTest` (2): 500 parallel one-unit requests vs 20 units give exactly 20 x 201 and
  480 x 409 INSUFFICIENT_STOCK, 20 distinct ids, stock 0 available / 20 held; 200 parallel three-unit
  requests vs 20 units give 6 x 201, 194 x 409, stock 2 / 18. Requests are released by a latch, no sleeps.
- `ReservationRollbackTest` (1): a test-only trigger fails the RESERVED movement insert; response is 500
  INTERNAL_ERROR without internal detail; stock and reservation table unchanged; full stock reservable afterwards.

Evidence:
- `./mvnw -B verify` (Java 25): `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Mutation checks (temporary code changes, reverted):
  - check-then-act reservation (separate SELECT, unconditional UPDATE): the 500-request test fails with
    `{"201"=20, "409 INSUFFICIENT_STOCK"=474, "500 INTERNAL_ERROR"=6}` (CHECK constraint stopped the oversell).
  - `@Transactional` removed from reservation create: the rollback test fails, `available` 1 instead of 4.
  - `accept-float-as-int` setting removed: `{"initialQuantity": 1.5}` is accepted and the SKU test fails.
- Compose stack (`docker compose up -d --build --wait`), temporary merchant and key inserted with SQL:
  SKU created on app1 (8081), reserved on app2 (8082), further reservation on app1 gets 409
  INSUFFICIENT_STOCK, availability on app2 shows 0 / 2 / 0, request without key gets 401 with
  `WWW-Authenticate: Bearer`. Movement rows carry the request ids (including the client-supplied
  `manual-check-1`). `grep` of both app logs for the API key: 0 matches.

Not done yet (later increments): Idempotency-Key handling (4), expiry worker and movement history API (5),
dev bootstrap keys, structured JSON logs, metrics, OpenAPI annotations and committed spec (6).

### Increment 4 (2026-10-11)

Built:
- `idempotency/Idempotency`: required-key check, record insert first (`ON CONFLICT DO NOTHING`), replay or
  409 IDEMPOTENCY_KEY_REUSED on an existing key, `ApiProblem` rejections recorded as problem details,
  any other exception rolls back the record with the business change.
- `ReservationService.transition`: confirm/cancel per section 6; INVALID_STATE_TRANSITION carries `currentStatus`.
- `POST /v1/reservations` now requires `Idempotency-Key`; new `POST /v1/reservations/{id}/confirm` and `/cancel`.

Tests added (HTTP, real server, Testcontainers `postgres:18.6`; 37 total):
- `IdempotencyApiTest` (6): key required / too long; retry after lost response replays the 201 (same body,
  Location, `Idempotent-Replayed: true`, stock moved once); same key with different sku, quantity, or
  holdSeconds gives 409 IDEMPOTENCY_KEY_REUSED; keys scoped by merchant and operation; a recorded 409
  INSUFFICIENT_STOCK is replayed even after stock frees up while a new key succeeds; 50 concurrent requests
  with one key give 50 x 201 with one id, exactly one not replayed, stock moved once.
- `ReservationLifecycleApiTest` (8): confirm moves held to sold; cancel returns held to available; repeated
  confirm and repeated cancel (same key replays, new key 409 INVALID_STATE_TRANSITION, opposite transition 409);
  lost confirm response retry; past-expiry confirm and cancel give 409 RESERVATION_EXPIRED and leave the
  units held (expires_at moved into the past with SQL, no sleep); other merchant / unknown / malformed id 404;
  confirm and cancel require a key.
- `ConcurrentTransitionTest` (2): 20 reservations, a confirm and a cancel for each released together: each
  reservation gets exactly one 200 and one 409 INVALID_STATE_TRANSITION, final status matches the winner,
  stock is held 0, sold 2 x confirmed, available 40 - sold; 10 concurrent confirms with different keys on one
  reservation give one 200 and nine 409, sold 3 once.
- `ReservationRollbackTest` now also checks that a failed (500) request is not recorded: retrying it with the
  same key executes normally (201, not replayed).

Evidence:
- `./mvnw -B verify` (Java 25): `Tests run: 37, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Mutation checks (temporary, reverted):
  - `FOR UPDATE` removed from the confirm/cancel read: both race tests fail; PostgreSQL rejects the second
    resolution with `movement_one_resolution_idx` (500s instead of 409s).
  - Executor ignores an existing key (no replay branch): 7 tests fail across `IdempotencyApiTest` and
    `ReservationLifecycleApiTest` (duplicate reservations, double confirms, no conflicts).
- Compose stack (rebuilt), temporary merchant and key inserted with SQL: the same Idempotency-Key sent to
  app1 and app2 in parallel gave two 201s with one Location, one marked `Idempotent-Replayed: true`;
  confirm on app1, retry with the same key on app2 gave 200 replayed; cancel afterwards gave 409
  INVALID_STATE_TRANSITION with `currentStatus` CONFIRMED; stock 3 / 0 / 2; movements STOCK_CREATED,
  RESERVED, CONFIRMED; three idempotency records (201, 200, 409).

Open items carried forward: idempotency retention purge and movement history API (5); OpenAPI response
schemas for the three idempotent endpoints, which return `ResponseEntity<String>` (6).

### Increment 5 (2026-10-11)

Built:
- `expiry/ExpirationWorker` (section 8), `ExpiryProperties`, `SchedulingConfig` (`reservecore.workers.enabled`).
- `idempotency/IdempotencyPurger`, `IdempotencyProperties`.
- `GET /v1/skus/{sku}/movements` (`InventoryService.movements`, `MovementPage`): keyset paging on movement id.
- Worker metrics: expired counter, failed-batch counter, batch timer, lag gauge.

Tests added (49 total; HTTP for all API state, worker and purge called directly):
- `MovementHistoryApiTest` (3): every change recorded newest first with exact after-quantities, reservation
  id, and the causing request's `X-Request-Id`; three pages of 3/3/1 equal the full list; bad `limit` /
  `before` give 400; another merchant's SKU gives 404.
- `ExpirationWorkerTest` (8):
  - due reservations expire and release once; a not-yet-due reservation and a confirmed one are untouched;
  - worker first, then confirm: 409 INVALID_STATE_TRANSITION (`currentStatus` EXPIRED), released once;
  - confirm past expiry first (409 RESERVATION_EXPIRED), then worker: EXPIRED, released once, no CONFIRMED movement;
  - confirmed, then its expiry passes: the worker leaves it CONFIRMED;
  - 30 confirms and 4 worker passes released together on already-expired reservations: no confirm succeeds,
    30 EXPIRED movements, full stock back;
  - boundary run: 30 reservations expiring 30 ms ahead, 30 confirms and 4 looping workers released together:
    each confirm is 200 iff the reservation ends CONFIRMED, otherwise it ends EXPIRED; one resolution
    movement each; stock adds up. Observed mixes over 7 runs: 15/15, 16/14, 19/11, 18/12, 19/11, 17/13, 17/13
    (confirmed/expired);
  - 4 concurrent workers over 200 due reservations on 2 SKUs: each expired exactly once, more than one batch
    per SKU, work split across workers (observed 60/40/50/50, 40/50/60/50, 50/50/50/50);
  - injected failure on the last EXPIRED movement of a batch: the pass throws, failure counter +1, all
    three reservations still ACTIVE, stock still held, no EXPIRED movement, lag gauge >= 1 s; after the
    trigger is removed the next passes expire all three once and the lag gauge returns to 0.
- `IdempotencyRetentionTest` (1): a record aged past 24 h is purged and its key then executes as new;
  a recent key still replays.
- `ReservationRollbackTest` now checks the movement history API instead of the table.

Evidence:
- `./mvnw -B verify` (Java 25): `Tests run: 49, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- `ExpirationWorkerTest` and `ConcurrentTransitionTest` repeated 5 more times: all green.
- Mutation checks (temporary, reverted):
  - `FOR UPDATE SKIP LOCKED` removed from the claim: 3 concurrency tests fail; PostgreSQL reports 32
    `movement_one_resolution_idx` violations (double resolutions blocked by the index).
  - Batch run without its transaction: the rollback test fails (reservations EXPIRED instead of ACTIVE).
- Compose stack (scheduler on, two instances): 300 one-unit reservations created alternately on app1 and
  app2; both apps stopped; all 300 backdated past expiry (300 ACTIVE past expiry while down); apps
  restarted: by the time both were healthy all 300 were EXPIRED (app2 logged two batches of 100, app1
  one), stock 300 / 0 / 0, 300 EXPIRED movements for 300 distinct reservations in 3 batches, no ERROR or
  exception lines in either app log.

Open items carried forward: metrics endpoint exposure, structured JSON logs, OpenAPI (6); automated
worker-restart demo (7).

### Increment 6 (2026-10-11)

Built:
- Dev bootstrap: `db/dev/R__dev_merchants.sql`, `application-dev.yml`, compose `SPRING_PROFILES_ACTIVE=dev`,
  README "Development credentials".
- Logging: ECS JSON console logs; `merchantId` in the MDC after authentication; one access line per request.
- Metrics and health: Prometheus registry and endpoint; readiness group `readinessState,db`; compose
  healthcheck now checks for HTTP 200 (a body grep for "UP" would match the `readinessState` component
  while the database is DOWN); optional `observability` profile with Prometheus.
- OpenAPI: `OpenApiConfig` (info, server `/`, bearer scheme), `ProblemResponse` documentation schema,
  `@Operation`/`@ApiResponse` on every endpoint, committed `docs/openapi.json`.
- CI workflow, README.

Tests added (60 total):
- `DevBootstrapTest` (2, `dev` profile context): both documented keys authenticate as different merchants;
  `api_key` holds the expected SHA-256 hashes and no plaintext.
- `AuthenticationApiTest` (+2): revoked key 401; dev keys 401 without the `dev` profile.
- `MerchantIsolationTest` (2): Bob gets 404 with the right code on all six merchant-scoped operations
  against Alice's SKU and reservation, and Alice's stock, history and reservation are unchanged; same SKU
  code and same Idempotency-Key are independent per merchant.
- `ObservabilityTest` (3): exactly one JSON access line per request with `requestId`, `merchantId`,
  method, path, status, duration and ECS `log.level` / `service.name`; none for an anonymous request's
  merchant; API key and its secret absent from all captured output; `/actuator/prometheus` without a key
  serves HTTP, worker and HikariCP metrics; liveness and readiness UP with the `db` component.
- `OpenApiSpecTest` (2): served spec equals `docs/openapi.json`; every operation present, bearer scheme,
  required `Idempotency-Key` header and `Problem` 409 schema on the three idempotent operations,
  `ReservationView` 201 schema.

Evidence:
- `./mvnw -B verify` (Java 25): `Tests run: 60, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Mutation check (reverted): merchant filter removed from the reservation read: `MerchantIsolationTest`
  fails (`expected: 404 but was: 200`).
- OpenAPI drift check: spec matched on two fresh runs; a hand edit of `docs/openapi.json` made the test
  fail with "docs/openapi.json is out of date; run ./mvnw verify -Dopenapi.update=true and commit it".
- Compose (fresh volume, `--profile observability`): all services healthy; app1 applied 2 migrations (V1
  and the dev repeatable); Alice created HOODIE-M on app1 and reserved on app2 (201, stock 19 / 1 / 0);
  Bob reading it got 404; app2's access line for request `compose-check-6` carried Alice's merchant id,
  POST, `/v1/reservations`, 201; no log line contains a dev key; Swagger UI 200; Prometheus `up` = 1 for
  app1:8080 and app2:8080. With PostgreSQL stopped, readiness answered 503 (`db` DOWN) and liveness 200;
  after restart readiness was 200 again. Correction (increment 7): that drill restarted the database with
  `docker compose up --wait postgres app1 app2`, which also restarted the app containers, so it did not show
  recovery without a restart. The increment 7 outage drill does (app containers untouched).
- `node ~/.claude/tools/floor-guard.mjs`: clean (scratch copy with an empty base commit; the repo has no commits).

Not verified: the GitHub Actions workflow has not run (the repo is not pushed); its YAML parses and the
same `./mvnw -B verify` passes locally.

### Increment 7 (2026-10-11 to 2026-10-12)

Built:
- `demo` module (JDK HttpClient, virtual threads, Jackson; repackaged into one executable jar):
  `Api`, `Checks` (PASS/FAIL lines, exit code), `LoadRun`, `LatencySummary` (nearest-rank percentiles),
  `Scenarios` (`lifecycle`, `contention`, `expiration`, `duplicates`, `worker-restart`), `Bench`, `DemoMain`.
- `ops/consistency-check.sql`: read-only ledger checks, fails with a non-zero psql exit on any discrepancy.
- CI: second job `demo` (compose up, `reservecore-demo all`, consistency check, logs on failure).
- Docs: README (problem, workflow, quick start, demonstration, credentials, observability, limitations),
  `docs/ARCHITECTURE.md`, `docs/RUNBOOK.md`, `docs/BENCHMARK.md`, raw outputs in `docs/benchmark/`.

Tests added: `LatencySummaryTest` (4, demo module): percentiles of 1..100 ms and of a 4-value sample,
sub-millisecond values, empty sample; expected values computed by hand. 64 tests in total (60 service, 4 demo).

Evidence (all on the compose stack unless stated):
- `java -jar demo/target/reservecore-demo.jar all` on a fresh stack: `40 passed, 0 failed`, exit 0
  (`docs/benchmark/2026-10-12-demo-all.txt`). Contention: 20 created, 980 x 409 INSUFFICIENT_STOCK,
  0 unexpected, stock 0/20/0 on both instances. Expiration: holds reclaimed 259 ms after `expiresAt`.
  Worker restart: both instances killed before the first of 30 holds expired, restarted after all expired,
  all 30 EXPIRED within 92 ms of both instances reporting ready, 30 EXPIRED movements for 30 reservations.
  Second run on the same database: 40 passed, exit 0. `contention --concurrency=1000`: 6 passed, exit 0.
- Benchmark: two sessions of 18 measured runs plus warm-ups, 36 of 36 correctness checks passed, zero
  unexpected responses; results and environment in `docs/BENCHMARK.md`.
- Consistency check after demo and benchmarks (20,532 reservations, 20,655 movements, 43,084 idempotency
  records): all checks 0, exit 0. With one EXPIRED movement deleted inside a rolled-back transaction: 2
  offending rows reported, psql exit 3.
- Backup drill: `pg_dump -Fc` (3.2 MB) while the apps ran, restored into a scratch database with
  `pg_restore --exit-on-error`: identical row counts and Flyway history, consistency check passed on the copy.
- Key issuance drill (runbook commands): generated a 43-character secret, inserted prefix and SHA-256 only,
  the key authenticated (404 for an unknown SKU), revocation gave 401 on the other instance on the next
  request, the secret appeared in no log line.
- Database outage drill: PostgreSQL container stopped about 100 s with the app containers untouched (their
  start times unchanged afterwards). API write: 500 INTERNAL_ERROR after 0.06 s; liveness 200; a readiness
  request and Prometheus scrapes did not answer within 30 s (database health check and lag gauge wait for a
  pool connection, HikariCP default timeout 30 s); 5 expiration batches failed and were logged, counters 3
  and 2 afterwards. After PostgreSQL started, both instances were ready within 5 s, a hold that expired
  during the outage was reclaimed within 1 s, a retry of the failed request with the same key executed (201),
  and the consistency check passed. Recorded in the runbook and README limitations; not changed in code
  because the query-on-read lag gauge was an agreed increment 5 design. Options if wanted: an in-memory
  "seconds since last successful pass" gauge instead of the database query, and a shorter connection
  timeout for health checks.
- Fresh-checkout run: only the files git would track copied to a scratch directory (no `target/`), then the
  CI commands: `./mvnw -B verify` (60 + 4 tests, BUILD SUCCESS); `./mvnw -B -pl demo -am package`;
  `docker compose up -d --build --wait --wait-timeout 300` (all healthy); `reservecore-demo all`
  (40 passed, exit 0); consistency check (all 0, exit 0); `docker compose down -v`.
- `./mvnw -B verify` in the repository (Java 25): service `Tests run: 60, Failures: 0, Errors: 0, Skipped: 0`,
  demo `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- floor-guard (scratch copy, empty base commit): one finding, `[unfinished-work] docs/BENCHMARK.md` for the
  sentence describing a hot-SKU optimization that is not implemented. Intended: it documents a possible
  future change outside the brief's scope, not unfinished work in this increment.

Not verified: the GitHub Actions workflow on GitHub itself (repository not pushed).

### Follow-up after increment 7 (2026-10-12)

Agreed before coding: fix the outage behaviour found in increment 7 with both changes (worker gauges that
never query the database on read, HikariCP `connection-timeout` 5000 ms), then show the system working with
resource utilisation sampled during a live run. Nothing committed.

Built:
- `ExpirationWorker`: lag measured at the end of each successful pass and held in memory; new gauge
  `reservecore.expiry.last.success.age`. `application.yml`: `spring.datasource.hikari.connection-timeout: 5000`
  (HikariConfig binds milliseconds; `5s` failed startup with a NumberFormatException, caught by the tests).
- `ops/sample-utilisation.sh` (bash 3.2 compatible): CSV of container CPU and memory, pool active and
  waiting, heap, platform threads, request count, PostgreSQL sessions, about every 3 s.
- Tests: `ExpirationWorkerTest` now asserts that a failed pass keeps the lag and does not reset the age
  (age >= time elapsed since the last successful pass), that recovery resets it, and a new
  `lagReportsTheOldestDueReservationLeftAfterAPass` (150 due one hour ago, one pass of 100 leaves lag >= 3600 s,
  draining returns 0). `ObservabilityTest` checks the new gauge on `/actuator/prometheus`.
  Mutation check: resetting the age at the start of a pass first survived the original assertion, which was
  then tightened; the tightened test fails on the mutant and passes on the real code.

Evidence:
- `./mvnw -B verify`: service `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0`, demo 4, `BUILD SUCCESS`.
- Outage drill (PostgreSQL container stopped, apps untouched and not restarted): API 500 after 0.05 s and then
  5.03 s (was up to 30 s); readiness 503 in 5.03 s (was no answer in 30 s); `/actuator/prometheus` 200 in
  0.06 s with `last_success_age` 15.6 s and 2 failed batches (was no answer in 30 s); both instances ready 2 s
  after PostgreSQL started; expired hold reclaimed; same-key retry 201; consistency check passed.
- Utilisation run (docs/BENCHMARK.md, "Resource utilisation"): `demo all` 40 passed, exit 0; under the
  benchmark each instance had 10 of 10 pool connections busy with up to 419 / 465 requests waiting, app CPU at
  most 3.9 cores, PostgreSQL about 1.2 cores, app memory at most 526 MiB, PostgreSQL 108 MiB.
- Consistency check after all runs: every check 0, exit 0.
- Open finding (docs/BENCHMARK.md, README limitations): 4 of 17 benchmark processes with the 5 s timeout lost
  5 to 7 of 1,000 requests in one concurrency-1000 run at the connection level; the requests never reached the
  application (993 and 995 idempotency records in the two runs checked); 0 of 9 processes with 30 s, 0 of 10
  single-run processes, 0 of 8 on fresh containers with 5 s. Cause not identified; investigation stopped there.

### Decisions and additions (2026-10-12, later)

- Pool connection timeout: kept at 5 s (decided on the user's "do what seems best"). Reason: the measured
  benefit (requests and readiness fail in 5 s instead of hanging over 30 s during an outage) is real, while in
  none of the lost-connection runs did any request time out waiting for the pool (no 500s, no errors logged),
  so the setting has no plausible way to cause them. The finding stays open in docs/BENCHMARK.md.
- Launch video with the `brag` skill (https://github.com/latent-spaces/brag, commit 8531ccb), installed outside
  the project: cloned to `~/Documents/brag`, skills linked as `~/.claude/skills/brag` and
  `~/.claude/skills/brag-slim` (same pattern as diagram-design). On Opus 5.5 `/brag` hands off to `/brag-slim`,
  which needs no Hyperframes; Homebrew `ffmpeg` 9.0.2 was installed for encoding. Output in `brag-output/`:
  `brag.mp4` (20.7 s, 1920x1080, 30 fps, H.264 + AAC, 2.7 MB), `brag.jpg` (poster, baked in as frame 0),
  `brag-plan.md`, `share-copy.txt`; intermediate files in `brag-output/work/` are git-ignored. (Before the first push the deliverables moved to
  `docs/media/` as `launch-video.mp4`, `launch-video-poster.jpg`, `launch-video-plan.md` and
  `launch-video-share-copy.txt`, and the decks to `docs/presentation/`; the intermediate files were not kept in
  the repository.) Every number
  in the video comes from recorded runs (demo output and docs/benchmark/); the API scene shows a screenshot
  of the running Swagger UI. Frames were rendered by headless Chrome (`work/capture.mjs`, DevTools protocol,
  no npm packages) from `work/video.html`; the soundtrack is synthesized by `work/music.py`.

### Senior review (2026-10-12)

Review findings, in impact order, from reading the hot paths: (1) a reservation held the SKU row lock for four
round trips after taking it; (2) rejections ran the same conditional UPDATE; (3) confirm and cancel held the
inventory lock for four round trips including a re-read; (4) lock waits were unbounded, so one stalled
transaction could pin the pool; (5) the worker inserted ledger rows one round trip at a time while holding
inventory locks; (6) no business-outcome metrics, and probes and scrapes flooded the access log; (7) API-key
lookup costs a second pooled connection per request (not changed: caching would delay revocation).

Changed:
- `ReservationService`: reserve, confirm and cancel each write stock, reservation and ledger row in one
  data-modifying CTE; expire on touch (an overdue hold found by confirm or cancel is expired in the same
  statement, 409 RESERVATION_EXPIRED, EXPIRED ledger row with the request id); `get` reports the effective status
  with `reclaimPending`. `ReservationView` gains `reclaimPending`.
- `Idempotency`: `SET LOCAL lock_timeout` (from `reservecore.lock-timeout`, 2 s) at the start of every API
  transaction; `reservecore.operations` counter by operation, outcome and replay.
- `ProblemDetailsAdvice`: SQLSTATE 55P03 becomes 503 SERVICE_BUSY with `Retry-After: 1`.
- `ExpirationWorker`: one JDBC batch for a SKU's EXPIRED ledger rows.
- `RequestIdFilter`: actuator requests are not access-logged.
- OpenAPI: 503 on the three writes, `reclaimPending`, effective-status wording; `docs/openapi.json` regenerated.
- Demo: waits for `reclaimPending` to clear before treating a hold as reclaimed.
- Test client: 30 s request timeout, so a lock bug fails a test instead of hanging the build (the first red run
  of `LockTimeoutTest` hung exactly that way, which is the bug the timeout fixes).

Tried and removed: a lock-free availability precheck. The A/B gave no gain and the mechanism explains why:
PostgreSQL does not lock a row that fails the WHERE clause, so after stock is gone the conditional UPDATE already
returns without waiting, and in a burst every request reads the pre-burst value. It only added a round trip.

Tests (70 in total: 66 service, 4 demo):
- `LockTimeoutTest` (2, new): a row lock held by the test on a second connection makes a reserve and a confirm
  answer 503 SERVICE_BUSY after about 2 s with `Retry-After: 1`; the same key then succeeds (not replayed).
- `ReservationLifecycleApiTest`: first confirm after expiry expires the hold (409, units released, EXPIRED row
  with that request's id), a following cancel gets INVALID_STATE_TRANSITION.
- `ExpirationWorkerTest` (+1 new, 2 updated): overdue hold reads EXPIRED with `reclaimPending` true until the
  worker runs; confirm past expiry expires it and the worker finds nothing; a failed batch leaves holds reading
  `reclaimPending` true.
- `ObservabilityTest` (+2 new): outcome counter by code and replay; actuator probes not access-logged.

Evidence:
- `./mvnw -B verify`: service `Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`, demo 4, `BUILD SUCCESS`.
- Mutation checks (reverted): CTE without `available >= :q` (precheck alone): the concurrency test fails with a
  500 (oversell stopped by the CHECK constraint); expiry check removed from the resolving CTE: both expire-on-touch
  tests fail.
- Hot-path performance: interleaved A/B, 9 runs per cell per variant, client inside the compose network
  (docs/BENCHMARK.md, "Hot-path engineering"): no difference beyond the run-to-run variance (about ±20%);
  wait-event sampling: 73% of session time queued on the row lock, 13% waiting for the application inside a
  transaction, 6% WAL; `fdatasync` 59 µs. The CTEs are kept for fewer statements and one atomic write, without a
  throughput claim; the next lever (server-only transaction for the idempotency record) is proposed in
  docs/ARCHITECTURE.md.
- Lost-connection finding localised to Docker Desktop's port forwarding (accept-count hypothesis falsified;
  0 losses with the client inside the Docker network).
- Compose stack on the final build: `reservecore-demo all` 40 passed, exit 0; `ops/consistency-check.sql`
  passed.
