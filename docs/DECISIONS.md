# Decisions

Settled 2026-10-11. Change only with a new dated entry explaining why.

## Stack

| Area | Decision | Why |
|---|---|---|
| Language | Java 25 LTS | 24 (installed default) is out of support; 25 is LTS to 2031 |
| Framework | Spring Boot 4.1.x, latest patch | 3.5 support ended 2026-06; 4.0 support ends 2026-12 |
| Database | PostgreSQL 18 (Docker) | Current major; built-in `uuidv7()` for time-ordered IDs |
| Migrations | Flyway (Boot-managed version, plus PostgreSQL database module) | Required by brief |
| Data access | Spring `JdbcClient` with explicit SQL, no JPA | Row locks, conditional updates and `SKIP LOCKED` stay visible |
| Build | Maven via Maven wrapper; modules `service` and `demo` | Reproducible builds, one toolchain |
| Tests | JUnit 5 + Testcontainers on `postgres:18` | Real transactional behavior |
| API docs | springdoc-openapi, code-first; generated spec committed, CI fails on unexpected diff | Docs stay in sync with code |
| Observability | Boot structured JSON logging, Actuator health, Micrometer + Prometheus endpoint, request-ID filter with MDC | Built-in, no extra encoders |
| Auth | Merchant API keys, SHA-256 hashed, looked up by key prefix | High-entropy keys do not need bcrypt |
| Merchant isolation | `merchant_id` in every query plus composite FKs including `merchant_id` | RLS listed as possible later hardening |
| Demo client | Java `demo` module, JDK HttpClient + virtual threads | Load run plus scenario checks with pass/fail |
| Compose | PostgreSQL + 2 app instances on separate ports; Prometheus in an optional profile; no Grafana | Exercises multi-instance safety without a load balancer |
| CI | GitHub Actions (ubuntu runner has Docker for Testcontainers) | Standard |
| Cache/broker | None (no Redis, no broker) | Out of scope unless measurements show a need |

Exact versions are pinned in `pom.xml` only after checking each on its official page
(spring.io, Maven Central, docs for that version). Cite the source in the commit or plan.

## Core design choices

- Reservation create: one conditional `UPDATE inventory SET available = available - :q ... WHERE available >= :q RETURNING`,
  then insert reservation, movement, and idempotency record in the same transaction.
- Lock order for confirm/cancel/expire: reservation row first (`FOR UPDATE`), then inventory row.
- Expiry check uses database `now()` inside the locked transition. At or after `expires_at`, confirm fails.
- Expiration worker: scheduled, `SELECT ... WHERE status='ACTIVE' AND expires_at <= now() ... FOR UPDATE SKIP LOCKED LIMIT :batch`,
  one transaction per batch. No leases.
- Idempotency: table unique on (merchant_id, operation, idempotency_key) with request hash and stored response,
  written in the business transaction. Concurrent same-key requests serialize on the unique index.
- Invariant enforced by CHECK constraints: `initial = available + held + sold`, all non-negative.

## Repo

- Standalone git repo `ReserveCore`, to be pushed to GitHub under savsuth.
- Portfolio commit rule: every commit message is exactly `updated features`, no trailers.
