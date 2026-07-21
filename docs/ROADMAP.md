# Roadmap

## Phase 1 — Detection MVP (Android) ← CURRENT
Prove the riskiest 20%. No backend.
- [ ] Repo scaffold, Compose app shell
- [ ] AccessibilityService: detect Instagram reel swipe (pkg=com.instagram.android, vertical pager child change), debounce duplicates
- [ ] Disclosure dialog + onboarding to enable service
- [ ] Room: daily count per platform
- [ ] Home screen: live counter + brain state visual (3 states)
- [ ] Exit: counter proven accurate vs 50 manual swipes (±2)

## Phase 2 — Block & Challenges
- [ ] Overlay block screen at limit (SYSTEM_ALERT_WINDOW)
- [ ] guilt_pack.json loader, weighted no-repeat rotation
- [ ] Challenges: face-down 30s (accel), walk N steps (step detector), jump N (accel peaks), fake-scroll feed
- [ ] YT Shorts + Snapchat detection

## Phase 3 — Backend & Accounts
- [ ] FastAPI: Google auth → own JWT+refresh; /sync idempotent; /me/today
- [ ] Neon Postgres per SCHEMA.md, Alembic, pgbouncer pool
- [ ] Client sync queue (60s batch, ack, retry)
- [ ] Deploy Render/Fly free; GitHub Actions CI (android build+test, backend pytest)

## Phase 4 — Social
- [ ] Friendships, battle groups, Upstash ZSET leaderboards, 30s cache
- [ ] FCM: rank-change taunts
- [ ] Streaks + "beat yesterday"

## Phase 5 — Ship
- [ ] GitHub Releases APK, closed testing (20 testers/14d), Play listing ($25)
- [ ] iOS skeleton in Actions macOS runner (build-green only)

## Status log
- (append: date — phase — what shipped / what broke)
