# Schema (server, Postgres)

⚑ **This block is executable.** `backend/tests/test_schema_parity.py` parses the six declarations
below and compares them against `app/models/` in BOTH directions — a column here that is not mapped,
or mapped and not here, fails the build. So it cannot quietly stop describing the code, and it is
not a subset: adding a column means editing this file in the same commit. Built in 3b (D62).

users(id PK, google_sub UNIQUE, email, display_name, avatar_url, timezone TEXT, created_at)
sessions(id PK, user_id FK, refresh_token_hash, expires_at, revoked_at NULL, created_at)
devices(id PK, user_id FK, fcm_token, platform, last_seen)
daily_counts(user_id FK, date DATE, platform ENUM[instagram,youtube,snapchat,facebook,tiktok], count INT, PK(user_id,date,platform))
friendships(user_id FK, friend_id FK, status ENUM[pending,accepted,blocked], created_at, PK(user_id,friend_id))
sync_batches(batch_id UUID PK, user_id FK, platform, delta INT, client_ts, received_at)  -- pruned >7d

# Sync flow (idempotent deltas) — NEAR-LIVE, amended D89

Target: a friend sees your count within ~2s while you are both looking. Not a live socket; a tight
push loop that only runs while something is actually happening.

1. Device increments Room count per detected reel. Room stays the source of truth (offline-first).
2. **PUSH_DEBOUNCE = 2s.** While a reel surface is active, a flush is scheduled 2s after the first
   unsent delta and coalesces everything that lands in that window. At ~6s/reel that is ~1 request
   per reel; a fast scroller coalesces several into one. **Idle costs nothing** — no delta, no timer,
   no request. Also flushed immediately on app-background and on connectivity regained.
3. Batch = `{batch_id: uuid4, platform, delta, client_ts}`. Delta = count since the last ACKED batch,
   never an absolute (invariant 1).
4. `POST /sync [batches]`. Server: `INSERT sync_batches ON CONFLICT (batch_id) DO NOTHING`; if
   inserted → UPSERT `daily_counts += delta` (date from `users.timezone`, server clock — invariant 2)
   → `ZINCRBY` today's friend-group ZSETs.
5. Response acks `batch_id`s. Client clears acked from the queue. Retries are safe: a conflict means
   already counted.
6. **Reads: `GET /friends/today`, polled every 2s ONLY while a friends screen is visible.** Stops on
   background, on screen-off, and on navigating away. Server caches the ZSET read for 2s, so N
   friends watching each other cost one Redis read per 2s, not N.
7. Nightly scheduled Action: prune `sync_batches` >7d; streak recompute; rank-change FCM pushes.

**Why polling and not a socket.** A socket keeps a connection (and a radio state) alive for a number
that only changes every ~6s, and on Render's free tier a long-lived connection is the first thing a
spin-down kills. A 2s poll that runs only while the screen is open is the same freshness, no
reconnect logic, and provably zero cost when nobody is looking. Revisit if scroll battles ever need
sub-second, which they do not — a reel takes six seconds to watch.

# Day boundary
"Today" = date in users.timezone at server receive time. Client never decides the date. On app open, GET /me/today returns server-truth counts; client reconciles Room.

# Redis
Key `lb:{group_id}:{yyyy-mm-dd}` → ZSET member=user_id score=count. TTL 48h.
Reads cached in-process **2s while a friends screen is open, 30s otherwise** (D89, amending invariant 3).
The client NEVER talks to Redis directly — that half of the original rule is unchanged and is the half
that was actually protecting anything.
