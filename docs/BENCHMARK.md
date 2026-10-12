# Benchmark report

Every number below comes from a real run on 2026-10-12; the raw console output of each run is in
`docs/benchmark/`. Nothing is estimated or extrapolated. These are laptop measurements of one hot SKU,
useful for orders of magnitude and for comparing configurations on the same machine, not as capacity
figures for a production deployment.

## Environment

| Item | Value |
|---|---|
| Host | Apple M4, 10 cores, 16 GB RAM, macOS 15.1 |
| Docker | Docker Desktop, server 28.3.0, Compose 2.38.1; VM: 10 CPUs, 9.7 GiB memory |
| Topology | `docker compose up`: PostgreSQL 18.6 + two app containers (app1 :8081, app2 :8082); load client on the host, same machine |
| App | ReserveCore 0.1.0-SNAPSHOT, Spring Boot 4.1.1, OpenJDK 25.0.4.1 (Temurin 25 JRE image), virtual threads on, default container heap (MaxHeapSize 2.43 GiB), ECS JSON logging to stdout (one access line per request) |
| Connection pool | HikariCP: 10 connections per instance, 20 in total; connection timeout 30 s (default) for sessions 1 and 2, 5 s for the utilisation run |
| PostgreSQL | 18.6 official image, default configuration: `shared_buffers` 160 MB, `max_connections` 100, `synchronous_commit` on, `fsync` on, `wal_level` replica |
| Client | `reservecore-demo bench`, OpenJDK 25.0.4.1 on the host, JDK HttpClient (HTTP/1.1), virtual threads |

## Method

- Request: `POST /v1/reservations`, one unit, a distinct `Idempotency-Key` per request, `holdSeconds` 600,
  dev merchant Alice's key. Requests alternate between app1 and app2 (no load balancer).
- Run: 1,000 requests against a SKU created fresh for that run. `concurrency` virtual threads pull request
  numbers from a shared counter, so at most `concurrency` requests are in flight. All threads are released
  together by a latch; wall time runs from the release until the last response.
- Scenarios:
  - contended: 20 units, so 20 requests succeed and 980 are business rejections (409 INSUFFICIENT_STOCK).
    This is the brief's demonstration.
  - ample: 1,000 units, so all 1,000 succeed (measures the success path).
- Client concurrency 50, 200 and 1,000; 3 runs per cell. One warm-up run per scenario (concurrency 200)
  precedes the measured runs and is not recorded.
- Outcomes are counted separately: created (201), business rejections (409 INSUFFICIENT_STOCK), unexpected
  (any other status, or no response). Latency is per request as seen by the client, including the local
  network hop into Docker. Percentiles use the nearest-rank method over all 1,000 requests of a run; the
  cell value is the median of its 3 runs.
- Each run also checks correctness: exactly 20 (contended) or 1,000 (ample) created, the rest rejected,
  zero unexpected. All 36 checks across both sessions passed.
- The database was not reset between runs or sessions; it grew to about 20,500 reservations and 43,000
  idempotency records during the sessions. Every run uses its own SKU.

Reproduce: `docker compose up -d --build --wait`, then `java -jar demo/target/reservecore-demo.jar bench`.

## Results

Session 1 (`docs/benchmark/2026-10-12-session-1.txt`, 03:51:22Z to 03:51:36Z), median of 3 runs:

| scenario | concurrency | req/s | p50 ms | p95 ms | p99 ms | max ms | unexpected |
|---|---|---|---|---|---|---|---|
| contended | 50 | 1944 | 19.7 | 59.4 | 99.6 | 162.9 | 0 |
| contended | 200 | 2421 | 71.6 | 153.2 | 205.2 | 297.6 | 0 |
| contended | 1000 | 2589 | 222.6 | 363.4 | 376.5 | 384.3 | 0 |
| ample | 50 | 1139 | 39.5 | 91.7 | 117.5 | 155.5 | 0 |
| ample | 200 | 1291 | 141.2 | 240.0 | 292.5 | 375.7 | 0 |
| ample | 1000 | 1356 | 382.3 | 681.3 | 710.3 | 731.8 | 0 |

