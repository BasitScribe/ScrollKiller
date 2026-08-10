# Deploying the ScrollKiller API

Written ahead of the first deploy on purpose (D89). Everything here is a decision or a
prerequisite — none of it is a thing you want to be working out while a deploy is failing.

**Status: NOT DEPLOYABLE YET.** There are no endpoints beyond `/health` and `/readyz`, and no
database layer. This file exists so that the day 3b–3d land, deploying is a checklist rather than a
research project. The blockers are listed at the bottom.

---

## The shape

| Piece | Choice | Free tier | Why |
|---|---|---|---|
| API | **Render**, Docker deploy from `backend/Dockerfile` | 750 h/mo, spins down after 15 min idle | Deploys a Dockerfile directly, so the image CI already builds and scans is the image that runs |
| Postgres | **Neon** | ~191 compute-hours/mo, autosuspend | Branching is genuinely useful for migrations; pgbouncer is built in |
| Redis | **Upstash** | 500k commands/mo | HTTP-based, so no connection pool to keep warm on an instance that sleeps |
| Push | **FCM** | free | Phase 4 only |

**Cost ceiling on the free tiers is ~50 users, and the binding constraint is Upstash commands** —
not battery, not Neon (D89 does the arithmetic). When that is reached, the first move is a longer
push debounce for non-battle users. Nothing paid without an ADR (the ₹0 rule).

---

## The spin-down, and why it is survivable

Render's free instance sleeps after 15 minutes with no traffic and takes ~50s to wake. That sounds
fatal for a 2s sync target and is not, for a reason worth understanding rather than hoping about:

- **`/sync` is idempotent**, so a request that times out during a cold start is simply retried and
  cannot double-count. The client's queue is the buffer.
- **`/me/today` is fire-and-forget with a short timeout** and must never block a UI frame. A cold
  start shows the device's own (correct) number, which is the source of truth anyway.
- **`/health` must NOT touch the database.** A suspended Neon must never make a live app read as
  dead — that is D60, and there is a test walking the import graph to keep it true.
- Once any user is scrolling, 2s pushes keep the instance warm all day. **The cold start only ever
  hits the first user of the morning.**

Do NOT add a keep-alive cron to defeat the spin-down. It burns the free hours you are trying to
protect and it is the exact "clever workaround that becomes load-bearing" this project avoids.

---

## Environment variables

Set in Render's dashboard, never in a file. `.env.example` holds the names with placeholder values
and is the only env file that is tracked (D60).

| Var | Notes |
|---|---|
| `ENVIRONMENT` | `production` |
| `LOG_LEVEL` | `INFO`. Never `DEBUG` in production — the redaction tests cover tokens, not volume |
| `DATABASE_URL` | ⚑ **The POOLED Neon URL** (`-pooler` host), `postgresql+asyncpg://…` |
| `DATABASE_URL_DIRECT` | ⚑ **The DIRECT Neon URL.** Alembic only. See below |
| `REDIS_URL` | Upstash, Phase 4 |
| `GOOGLE_CLIENT_ID` | 3c. The `aud` the id_token is verified against |
| `JWT_SIGNING_KEY` | 3c. Generate with `openssl rand -base64 48`. Never reuse across environments |

### ⚑ Two database URLs, and mixing them up is a real outage

The app runs on the **pooled** URL with **prepared statements disabled**
(`statement_cache_size=0` for asyncpg). pgbouncer in transaction mode does not keep a session
pinned, so a prepared statement created on one backend is unavailable on the next and you get
`prepared statement "__asyncpg_stmt_1__" does not exist` under load — intermittently, which is the
worst way to find out.

Alembic runs on the **direct** URL. Migrations take locks and issue DDL, and a transaction-pooled
connection is the wrong place for both.

---

## First deploy — the order

1. **Neon**: create the project, copy BOTH URLs.
2. **Migrations first, from a laptop, on the DIRECT url**: `alembic upgrade head`. Deploying an app
   whose schema does not exist yet just produces a crash loop with a less useful error.
3. **Render**: new Web Service → *Docker* → root directory `backend`. Health check path **`/health`**
   (not `/readyz` — readiness gates traffic and would take the service out on a Neon autosuspend).
4. Set the env vars. Deploy.
5. Verify in this order: `GET /health` → 200 with no DB touched · `GET /readyz` → 200 once the DB is
   reachable · `GET /docs` loads.
6. Only then point a debug build at it.

---

## Before the first real deploy — blockers

- [ ] **3b**: models + Alembic. `app/models/` is still an empty package.
      ✅ Already done: `app/platforms.py` (wire enum, pinned to the Kotlin client by a test) and
      `app/timezones.py` (IANA validation + `local_date_for`, which is invariant 2).
- [ ] **Regenerate the lockfile** — `sqlalchemy[asyncio]`, `asyncpg`, `alembic` are not in it:
      `pip install pip-tools && pip-compile --generate-hashes --output-file=requirements.txt requirements.in`
- [ ] **3c**: auth. Nothing can be per-user until there is a user.
- [ ] **3d**: `/sync` + `/me/today`.
- [ ] ✅ **`tzdata` is in the Dockerfile.** Do not remove it — the slim base strips the timezone
      database, and `assert_tzdata_available()` refuses to boot without it *by design*, because the
      alternative is a container that starts healthy and silently misdates every count (D87/D89).
- [ ] **Add a Postgres service to `backend.yml`** if the model tests need a live database. There is
      none today, which is why 3b's first two modules were deliberately built dependency-free.
- [ ] **Decide the deploy trigger.** Recommended: manual, or on a tag — not on every push to `main`.
      CI is a gate; a deploy is a decision.

---

## Rollback

Render keeps previous deploys; roll back from the dashboard. **A schema migration does not roll back
with it** — so any migration that a previous app version cannot run against must be expand/contract:
add the new column, deploy the code that writes both, backfill, and only drop the old one a deploy
later. That is slower and it is the difference between a rollback and an outage.
