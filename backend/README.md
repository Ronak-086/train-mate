# Train Mate API

Java 21. PostgreSQL for data, Redis for cache, locks and counters, Firebase for sign-in.

## How it is built

The API is two layers, on purpose:

| Layer | Where | What it is |
|---|---|---|
| **Core** | `app.trainmate.*` except `spring` | Plain Java: business rules, SQL over JDBC, JSON via Jackson, a small router, the Firebase token verifier, a Redis client. No framework. |
| **Spring host** | `app.trainmate.spring` | About 100 lines: starts Spring Boot, builds the core from configuration, and hands every `/api/**` request to it. Flyway runs the migrations. |

The reason is testability. Spring Boot's libraries could not be downloaded where this was written, so everything that
matters is in the core and was tested against a real PostgreSQL and Redis. The Spring layer is deliberately thin.

## Status: what exists and what is verified

**Implemented (step 4, part 1):** sign-in (Firebase ID tokens), accounts (`/me`), trips, train search and route, train status
(cache, lock, budget, circuit breaker, validation, snapshots), the dev tools, and the feed endpoint returning the active trip.

**Not implemented yet (part 2):** posts and the board, comments, reactions, offers and chat, reports, blocks, reviews, and the feed's
`items`. Until they exist, the web app's Board, Post and Chat screens show errors when pointed at this API; Home, Add train,
Status and Profile work.

**Real train-data provider:** not implemented. Only a mock exists. See [docs/train-data-provider.md](../docs/train-data-provider.md).

**Verified here** (`scripts/test-offline.sh`, 152 tests against real PostgreSQL 16 and Redis): schema migrations, timetable import and
rollback, status maths, the whole refresh pipeline including a 12-request stampede and queued refreshes, budget and circuit breaker, the key-value
stores, the Firebase token verifier against forged, unsigned, expired and wrong-audience tokens, trips and accounts including concurrency, every
HTTP error shape, production safety guards, and the whole thing over real sockets. The browser suite also ran against it
(`npm run e2e:backend` in `web/`, 9 tests).

**NOT verified here:**
- **The Spring layer** compiles against stand-ins I wrote, not against Spring. Run `mvn verify` first. Expect, at worst, small fixes in `app/trainmate/spring/`.
- **Docker files and `docker-compose.yml`** were never run (no Docker available).
- **Real Firebase tokens.** The verifier follows Firebase's documented rules and is tested with locally generated keys, but has never seen a token from Google.
- **Neon and Upstash** specifically (TLS to Redis, pooled connections).

## Running it

**Docker (everything, with dev sign-in and seed data):** from the repo root, `docker compose up --build`, then open http://localhost:3000.

**Maven:** `mvn verify` runs the tests (those needing PostgreSQL or Redis skip themselves if unreachable; set `TEST_DB_URL`/`REDIS_URL`).
Then `mvn spring-boot:run` with the variables from `.env.example`.

**Without Maven** (what the tests here used; needs a JDK 21 plus Jackson, the PostgreSQL driver and JUnit as jars in `/usr/share/java`):
`scripts/test-offline.sh` to test, `scripts/run-dev.sh` to run on the plain JDK server.

## Configuration

See `.env.example`. The app **refuses to start** on unsafe settings: `AUTH_MODE=dev` without `DEV_TOOLS`, `AUTH_MODE=firebase` without
`FIREBASE_PROJECT_ID`, a missing or short `PNR_SALT`, an unknown provider.

## Endpoints (all under `/api`)

| | |
|---|---|
| `GET /health` | public |
| `GET /trains/search?q=`, `GET /trains/{no}/route` | public |
| `GET /trains/{no}/status?date=` | public; faster refresh if the caller has that train saved |
| `GET /trips/mine`, `POST /trips`, `DELETE /trips/{id}` | signed in |
| `GET /feed/around-me` | signed in; returns the active trip, `items` empty until part 2 |
| `GET /me`, `PATCH /me`, `DELETE /me` | signed in |
| `POST /dev/reset`, `POST /dev/clock` | only with `DEV_TOOLS` |

Errors are RFC 9457 `application/problem+json` with a stable `code`. Shapes match `web/src/lib/api/types.ts`.

## Where this goes beyond the spec (please confirm)

1. **Migration V2** adds `requests.title` (the designs show a headline per post) and `conversations.requester_done` / `helper_done` (both people must mark done).
2. **IDs are UUID strings** as in the schema; the web mock used `p_1`-style ids. The web app treats ids as opaque, so this is invisible to it.
3. **`fetchedAt` on status** (for "updated 4 min ago") and the **arrival-window rule**: scheduled time plus the low delay, up to scheduled plus the high delay plus 10 minutes for stops ahead.
   The delay range uses the age of the last fetch, not the observation time. These are my tuning, not in the spec.
4. **Labels:** "up to 20 min late" when old data makes the range reach zero, so a late train never reads "on time".
5. **API usage is written immediately**, not batched (spec 8.6): simpler, and the volume is a handful of rows a day.
6. **Hand-written pieces** that would normally come from libraries, because none could be downloaded: the Redis client (six commands, one connection) and the Firebase
   token verifier. Both are tested hard, but the verifier is security-critical: have it reviewed, or swap in Nimbus or Spring Security behind the `TokenVerifier` interface.
7. **`DELETE /me` removes the app's data but not the Firebase account** (that needs the Firebase Admin SDK).
8. **In development the clock travels in `X-Server-Time`**, because the JDK server overwrites `Date`. Real servers send `Date`; the web client reads either.
