# /brag plan: ReserveCore

Input: project (this repository). Tone: `polished` with punch (a serious backend, so no jokes; the drama
comes from the real numbers). Format: landscape 1920x1080, 30 fps, 20.4 s.

## Answers

- What is it: an inventory reservation backend that holds limited stock during checkout and never oversells
  or applies a retried request twice.
- Who is it for: developers building storefronts and checkout flows for limited stock (drops, launches).
- What it does for them: reserve, confirm, cancel and expire holds over HTTP, safe under concurrency,
  retries, crashes and several instances.
- What sets it apart: correctness is proven by running it: 1,000 requests for 20 units give exactly 20
  reservations; 50 copies of one request give one reservation; killing every instance still releases every
  expired hold exactly once.
- Most impressive claim (all from real runs, docs/benchmark/ and the demo output): `created 20, 409
  insufficient 980, unexpected 0`; `all 50 copies -> 201`, `distinct reservation ids 1`; `all 30 holds
  EXPIRED after restart`, `reclaimed within 92 ms of all instances reporting ready`; 64 tests, 40/40 demo checks.
- Visual hook: 1,000 dots (requests) racing for 20 units; 20 turn green, 980 turn amber, "0 oversold".
- Real UI to show: the API itself (a real request and 201 response from the README example), the running
  Swagger UI (screenshot of http://localhost:8081/swagger-ui.html), the dev demo's PASS output numbers.
- Tone: polished, developer-facing, dark terminal palette.
- Share caption: see share-copy.txt.

## Visual identity

The project has no UI of its own, so the identity follows its terminal and API surface: near-black
background `#0B0E14`, text `#E8ECF2`, success green `#3DDC97` (201), rejection amber `#FFB547` (409), kill
red `#FF5D5D`, accent blue `#6CA8FF`. System fonts: SF Pro (system-ui) for type, SF Mono for code.

## Storyboard (20.4 s)

| # | Time | Scene | On screen | Sound |
|---|---|---|---|---|
| 1 | 0.0-3.2 | Hook | "1,000 checkouts." then "20 hoodies." over a grid of 1,000 dots streaming in | pad swell, rising |
| 2 | 3.2-7.0 | Reveal | 20 dots flash green one by one, then 980 sweep amber; counters: 20 reserved, 980 told no (409), 0 oversold | beat drops in; 20 soft in-key chimes on the green flips |
| 3 | 7.0-10.8 | Highlight: retries | a real `POST /v1/reservations` with an Idempotency-Key types out, `201 Created` answers; the card fans into 50 copies; "Same request, 50 times at once." "1 reservation." chip `Idempotent-Replayed: true` x49 | hats and arpeggio enter |
| 4 | 10.8-14.8 | Highlight: crash | app1 and app2 holding 30 holds; SIGKILL, both go dark, holds expire with nothing running; restart; holds flip to released: "Kill every instance." "30 holds released. Exactly once." "92 ms after restart" | music ducks and filters on the kill, returns on restart |
| 5 | 14.8-17.6 | Highlight: proof | the running Swagger UI in a browser frame; "A real HTTP API." chips: 64 tests, 40/40 demo checks, 0 failed | full groove |
| 6 | 17.6-20.4 | Outro | "ReserveCore", "Hold stock at checkout. Never oversell.", "Spring Boot 4 · Java 25 · PostgreSQL 18" | resolve chord and a final chime |

Transitions are staggered (old content out, then new in) to avoid double exposure.
Poster: the settled reveal frame (counters and resolved grid), about 6.5 s.
