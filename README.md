# ReserveCore

ReserveCore is a PostgreSQL-backed inventory reservation service. It provides an HTTP API for creating, confirming, and cancelling reservations, with automatic expiration, idempotent writes, and a ledger of stock changes.

The service uses Java 25, Spring Boot 4.1.1, and Spring JDBC. PostgreSQL stores all application state and coordinates concurrent requests across service instances. Transactions, row locks, and database constraints enforce stock balances and prevent duplicate reservation processing.

## Local setup

Local setup requires Docker with Compose v2. The Docker build compiles the service. JDK 25 is required locally only for tests and the demo client.

From the repository root:

```sh
docker compose up -d --build --wait
curl --fail http://localhost:8081/actuator/health/readiness
```

Compose starts PostgreSQL 18.6 and two service instances:

| Service | Address |
| --- | --- |
| API instance 1 | `http://localhost:8081` |
| API instance 2 | `http://localhost:8082` |
| Swagger UI | `http://localhost:8081/swagger-ui.html` |
| PostgreSQL | `localhost:55432`, database and user `reservecore` |

Flyway applies database migrations at startup. Compose enables the `dev` profile, which creates two test merchants with these public development keys:

```text
Alice: rcsk_devalice_DEVELOPMENT-ONLY-not-a-secret-alice-0000001
Bob:   rcsk_devbob00_DEVELOPMENT-ONLY-not-a-secret-bob-000000001
```