Session 2 (`docs/benchmark/2026-10-12-session-2.txt`, started right after session 1), median of 3 runs:

| scenario | concurrency | req/s | p50 ms | p95 ms | p99 ms | max ms | unexpected |
|---|---|---|---|---|---|---|---|
| contended | 50 | 3195 | 12.2 | 34.3 | 47.8 | 82.0 | 0 |
| contended | 200 | 3531 | 46.4 | 110.4 | 142.4 | 263.2 | 0 |
| contended | 1000 | 3501 | 172.5 | 263.8 | 280.3 | 284.5 | 0 |
| ample | 50 | 1401 | 31.3 | 70.2 | 94.6 | 128.0 | 0 |
| ample | 200 | 1517 | 122.6 | 221.0 | 283.0 | 359.9 | 0 |
| ample | 1000 | 1398 | 370.6 | 680.4 | 704.3 | 713.3 | 0 |

The per-run rows (all 18 per session) are in the raw files.

Cold-start runs, recorded by the demo scenarios rather than the benchmark (contended, 1,000 requests):

| when | concurrency | req/s | p50 ms | p95 ms | p99 ms | max ms | unexpected | raw output |
|---|---|---|---|---|---|---|---|---|
| first `demo all` after a fresh `compose up` | 200 | 930 | 180.7 | 447.2 | 614.5 | 830.4 | 0 | `2026-10-12-demo-all.txt` |
| `demo contention` after two `demo all` runs | 1000 | 700 | 764.2 | 1273.7 | 1348.7 | 1410.4 | 0 | `2026-10-12-contention-c1000.txt` |

## Observations

- Correctness held in every run: never more than the available units reserved, zero unexpected responses
  (no 5xx, no connection errors) up to 1,000 concurrent clients, including the cold runs.
- Throughput flattens as concurrency rises while latency grows with it, the signature of a serialized
  resource. Here it is the single inventory row of the SKU: every reservation request, including those
  that end in INSUFFICIENT_STOCK, runs a conditional `UPDATE` on that row and waits for its lock (section 6
  of docs/PLAN.md). Requests are also bounded by 20 pooled connections in total.
- Successful reservations are slower than rejections (about 1,100 to 1,500 req/s versus 1,900 to 3,500
  req/s) because they hold the row lock while inserting the reservation and movement and storing the
  idempotent response.
- Warm-up matters a lot: the first 1,000 to 2,000 requests after a start ran at 700 to 930 req/s with p95
  above 400 ms, and session 1 was still noticeably slower than session 2 for the contended scenario
  (1,944 versus 3,195 req/s at concurrency 50). Treat session 2 as the warmed state, and expect differences
  of this size between repeated sessions on a laptop.

## Resource utilisation (2026-10-12, 15:55 to 15:56 UTC)

A separate run on a freshly built stack with the 5 s pool timeout: `ops/sample-utilisation.sh` sampled every
container about every 3 s while the stack sat idle, ran `demo all`, ran `bench --runs=3`, and sat idle again.
Raw samples: `docs/benchmark/2026-10-12-utilisation-samples.csv` (phase boundaries in
`2026-10-12-utilisation-phases.txt`); demo and bench output in `2026-10-12-utilisation-demo-all.txt` and
`2026-10-12-utilisation-bench.txt`. CPU is Docker's figure, where 100 % is one core of the VM's 10.

| phase | container | samples | CPU % avg / peak | memory MiB peak | pool active peak (of 10) | requests waiting for a connection, peak | heap MiB peak | PostgreSQL sessions |
|---|---|---|---|---|---|---|---|---|
| idle (before) | app1 | 4 | 1 / 1 | 269 | 0 | 0 | 65 | |
| idle (before) | app2 | 4 | 2 / 5 | 266 | 0 | 0 | 57 | |
| idle (before) | postgres | 4 | 0 / 0 | 69 | | | | 21 |
| demo all | app1 | 10 | 80 / 361 | 306 | 10 | 90 | 70 | |
| demo all | app2 | 10 | 72 / 386 | 293 | 0 | 0 | 67 | |
| demo all | postgres | 10 | 11 / 80 | 85 | | | | 21 |
| benchmark | app1 | 6 | 182 / 366 | 495 | 10 | 419 | 184 | |
| benchmark | app2 | 6 | 155 / 308 | 526 | 10 | 465 | 196 | |
| benchmark | postgres | 6 | 114 / 121 | 107 | | | | 21 |
| idle (after) | app1 | 4 | 4 / 10 | 496 | 0 | 0 | 170 | |
| idle (after) | app2 | 4 | 4 / 12 | 525 | 0 | 0 | 187 | |
| idle (after) | postgres | 4 | 3 / 9 | 108 | | | | 21 |

