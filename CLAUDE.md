# ReserveCore

Inventory reservation backend: Spring Boot + PostgreSQL service that holds stock during checkout,
confirms or cancels holds, expires abandoned ones, and never oversells or double-applies a request.

## Read first

- `docs/PROCEDURE.md` - the full brief: requirements, invariants, increments, verification, deliverables.
- `docs/DECISIONS.md` - settled stack and core design choices. Do not reopen them without asking.
- `docs/PLAN.md` - implementation plan and progress (create it in increment 1, update after each increment).

## Environment

- Use Java 25 for this project only:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
  The machine default is Java 24; do not change it globally.
- Maven 3.9.9 is installed; generate and use the Maven wrapper (`./mvnw`).
- Docker 28 + Compose v2 available; Testcontainers uses it.
- Hardware for benchmark notes: Apple M4, 16 GB RAM, Docker allotted 10 CPU / ~10 GB.

## Rules

- Work through the increments in `docs/PROCEDURE.md` in order. Do not stop after planning.
- Smallest change that solves each increment. No speculative abstractions.
- Pin versions only after checking the official page for that exact version; cite it in `docs/PLAN.md`.
- Every increment ends with evidence: `./mvnw verify` output or a command run. Fix code to go green;
  never skip, delete, or weaken a test. Avoid sleep-based race tests.
- Never invent benchmark numbers. Record hardware, config, concurrency, dataset with each result.
- No secrets in Git. Never log API keys. Dev credentials are explicit and documented.
- Plain text, no emojis in code comments, docs, or chat.
- Git: commit messages are exactly `updated features`, no trailers. Commit only when the user asks.
- Before reporting a code task done: `node ~/.claude/tools/floor-guard.mjs` and resolve or explain findings.