These credentials are for local use. Outside the `dev` profile, create merchants and API keys using the [runbook](docs/RUNBOOK.md#api-keys-drilled). The database stores each key's prefix and SHA-256 hash.

Stop the stack with `docker compose down`. The database volume is preserved between runs.

## API

Every `/v1` request requires `Authorization: Bearer <key>`. The API key identifies the merchant; requests do not need a separate merchant ID. A merchant can only read or change its own data. Requests for another merchant's SKU or reservation return 404.

| Method | Path | Result |
| --- | --- | --- |
| POST | `/v1/skus` | Create a SKU and its initial stock; 201 |
| GET | `/v1/skus/{sku}` | Read initial, available, held, and sold quantities; 200 |
| GET | `/v1/skus/{sku}/movements` | Read stock movements, newest first; 200 |
| POST | `/v1/reservations` | Hold units; 201 |
| GET | `/v1/reservations/{id}` | Read a reservation, including whether its hold has expired; 200 |
| POST | `/v1/reservations/{id}/confirm` | Convert held units to sold; 200 |
| POST | `/v1/reservations/{id}/cancel` | Return held units to available stock; 200 |

SKU codes accept 1–64 characters from `A–Z`, `a–z`, `0–9`, `.`, `_`, and `-`. Quantities must be positive integers. `holdSeconds` defaults to 600 and accepts 5–3600 with the default configuration.

Movement history accepts `limit` (default 50, maximum 200) and `before`. Pass the response's `nextBefore` value as `before` to fetch the next page.

The committed [OpenAPI specification](docs/openapi.json) contains the request and response schemas. `OpenApiSpecTest` checks it against the generated API document during the build.

### Request format

Reservation requests use JSON. The following request assumes the SKU already exists; use `POST /v1/skus` to create it first.

```http
POST /v1/reservations
Authorization: Bearer <api-key>
Idempotency-Key: reservation-001
Content-Type: application/json

{"sku":"SKU-001","quantity":1,"holdSeconds":600}
```

A successful request returns `201 Created`, a reservation object, and a `Location` header containing the reservation path. Use the returned ID to read, confirm, or cancel the reservation.

### Retries and idempotency

An idempotency key lets you retry a request without applying the same change twice. Reserve, confirm, and cancel require an `Idempotency-Key` of 1–255 printable ASCII characters. Each key belongs to one merchant and one operation, such as reserve or confirm. SKU creation does not use idempotency keys; creating the same SKU again returns `409 SKU_ALREADY_EXISTS`.

For reserve, confirm, and cancel requests:

- Sending the same key with the same request values returns the saved status and body. Reusing that key with different values returns `409 IDEMPOTENCY_KEY_REUSED`.
- The stock change, reservation, ledger entry, and saved response are committed together in one transaction. If duplicate requests arrive at the same time, they wait for the first transaction. They return its saved response if it commits, or try the operation themselves if it rolls back.
- Successful responses and rejections such as insufficient stock are saved. Authentication failures, validation errors, and transactions that roll back are not saved.
- By default, a cleanup worker deletes saved responses once they are more than 24 hours old. After a response is deleted, a request using its key is treated as new.

Reuse the original key when retrying after a connection failure or `503 SERVICE_BUSY`. The saved response describes the original result. To check the reservation's current status, use `GET /v1/reservations/{id}`.

## Consistency and concurrency

Database constraints enforce nonnegative stock counts and the following balance:

```text
initial_quantity = available + held + sold
```

A reserve request updates the inventory row only when `available >= quantity`. The same SQL statement creates the reservation and its ledger entry. When requests compete for the same SKU, a row lock makes them update its stock one at a time. Each request checks the available quantity before taking any units.

Confirm and cancel lock the reservation before changing stock. The service checks the deadline using the database clock, `clock_timestamp()`, after it acquires the lock. A request that waited too long cannot confirm an expired hold. A partial unique index also prevents the ledger from recording more than one confirmation, cancellation, or expiration for a reservation.

The reservation states are:

- `ACTIVE`: units are held and can be confirmed or cancelled before `expiresAt`.
- `CONFIRMED`: held units have moved to sold.
- `CANCELLED`: held units have returned to available stock.
- `EXPIRED`: the hold deadline has passed. Units return to available stock when expiration is processed.

Once a reservation is confirmed, cancelled, or expired, it cannot change to another state. A read can return `EXPIRED` with `reclaimPending: true` before the background worker has released the reserved units. Those units remain held until release. A confirm or cancel request that finds an expired but unreleased hold releases the stock itself and returns `409 RESERVATION_EXPIRED`.

Each instance runs an expiration worker with a one-second delay between passes by default. Workers use `FOR UPDATE SKIP LOCKED` to process separate batches, skipping reservations locked by another worker or request. They update inventory in ascending ID order and save the stock changes and ledger entries in one transaction. If a batch fails, its changes roll back and a later pass can retry it.

Queries filter by merchant ID. Composite foreign keys also include the merchant ID, preventing references to another merchant's data.

See [Architecture](docs/ARCHITECTURE.md) for the transaction boundaries, lock ordering, and design tradeoffs.

## Build and verify

Install JDK 25 and make it the active Java version. Docker must be running for the Testcontainers tests. The repository includes the Maven wrapper.

```sh
./mvnw verify
```

The service tests use PostgreSQL 18.6 in Testcontainers. They cover concurrent reservations and transitions, duplicate requests, rollback, merchant isolation, expiration, lock timeouts, movement history, and the OpenAPI contract. The demo module also tests latency calculations.

After the build, run the demo client against the Compose stack:

```sh
java -jar demo/target/reservecore-demo.jar lifecycle
java -jar demo/target/reservecore-demo.jar contention
java -jar demo/target/reservecore-demo.jar duplicates
java -jar demo/target/reservecore-demo.jar expiration
```

The contention scenario submits 1,000 one-unit requests against 20 units of stock and checks for exactly 20 successful reservations. The duplicate scenario checks that concurrent retries apply a reserve or confirm once.

For the complete set, including recovery after killing and restarting both local service instances:

```sh
java -jar demo/target/reservecore-demo.jar all
```

Run the consistency check to verify that inventory counts agree with the reservations and ledger entries:

```sh
docker compose exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U reservecore -d reservecore \
  < ops/consistency-check.sql
```

It exits with a nonzero status if the records do not agree. The [GitHub Actions workflow](.github/workflows/ci.yml) defines build/test and demo/consistency jobs.

## Configuration and operations

Outside Compose, set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. The service ships without default database credentials.

| Property | Default | Purpose |
| --- | --- | --- |
| `reservecore.lock-timeout` | `2s` | Limit row-lock waits in API transactions |
| `reservecore.hold.default-seconds` | `600` | Hold duration when omitted from a request |
| `reservecore.hold.min-seconds` / `max-seconds` | `5` / `3600` | Accepted hold duration range |
| `reservecore.workers.enabled` | `true` | Run expiration and idempotency cleanup on this instance |
| `reservecore.expiry.interval` | `1s` | Delay between expiration passes |
| `reservecore.expiry.batch-size` / `max-batches-per-tick` | `100` / `10` | Work limit per expiration pass |
| `reservecore.idempotency.retention` | `24h` | How long to keep saved responses before cleanup |
| `reservecore.idempotency.purge-interval` / `purge-batch-size` | `1m` / `1000` | Cleanup interval and rows per delete |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Database connections per instance |
| `spring.datasource.hikari.connection-timeout` | `5000` ms | Maximum wait for a pooled connection |

The service writes structured JSON logs in ECS format to standard output. Logs include request IDs and, after authentication, merchant IDs. `/actuator/health/liveness` reports whether the application is alive. `/actuator/health/readiness` also checks the database connection before reporting that the service is ready.

`/actuator/prometheus` exposes metrics for HTTP requests, the JVM, the database connection pool, API results, and expired holds. Monitor expiration lag and the time since the last successful worker pass. During a database outage, the recorded lag stays at its last measured value, but the time since success keeps increasing.

To add Prometheus on port 9090:

```sh
docker compose --profile observability up -d
```

Actuator endpoints do not require an API key and use the same port as the API. When deploying, use your firewall or reverse proxy to keep them off the public internet. The [runbook](docs/RUNBOOK.md) covers key provisioning, backups, restore, worker failures, and database outages.

## Performance

The initial benchmark sessions ran on an Apple M4 with 16 GB RAM and Docker Desktop, using two service instances with ten database connections each. Each run submitted 1,000 one-unit reservation requests for a single SKU.

| Workload | Recorded throughput |
| --- | --- |
| Sufficient stock for all requests | Approximately 1,100–1,500 successful reservations/second |
| Stock available for 2% of requests | Approximately 1,900–3,500 requests/second, including insufficient-stock responses |

See [Benchmark results](docs/BENCHMARK.md) for the measurement method, latency percentiles, and raw results.

To run the benchmark:

```sh
java -jar demo/target/reservecore-demo.jar bench
```
