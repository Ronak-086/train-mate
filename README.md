# Train Mate

A web app for people sharing a train journey: live train status, a board for their coach, seat swaps, and private chat.

    web/       Next.js app (TypeScript, Tailwind). Includes a built-in mock API for development.
    backend/   Java API: PostgreSQL, Redis, Firebase sign-in. Plain-Java core with a thin Spring Boot host.
    docs/      Firebase setup, the train-data provider, testing on real phones.
    docker-compose.yml   PostgreSQL + Redis + API + web, for local development.

## Quick start (no accounts, no Java)

    cd web && npm install && npm run dev      # http://localhost:3000, pick a test traveller

This uses the built-in mock API. To use the real backend instead, see `backend/README.md`.

## Tests

| Command (in `web/`) | What | Count |
|---|---|---|
| `npm test` | web unit tests and the mock API's rules | 98 |
| `npm run e2e` | browser flows against the mock API (desktop sized), including hover | 36 |
| `npm run e2e:mobile` | the same flows in an **emulated** Android phone (not a real device) | 31 |
| `npm run e2e -- --project=firebase-mode` | Google sign-in screen and guards (placeholder Firebase settings) | 4 |
| `npm run e2e:backend` | browser to Java API to PostgreSQL and Redis (needs those running) | 9 |
| `scripts/test-offline.sh` (in `backend/`) | backend tests against real PostgreSQL and Redis | 152 |

Run `npx playwright install chromium` once before the browser tests.

## Honest status

**Working and tested:** every screen in the designs plus the ones the designs left out; hover states; Google sign-in wiring (the screen, guards, token handling);
the backend's sign-in, accounts, trips, train data and status pipeline, against real PostgreSQL and Redis.

**Not yet:** the backend's posts, board, comments, reactions, chat, reports and blocks (the web app uses the mock for those); a real train-data provider;
a real Google account signing in; the Spring layer compiled with Maven; testing on real phones. `docs/` says exactly what each needs.

---

## What the web app contains

Every screen in the designs works against the mock API, plus the screens the designs didn't cover (styled to match):

| Screen | Route | Notes |
|---|---|---|
| Sign in | `/login` | Google sign-in (`NEXT_PUBLIC_AUTH_MODE=firebase`) or a test-traveller picker (default). |
| Home | `/home` | Live status card (60s polling), coach feed, New post. Card opens the full route. |
| Board | `/board` | My coach / Whole train, type filters, "Show more" pagination, 30s polling. |
| New post | `/post/new` | Seat swap, Help, Question, Alert, with the spec's audience rules and limit messages. |
| Post detail | `/post/[id]` | Reactions and replies (questions, alerts); offers, accept/decline (swaps, help); close, delete, report, block. |
| Chats | `/chats` | Conversation list (15s polling). |
| Chat | `/chats/[id]` | 5s polling; pending, accepted, declined, done; Mark done needs both people; rating. |
| Add train | `/trips/new` | Search, route-aware stations, coach/seat/berth. |
| Status detail | `/status` | Every stop: passed, next, scheduled and expected times. |
| Profile | `/profile` | Rename, trips, sign out, delete account. |

Not in the designs, so decided by me: Whole train shows posts addressed to the whole train (`scope=TRAIN`);
My coach filters by coach. The designs' SHARE kind and group-size field are not offered in the UI yet.

## Step 2: API contract and mock backend

- `src/lib/api/` is what the UI uses: `types.ts` (the contract), `client.ts` (fetch wrapper, auth token,
  `ApiError`), `endpoints.ts` (one typed function per endpoint). `import { api, ApiError } from "@/lib/api"`.
- `src/server/mock/` is an in-memory stand-in for the Spring Boot API, served by `src/app/api/[...path]/route.ts`.
  Same paths and JSON as the real backend. Set `BACKEND_URL` and `/api/*` is proxied to the real backend instead.
- Dev login is `Authorization: Bearer dev:<userId>` (default `dev:u_me`). Seed users: u_me, u_priya, u_arun,
  u_meena, u_kavya, u_dev, u_imran (all coach B3), u_rahul (B4), u_sana (B6).
- The mock clock starts at 09:06 IST on 25 Oct 2026 (the moment the designs show) and then runs in real time.
- Dev only: `POST /api/dev/reset`, `POST /api/dev/clock {"iso": "..."}`. Registered when `NODE_ENV` is not production, or `MOCK_DEV_ROUTES=1`.
- Errors are RFC 9457 `application/problem+json` with a stable `code` (e.g. `SCOPE_NOT_ALLOWED`, `POST_COOLDOWN`
  with `retryAfterSeconds`, `CONVERSATION_EXISTS` with `conversationId`).

## Where the contract goes beyond the spec (please confirm)

1. **`title` on posts.** The designs show a headline per post; the spec only has `note`. Swaps derive it from the
   berths ("Upper berth for lower berth"); other kinds require it (max 80). Needs a `title` column in the schema.
2. **`fetchedAt` on train status**, for "updated 4 min ago" (the spec only has the provider's last-seen time).
3. **Arrival windows.** Delay range = delay ± r (r from the age of the last fetch), rounded to 5 min, so 12 min
   late reads "10–15 min late". A station's window runs from scheduled + low to scheduled + high + 10 min (allowance
   for stops ahead): Lumding 9:20–9:35, Delhi 2:00–2:15, as in the designs. The +10 is my tuning, not in the spec.
4. **Board order is newest first** (spec 10.3). The Board design lists swap, help, alert, question, which is not by time.
5. **`replyCount`** is comments for public kinds and non-declined offers for swaps and help.
6. **Done needs both people.** Conversations expose `myDone` / `otherDone`; the post becomes MATCHED when both mark done.
7. **Reports must name the post or comment's actual author** (`TARGET_MISMATCH` otherwise).
8. **Hidden/closed/expired posts** return 410 `POST_UNAVAILABLE` to others, and stay readable by the author and
   conversation participants.
9. **SHARE** is in the contract and rules; the UI will expose the four designed kinds.
10. **Delay wording:** "10–15 min late", or "up to 20 min late" when old data makes the range reach zero, so a late train never reads "on time".
11. **Timetable data is fictional.** The designs' stations don't form a real route, so trains 12345/12346/12347
    are fixtures. Only 12345 on 25 Oct 2026 has "live" tracking; the rest are timetable-only.
