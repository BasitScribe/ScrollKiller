# Schema (server, Postgres)

users(id PK, google_sub UNIQUE, email, display_name, avatar_url, timezone TEXT, created_at)
sessions(id PK, user_id FK, refresh_token_hash, expires_at, revoked_at NULL)
devices(id PK, user_id FK, fcm_token, platform, last_seen)
daily_counts(user_id FK, date DATE, platform ENUM[instagram,youtube,snapchat,facebook,tiktok], count INT, PK(user_id,date,platform))
friendships(user_id FK, friend_id FK, status ENUM[pending,accepted,blocked], created_at, PK(user_id,friend_id))
sync_batches(batch_id UUID PK, user_id FK, platform, delta INT, client_ts, received_at)  -- pruned >7d

# Sync flow (idempotent deltas)
1. Device increments Room count per detected reel.
2. Every 60s (or Wi-Fi/app-background), client builds batch: {batch_id: uuid4, platform, delta, client_ts}. Delta = count since last acked batch.
3. POST /sync [batches]. Server: INSERT sync_batches ON CONFLICT (batch_id) DO NOTHING; if inserted → UPSERT daily_counts += delta (date from user's timezone, server clock) → ZINCRBY today's friend-group ZSETs.
4. Response acks batch_ids. Client clears acked from queue. Retries safe: conflict = already counted.
5. Nightly cron: prune sync_batches >7d; streak recompute; rank-change FCM pushes.

# Day boundary
"Today" = date in users.timezone at server receive time. Client never decides the date. On app open, GET /me/today returns server-truth counts; client reconciles Room.

# Redis
Key lb:{group_id}:{yyyy-mm-dd} → ZSET member=user_id score=count. TTL 48h. Reads cached in-process 30s.
