# Procedure Guidelines: Inventory Reservation Service

Status: guidelines recorded 2026-10-11. Stack settled in docs/DECISIONS.md.
This file is the source brief. The spec, plan, and architecture notes derive from it.

## Purpose

Businesses selling limited stock must prevent multiple customers from buying the
same remaining units. Example: 20 hoodies, 500 simultaneous checkouts. The service
reserves stock temporarily, confirms successful purchases, and releases abandoned
reservations without overselling or processing repeated requests twice.

Users are developers building storefronts or checkout apps, integrating over HTTP.
Focused backend project plus a small demo client.

## Workspace rules

- Read the existing repository and any AGENTS.md before editing.
- If an app exists, fit in without disrupting unrelated functionality; otherwise
  initialize a standalone project. (Result: standalone project, no AGENTS.md found.)
- Explain proposed structure and assumptions, write the implementation plan into the
  repository, then start working. Ask only when a consequential choice cannot be inferred.

## Required technology (from the brief)

- Java with Spring Boot
- PostgreSQL as the authoritative store
- Flyway migrations
- Maven builds
- Docker Compose for local development
- JUnit + Testcontainers against real PostgreSQL
- OpenAPI documentation
- Structured logs, health checks, operational metrics
- Choose compatible supported versions, verify them in official docs, pin dependencies.

## Scope, version one

- One application: HTTP API + background expiration worker.
- Multiple instances must run safely against the same database.
- One reservation = one SKU, positive quantity, one merchant.
- Merchants manage their own inventory.
- No real payments, no multi-item checkout, no microservices, no message broker.
- Redis only if measurements later establish a specific need.

## Business workflow

1. Merchant creates a SKU with initial stock.
2. Storefront requests a temporary reservation during checkout.
3. Service checks availability and creates the reservation atomically.
4. Reserved units become unavailable to others.
5. Storefront confirms after its external checkout succeeds.
6. Confirmation converts held units into sold units.
7. Cancellation releases held units immediately.
8. Expiration releases held units when checkout is abandoned.

Confirmation = merchant reporting a completed purchase; no charging or payment verification.
Reservation duration is configurable with documented bounds.

## State machine

States: ACTIVE, CONFIRMED, CANCELLED, EXPIRED.
Transitions: ACTIVE -> CONFIRMED, ACTIVE -> CANCELLED, ACTIVE -> EXPIRED. Terminal states never transition.

- Confirmation fails when an ACTIVE reservation is past expiry, even if the worker has not processed it.
- Expiry evaluated with database time inside the protected transition.
- Boundary: at or after `expires_at`, confirmation is not allowed.
- Document how expired reservations are reclaimed and how temporarily unreclaimed stock affects availability.

## Correctness requirements

- Available stock never negative.
- Reservation confirmed at most once.
- Held stock released at most once.
- Never both confirmed and expired.
- Repeated requests never create duplicate reservations or stock movements.
- Merchants cannot access other merchants' resources.
- Restarts cannot lose reservations or leave transitions partially applied.

Invariant: `initial stock = available + active held + sold`. Define each quantity explicitly;
do not conflate with physical warehouse fulfillment.

- Use PostgreSQL transactions, constraints, and appropriate concurrency control.
- Availability check and deduction are never separate unprotected operations.
- No in-process locks (requests hit different instances).
- Consistent, documented lock ordering to reduce deadlocks.
- Short transactions, no external network calls inside them.
- Reservation changes, inventory changes, and audit records persist atomically.

## Idempotency

- `Idempotency-Key` required for create, confirm, cancel. Scoped by merchant + operation.
- Same key + equivalent request: replay recorded result, no repeated effects.
- Same key + different request: conflict.
- Concurrent same key: one logical operation.
- Lost response after commit: retry returns recorded result.
- State persisted in PostgreSQL, coordinated with the business transaction.
- Document retention, replay behavior, and which error responses are retained.
  A replay represents the original operation; clients fetch the resource for current state.

## API

Endpoints: create SKU + initial inventory; get availability; create reservation; get
reservation; confirm; cancel; paginated inventory movement history.
Provide sample requests/responses, status codes, consistent error schema.
Clear errors for: insufficient stock, expired reservation, conflicting transition, invalid
quantity, missing resource, auth failure, idempotency conflict. Request IDs on every response.

## Security

- Small merchant API-key auth; store key hashes, never plaintext.
- Documented local bootstrap with explicit development credentials, no production defaults.
- Merchant isolation enforced in database access.
- Never log keys or secrets; keep secrets out of Git.

## Expiration worker

- Durable; bounded batches; safe concurrently across instances.
- Coordinates with confirm/cancel; recovers after crashes; never releases stock twice.
- Exposes failures and lag via logs and metrics.
- No in-memory timer per reservation.
- Document transaction boundaries and recovery. If leases are used, explain lease expiry and
  stale-worker protection; otherwise prefer a simpler transactional design.

## Implementation increments

1. Spec, state machine, invariants, API outline, implementation plan.
2. Application, PostgreSQL, migrations, local startup.
3. Inventory + reservation creation with concurrency protection.
4. Confirmation, cancellation, persistent idempotency.
5. Expiration + transactional audit history.
6. Authentication, merchant isolation, documentation, observability.
7. Demo, failure scenarios, measured results.

After each increment: run relevant checks, update the plan. Continue through implementation.

## Verification

Real PostgreSQL integration tests covering:
- Many requests competing for limited stock
- Concurrent requests sharing an idempotency key
- Conflicting payloads sharing a key
- Confirmation racing expiration
- Cancellation racing confirmation
- Repeated confirmation and cancellation
- Expiration across multiple worker instances
- Rollback without partial stock changes
- Retry after a committed operation whose response was lost
- Application restart and recovery
- Cross-merchant access attempts

Avoid sleep-based race tests; use coordination or an injectable time abstraction.

Demonstration:
- Seed 20 units; send 1,000 independent one-unit requests with distinct keys and a hold long
  enough to finish the run.
- Exactly 20 succeed, the rest get insufficient-stock, inventory stays consistent.
- Separate expiration, duplicate-request, and worker-restart demos.
- Measure throughput, latency percentiles, business rejections, unexpected errors separately.
- Record hardware, configuration, concurrency, dataset. Never invent benchmark results.

## Deliverables

- Working application and migrations
- Docker Compose setup
- Integration tests and CI configuration
- OpenAPI documentation and example commands
- Demo client or script
- README: user problem, workflow, setup, demonstration
- Architecture notes: transaction boundaries, concurrency, idempotency, expiration, recovery
- Operational runbook: configuration, backups, worker failures, inventory discrepancy investigation
- Honest limitations section and measured benchmark report

Done means: a fresh checkout starts locally with documented commands, demonstrates the full
reservation lifecycle, and passes the correctness checks. Untested behavior and operational
limitations are stated explicitly.

## Working rules for this repo (from global instructions)

- Smallest change that solves the stated problem; no speculative abstractions.
- Every increment ends with evidence: test run or command output.
- Library-version-dependent code is checked against official docs for the pinned version.
- Run `node ~/.claude/tools/floor-guard.mjs` before reporting a code task done.
- Plain text, no emojis in docs.

## Tech stack decision

Settled 2026-10-11. See `docs/DECISIONS.md`.
