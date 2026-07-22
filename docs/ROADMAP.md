# Roadmap

## Phase 1 — Detection MVP (Android) ← CURRENT
Prove the riskiest 20%. No backend.
- [ ] Repo scaffold, Compose app shell
- [x] AccessibilityService: detect Instagram reel swipe (pkg=com.instagram.android), debounce duplicates (activity-reset 200ms, forward-only; PlatformSpec registry)
- [x] Disclosure dialog + onboarding to enable service (disclosure-first → deep-link to Settings → onResume re-check)
- [x] Room: daily count per platform (daily_counts mirror, atomic upsert-increment, survives process death; interim local-date boundary)
- [x] Home screen: live counter + brain state visual (3 states) — live Room-backed counter + degrading brain (🧠/🤯/💀 at 50/150) on Home AND bubble, one source (BrainState)
- [x] Floating live-counter bubble (SYSTEM_ALERT_WINDOW): draggable 🧠+count over tracked apps only, same repository Flow as Home, optional overlay onboarding step (D16); now surface-gated + attached-once, no window churn (D17) — code done; on-device verification pending (HANDOFF)
- [ ] Exit: counter proven accurate vs 50 manual swipes (±2)

## Phase 2 — Block & Challenges
- [x] Overlay block screen at limit (SYSTEM_ALERT_WINDOW) — full-screen block over reels at PlatformSpec.dailyLimit (default 100); Exit/Unlock/Back; dormant until surface gating active (never the feed). D19
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
- 2026-07-22 — Phase 1 — Shipped debounced reel detection (activity-reset 200ms, forward-only) behind a PlatformSpec registry (Instagram enabled); emits `REEL_ADVANCE platform=…`. Added disclosure-first onboarding + deep-link to Settings + onResume re-check. Home shows a live in-memory count (TodayCounter). SwipeDetectorTest green; app compiles. Pending: on-device 50-swipe ±2 calibration; Room persistence + real day-boundary; brain-state visual.
- 2026-07-22 — Phase 1 — Detection calibrated on-device (49/50, safe undercount) — exit criterion MET (D11). Added Room persistence: daily_counts mirror + DAO (atomic upsert-increment), CountRepository (Room-backed, replaces TodayCounter), HomeViewModel; count now survives process death. Interim device-local day boundary (TODO Phase-3 server-truth). Added Room+KSP+viewmodel-compose deps. Recorded IG container finding (ViewPager vs RecyclerView, D15). Compiles (KSP validates SQL); SwipeDetectorTest green; DailyCountDaoTest added (instrumented — needs device). Pending: on-device force-stop persistence check; brain-state visual; Phase 3 sync.
- 2026-07-22 — Phase 1 — Fixed two device bugs + a capture gap (D17). (1) Re-added rich node-tree diagnostics permanently behind BuildConfig.DEBUG (SurfaceDiagnostics; `DIAG`-prefixed logs) so the surface tour can find IG's Reels discriminator. (2) Added surface gating: PlatformSpec.surfaceMarkers + SurfaceMatcher + a single onDoomSurface state driving BOTH counting and bubble visibility; ships as a no-op pass-through (empty markers) until the tour fills the discriminator — fallback = window-content heuristic only if ids prove obfuscated. (3) Fixed overlay churn: bubble now attached once (onServiceConnected) and toggled VISIBLE/GONE, killing the VRI construct/destroy cycles + repeated "displaying over other apps" notification. assembleDebug + testDebugUnitTest green. PENDING (next session, on-device): run the labeled surface tour (feed→reels→stories→profile→DMs→reels), paste the DIAG log, pick surfaceMarkers; then verify counting only on Reels + bubble only on Reels + no window churn, and re-run the ±2/50 calibration.
- 2026-07-22 — Phase 1 — Calibration Round 1 recorded: no new figure — already captured in D11's on-device run (slow 20/20, rapid 19/20, mixed 10/10). Shipped floating live-counter bubble (D16): OverlayController owns the SYSTEM_ALERT_WINDOW bubble (service stays event-only), reads the same observeToday() Flow as Home, updates on change only, collects only while visible, no-ops without overlay permission. Added optional overlay onboarding step (OverlayPermissionScreen + OverlayStatus) with 3-way MainActivity routing and a persisted skip flag. Broadened accessibility scope (removed packageNames, added typeWindowStateChanged) so the bubble hides on leaving a tracked app; updated disclosure text. Created HANDOFF.md manual test checklist (incl. comments-scroll false-count edge case). Pending on-device: bubble show/hide/drag/tap, count-matches-Home, works-without-permission, comments/IME edge cases.
- 2026-07-22 — Phase 1 — Shipped brain-state visual (D18): com.scrollkiller.brain.BrainState (pure Kotlin, one source of thresholds+arc: 🧠 0..49 / 🤯 50..149 / 💀 150+, green/amber/red). Home renders the brain hero + tinted count + label; overlay bubble shows emoji+count with per-state background tint (inside the existing distinctUntilChanged collector — no animation loop, CHEAP held). White bubble text for legibility over the tint. BrainStateTest (boundary table) + SwipeDetectorTest green; compileDebugKotlin SUCCESSFUL. Home @Preview covers all 3 states. Pending on-device: eyeball 🧠→🤯 flip at 50 on Home + bubble. Still blocked: surface-gating ADR needs the SurfaceDiagnostics resource-id (surfaceMarkers still empty).
- 2026-07-22 — Phase 2 — Shipped the block screen (D19): full-screen overlay over the reel surface at PlatformSpec.dailyLimit (default 100), with Exit (→ launcher), Unlock (temporary bypass — challenge next), and Back=Exit. Pure BlockPolicy.overlayFor gates bubble-vs-block; SAFETY GATE keeps it dormant until surface gating is real (surfaceMarkers non-empty) so it never covers the feed. Service doom-surface signal now carries the Platform (onSurface/offSurface); OverlayController is the coordinator owning bubble + new BlockScreenController; added per-platform count (DAO observeCountForDatePlatform + CountRepository.observeToday(platform)). New BlockScreenController (focusable full-screen, add/removeView, no-op without overlay permission) + overlay_block.xml + guilt string-array. BlockPolicyTest + BrainStateTest + SwipeDetectorTest green; compileDebugKotlin SUCCESSFUL. Play-policy posture recorded in D19. PENDING on-device: cannot live-demo the block until the surface tour fills surfaceMarkers (safety gate keeps it dormant); then verify block only on Reels, Exit/Back leave, re-show on foregrounding. NEXT checkbox: the actual challenge (replaces Unlock bypass).