- The connection pool is where requests queue: under load all 10 connections of an instance were busy and
  up to 465 requests waited for one, while the busiest app used at most 3.9 of 10 cores and PostgreSQL about
  1.2. That matches the throughput ceiling above: work for one SKU is serialized on its inventory row, so
  more CPU would not help that path.
- Memory stayed small: apps at most 526 MiB (heap at most 196 MiB of a 2.43 GiB maximum), PostgreSQL at most
  108 MiB. 21 PostgreSQL sessions throughout (two pools of 10 plus the sampler's own `psql`).
- Platform threads stayed at 28 per app because request handling runs on virtual threads, which this metric
  does not count.
- The sample interval is about 3 s, so short peaks between samples are missed; the benchmark phase lasted
  17 s (6 samples per container).

The bench in this run reported the medians: contended 1642 / 2148 / 1895 req/s and ample 878 / 1121 / 1165
req/s at concurrency 50 / 200 / 1000, lower than sessions 1 and 2 because the apps had just been restarted
by `demo all`'s worker-restart scenario (cold JIT) and the sampler shared the machine.

## Hot-path engineering (2026-10-12, later)

A code review found that a reservation held the SKU's row lock for four round trips after taking it. The work
below tested whether shortening that changes throughput. Raw data: `2026-10-12-before-hotpath*.txt`,
`2026-10-12-after-hotpath.txt`, `2026-10-12-ab-round1-A-vs-B.txt`, `2026-10-12-ab-round2-A-vs-C.txt`,
`2026-10-12-wait-events.txt`.

**Single sessions are too noisy to compare.** One recorded session before and one after (host client, same
protocol) gave contended concurrency 1,000 at 3,173 versus 1,659 req/s, but the same cell ranged from 891 to
2,543 req/s within the "after" session. That comparison is not used.

**Controlled A/B.** Two images that differ only in `ReservationService.java`, client inside the compose network
(no port forwarding), fresh app JVMs per measurement, one discarded warm-up, then concurrency 200 and 1,000 with
3 runs each, interleaved over three rounds (A B, B A, A B): 9 runs per cell per variant.

Round 1, A = sequential statements (before) versus B = lock-free precheck plus one-statement CTEs:

| cell | A req/s median (range) | B req/s median (range) | B vs A |
|---|---|---|---|
| contended, 200 | 2604 (1451-3051) | 2076 (737-2953) | -20% |
| contended, 1000 | 2288 (897-2859) | 1594 (663-2653) | -30% |
| ample, 200 | 1190 (896-1444) | 1367 (1155-1869) | +15% |
| ample, 1000 | 1257 (1063-1545) | 1555 (348-1742) | +24% |

The precheck was then removed (it cannot help: PostgreSQL does not lock a row that fails the `WHERE` clause, so
once stock is gone the conditional UPDATE returns without waiting, and in a burst every request reads the
pre-burst value). Round 2, A versus C = one-statement CTEs only (shipped):

| cell | A req/s median (range) | C req/s median (range) | C vs A |
|---|---|---|---|
| contended, 200 | 2391 (1280-2741) | 2092 (1437-2581) | -13% |
| contended, 1000 | 2015 (1281-2693) | 2162 (890-2940) | +7% |
| ample, 200 | 1196 (350-1525) | 998 (548-1635) | -17% |
| ample, 1000 | 1230 (759-1431) | 1413 (675-1836) | +15% |

The signs flip between rounds for the same code (ample 200: +15% in round 1, -17% in round 2) and the ranges
overlap almost entirely. Conclusion: on this laptop, none of the variants differs by more than the run-to-run
variance, about ±20%. Zero unexpected responses in all 144 measured runs.

**Where the time goes.** `pg_stat_activity` sampled in a tight loop during a concurrency-1,000 session (7,307
samples of active client sessions):

| wait state | share |
|---|---|
| `Lock:transactionid` (queued for the row) | 49% |
| `Lock:tuple` (queued for the row) | 24% |
| `Client:ClientRead` (in a transaction, waiting for the application) | 13% |
| `LWLock:WALWrite`, `IO:WalSync`, `IO:WalWrite` (commit flush) | 6% |
| `CPU:active` | 3% |
| other | 5% |

`pg_test_fsync` in the PostgreSQL container: `fdatasync` 59 µs per 8 kB write. So the workload is lock-bound,
and the lock holder's time is spent waiting for the application, not executing SQL or flushing WAL. With the
CTEs, two application round trips remain inside the critical section (store the idempotency response, COMMIT)
plus the JSON serialization between them. Removing those needs a server-only transaction, a change to how
idempotent responses are stored; it is proposed in docs/ARCHITECTURE.md, not done.

## Open finding: lost connections at concurrency 1000

In 4 of 17 benchmark processes run with the 5 s pool timeout (15:47 to 15:56 UTC), one concurrency-1000 run
lost 5 to 7 of its 1,000 requests: the JDK client reported `IOException: HTTP/1.1 header parser received no
bytes` (the connection closed without a response). Each time it was the first concurrency-1000 run of a
process that had already used connections for earlier runs. Checked:

- The lost requests never reached the application: the two affected runs checked left 993 and 995
  idempotency records instead of 1,000, and no log line or error belongs to them. Nothing was changed by them, and a client retry
  with the same Idempotency-Key would execute them normally.
- Not reproduced in 10 single-run client processes (`demo contention --concurrency=1000`), in 9 benchmark
  processes with the old 30 s timeout (sessions 1 and 2, 5 runs of `--concurrencies=1000`, 2 runs after a
  database outage), or in 8 consecutive benchmark processes on freshly created containers with the 5 s timeout.
- The pool timeout cannot be ruled out statistically (4 of 17 versus 0 of 9), but the lost requests fail
  before any code that uses the pool runs, so no mechanism links them. Candidates below Spring are Tomcat's
  connection handling, Docker Desktop's port forwarding and the JDK client's connection reuse; they were not
  investigated further (that needs packet capture or client-side HTTP debug logs).
- Raw outputs of the four affected processes: `2026-10-12-lost-connections-1.txt` to `-3.txt` and
  `2026-10-12-utilisation-bench.txt`.

Follow-up (later the same day), now localised:

- Reproduced with a fixed command (fresh app JVMs, then `bench --concurrencies=1000 --runs=1` from the host):
  2 of 6 runs lost requests (1 and 11). A cold warm-up session also showed 37 `Connection reset by peer
  (connect failed)`.
- Hypothesis "Tomcat accept queue (100) overflows": raising `server.tomcat.accept-count` to 1,024 (kernel
  `somaxconn` 4,096) did not help: 1 of 6 runs still lost 38 requests. Falsified.
- Same command with the client inside the compose network, so Docker Desktop's host port forwarding is out of
  the path: 0 of 6 runs lost anything, and 0 unexpected responses in all 144 in-network A/B runs.
- Most likely cause: Docker Desktop's port forwarder dropping connections under connection bursts, not the
  application. Not proven (6 clean runs at a 25% base rate would happen by chance about 18% of the time), but
  the in-network A/B adds 144 more clean runs.

## Not measured

- Many SKUs in parallel (load spread over different inventory rows), which is the expected production
  shape; only the single hot SKU path was measured.
- Long-duration or soak runs, memory growth, and behaviour with a much larger reservation table.
- Confirm, cancel and expiration throughput; the expiration worker under load.
- Any server not on this laptop, any tuned PostgreSQL or pool configuration, a load balancer, TLS.
- Client and server share the machine, so the client competes with the server for CPU.

An early idea, rejecting requests that cannot succeed with a lock-free read before taking the row lock, was
tested later and dropped: see "Hot-path engineering" above.