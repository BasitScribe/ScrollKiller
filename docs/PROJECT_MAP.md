# ScrollKiller — Project Map

> **Single entry point.** Living audit + index of every doc in the project. Pointers only — never a
> dump of ROADMAP/DECISIONS.
> Last audited: **2026-07-28** (Phase 2 · challenge suite COMPLETE 4/4 · brand pass done D58 · YouTube v1-decided BETA D57 · device sweep pending).

## Read order for a new session

**`CLAUDE.md` → this file → the specific doc(s) the current ROADMAP item needs. Nothing else by
default.**

That is the whole rule. Everything below tells you *which* doc a task needs so you open one instead
of loading the set. If you find yourself reading DECISIONS.md end to end or the ROADMAP status log,
stop — scan the ADR index or the current-phase section instead.

---

## Doc index — what each file holds, and when to open it

| Doc | Contains | Open it when |
|-----|----------|--------------|
| [../CLAUDE.md](../CLAUDE.md) | Invariants (the 6 non-negotiables), stack, conventions, session protocol | **Always, first.** It is short and it overrides everything |
| **this file** | Project audit, phase status, package map, per-area summaries | **Always, second.** Orient here before opening anything else |
| [ROADMAP.md](ROADMAP.md) | Phases, checkboxes, and an append-only session log | You need the CURRENT phase's open items. **Read the `← CURRENT` section only** — the status log is long and historical |
| [DECISIONS.md](DECISIONS.md) | ADRs D1…D58, append-only, with a 58-line title index at the top | You need *why* something is the way it is. **Scan the index, then open the one Dn** — never the whole file. D55+ titles are greppable via `^\*\*D` |
| [../HANDOFF.md](../HANDOFF.md) | Manual on-device checklists, newest first | You are writing or running device verification. Only the `← CURRENT` block is live |
| [SCHEMA.md](SCHEMA.md) | Server tables + sync flow | Phase 3+ backend work. Nothing in the app reads this yet |
| [STORE_COPY.md](STORE_COPY.md) | Claims the Play listing may **not** make | Before writing any user-facing marketing copy, or pre-submission |
| [architecture.mermaid](architecture.mermaid) | Whole-system component flowchart | You need the shape of the system rather than one area |

### Obsidian vault — `ScrollKiller/`

Topic notes as mermaid mindmaps, cross-linked with `[[wikilinks]]` for graph view. These are the
**human** navigation layer; this file is the one for code work. Vault home:
[ScrollKiller](../ScrollKiller/ScrollKiller.md), with
[Detection](../ScrollKiller/Detection.md) ·
[Platforms](../ScrollKiller/Platforms.md) ·
[Overlay and Block](../ScrollKiller/Overlay%20and%20Block.md) ·
[Challenges](../ScrollKiller/Challenges.md) ·
[Guilt](../ScrollKiller/Guilt.md) ·
[Data](../ScrollKiller/Data.md).

**Link policy:** `docs/` uses standard markdown links — Obsidian's graph view already includes
relative markdown links, so the graph stays navigable *and* the links stay clickable on GitHub.
`[[Wikilinks]]` are used inside `ScrollKiller/` only. See D56.

---

## Mermaid mindmap

```mermaid
mindmap
  root((ScrollKiller))
    Product
      Anti-doomscroll
      Detect reels on-device
      Block at user limit
      Unlock via grace or challenge
      Friend battles later
      Competitor BrainPal
    Invariants
      Idempotent delta sync
      Server timezone day boundary
      Leaderboard 30s cache
      Raw events prune 7d
      Accessibility disclosure first
      Block always exitable P0
    Stack
      Android Kotlin Compose Room min26
      AccessibilityService
      SYSTEM_ALERT_WINDOW
      Backend FastAPI later
      Neon Upstash FCM later
      Cost zero until Play 25USD
    Phase
      P1 Detection MVP done
      P2 Block Challenges CURRENT
      P3 Backend Accounts
      P4 Social
      P5 Ship iOS skeleton
    Android packages
      service detection overlay block
      data Room CountRepository
      guilt pack cadence selector
      challenge walk jump holds
      permission health banner
      brain BrainState MascotArt
      ui dashboard onboarding
      stats TimeEstimate
    Platforms
      IG STABLE ENFORCED blocks
      YT BETA ENFORCED ReVanced pkg
      TikTok BETA SHADOW
      Snapchat BETA SHADOW
    Surfaces
      Dashboard Today Apps Settings
      Bubble expand panel
      Block Exit grace chooser challenge
      Guilt nudges Home bubble block
```

---

## Outline (Obsidian Mind Map source)

### ScrollKiller (working name)
#### Product
- Anti-doomscroll: count reels → interrupt at limit → physical unlock → social later
- Device = source of truth (Room). Server = scoreboard only (Phase 3+)
- No content leaves device — only counts
- Competitor teardown target: BrainPal (`com.brainrot.android`)

#### Non-negotiable invariants → `CLAUDE.md`
1. Sync idempotent — `batch_id` UUID; never POST absolute counts
2. Day boundary = user timezone (server); client interim `LocalDate.now()` until P3
3. Leaderboard reads cached 30s
4. Raw sync/events pruned >7d; daily aggregates forever
5. Accessibility disclosure before enable
6. **Block always exitable** — Exit/Back from every state; never gated on count/timer/network/challenge — **P0**

#### Stack
- **Now:** Kotlin, Compose, AccessibilityService, Room, min SDK 26
- **Later:** FastAPI / Neon / Upstash / FCM; iOS via GHA macOS only
- **Cost:** ₹0 until Play Store ($25); paid only via DECISIONS ADR

#### Roadmap status (audit 2026-07-28)
- **Phase 1 ✅** Detection MVP — IG calibrated 49/50 (D11); ±2/50 exit still open as formal checkbox
- **Phase 2 ← CURRENT** Block live on IG (D49 verified); challenges **all four device-verified** (D50/D53/D54/D55); chooser + "Surprise me" (D53); brand pass (D58); guilt engine; permission integrity (D51); block retry/ReVanced (D52 verified)
- **Open P2 work**
  - Expand guilt pack T3→40, T4→150 (content; ~18 each now)
  - Challenges: **suite COMPLETE 4/4, all device-verified**; only fake-scroll feed remains, and it is not a sensor challenge
  - **YT→STABLE: CLOSED as a v1 decision (D57), no longer "pending".** The IDENTITY_CHANGE strategy works (D34) and ReVanced counts (D52); what is *undone* is flipping the enum + the 15±2 swipe / 30s idle acceptance. Until then YT counts and displays but can never drive a block (`blocksAtLimit = blockEnabled && STABLE`).
  - Many overlay/guilt HANDOFF verifications still unchecked
- **Phase 3 ← CURRENT. 3a SHIPPED 2026-07-28**, 3b next. Sub-phases: **3a** ✅ CI + skeleton + security baseline → **3b** models + migrations → **3c** auth → **3d** sync + `/me/today` → **3e** client queue. **3a–3d touch zero `app/` files**, so the shipped offline app cannot regress.
  - **3a's finding was that the repo did not build from a clean checkout** — 24 untracked paths including `Brand.kt` and both hold sources, plus `gradlew` missing its exec bit and no `.gitattributes`. All fixed and verified by building a fresh clone with no `local.properties`.
  - `backend/` exists: FastAPI skeleton, no domain endpoints. **`app/routers/health.py` must never reach the DB** — liveness and readiness are separate modules and a test walks the import graph (D60). 34 tests, 100% coverage floor, all gates blocking, image runs as UID 10001.
  - ⚠️ **The first CI run has not been observed yet** — `android.yml`/`backend.yml` are PR-triggered and no PR is open. D67's fallback ladder for the JBR-vs-Temurin daemon-JVM pin is written but untested.
  - Recorded: **D59** monorepo, **D60** security-first, **D65** reconciliation (`server_acked + local_unacked`, CRDT-shaped), **D66** Redis/FCM→P4, **D67** Temurin/JBR split, **D68** private-now-public-later, **D69** Python 3.13. **D61–D64 deliberately unwritten** — claims about real infra, recorded in the sub-phase that verifies them.
- **Phase 4–5** Social, Play ship — not started

---

### Detection pipeline (`service/`)
#### ReelScrollAccessibilityService
- Event-only — **no God-class**; emits to overlay + repository
- Routes by `PlatformSpec.advanceStrategy`
- Surface: per-event marker match + **3s hysteresis**; hold while `overlay.isBlocking`
- Entry credit on surface enter — **suppressed** for `IDENTITY_CHANGE` (YT)
- DEBUG: `SurfaceDiagnostics`, `YtProbe`, `DIAG_LABEL` broadcast

#### PlatformSpec / PlatformRegistry
| Platform | Packages | Marker | Gating | Advance | Maturity | blocksAtLimit |
|----------|----------|--------|--------|---------|----------|---------------|
| Instagram | `com.instagram.android` | `clips_viewer` | ENFORCED | DELTA_Y_FORWARD 200ms | **STABLE** | **true** |
| YouTube | `com.google.android.youtube`, `app.revanced.android.youtube` | `reel_recycler` | ENFORCED | IDENTITY_CHANGE 500ms | **BETA by v1 decision** (D57) | false |
| TikTok | `com.zhiliaoapp.musically` | feed_* candidates | SHADOW | DELTA_Y_FORWARD | BETA | false |
| Snapchat | `com.snapchat.android` | `spotlight` | SHADOW | DELTA_Y_FORWARD | BETA | false |

- `blocksAtLimit = blockEnabled && STABLE` — never ask raw `blockEnabled` alone
- ⚠️ **Instagram is the ONLY platform that can block, and that is a v1 DECISION, not a gap (D57).** YT/TikTok/Snapchat are BETA: they count and display but cannot drive a limit. YT's Shorts capture was requested across four sessions and never produced, so promotion was refused on absent evidence — `blocksAtLimit` decides whether the app covers someone's screen. Promotion = one enum flip once the capture exists.
- `packageNames: List` (D52) — ReVanced shares YT row in `daily_counts`
- Enums: `AdvanceStrategy`, `GatingMode`, `Maturity`, `SurfaceMatcher`

#### Detectors
- `SwipeDetector` — activity-reset quiet gap, forward-only (IG)
- `IdentityAdvanceDetector` + `ReelIdentity` — handle/@ identity on CONTENT_CHANGED (YT)
- `EVENT_PULSE` — declared, unused

---

### Overlay & block (`service/` + `permission/`)
#### OverlayController
- Coordinates bubble + block; one count Flow (`observeTodaySummary`)
- Bubble: attach-once; hide = **alpha 0 + NOT_TOUCHABLE** (never GONE — D30)
- Expand panel in-place (`BubbleView` + `BubbleBreakdown`); placement via `OverlayPlacement` / `OverlayMetrics`

#### BlockScreenController
- Full-screen `TYPE_APPLICATION_OVERLAY` at user limit
- Outcomes: `SHOWN / ALREADY_SHOWING / NO_PERMISSION / FAILED / COOLING_DOWN` (D52)
- Attach verified; detach listener; hide reasons logged
- Always: **Exit**, **Back**, **"5 more minutes"** (`BlockLimits.GRACE_MINUTES=5`), challenge path
- **THREE panels, one window** (D50/D53): `block_panel` / `chooser_panel` / `challenge_panel`; child swaps via one `showPanel` helper so "exactly one visible" cannot be broken piecemeal. Root never GONE (D30). **Each panel carries its own Exit, listed first** for TalkBack traversal — invariant 6

#### BlockLimits
- Default 100; slider 20–300 step 10; grace 5 min; challenge grace **15** min (must be > free tap)
- Retry cooldown 30s (`BlockRetryPolicy`)

#### PermissionHealth (D51/D52)
- Gaps (worst first): ACCESSIBILITY → OVERLAY → **OVERLAY_BLOCKED_BY_SYSTEM** → NOTIFICATIONS
- `canDetect` vs `canBlock` — silent half-dead is forbidden
- Home banner (guaranteed) + notification (best-effort) + logcat
- `overlayRuntimeDenied` = observed window failure (ROM lies on `canDrawOverlays`)

---

### Data (`data/`)
#### Room `scrollkiller.db` (v3)
- `daily_counts` — (date, platform) PK, atomic UPSERT +1
- `scroll_events` — raw log, prune >7d
- `guilt_shown` — lineId + lastShownMs (7-day no-repeat)

#### CountRepository
- Offline-first source of truth
- Optimistic `PendingCounts` + `TodayMerge` (max) — UI updates before Room round-trip (D39)
- All reads from `observeTodaySummary()` — one collector, no drift
- DEBUG `CountLatency` DETECT→…→RENDERED

#### SettingsPrefs
- Bubble toggle, overlay skip, daily limits, grace deadlines, guilt locale, warning-once/day, motion permission, etc.

---

### Guilt (`guilt/` + `assets/guilt_pack.json`)
#### Model
- Pack: remote-shaped JSON; categories roast / existential / reverse_psych / **pride**
- ~72 lines, ~18/tier; India Gen-Z; `{count}` / `{minutes}` tokens only (D47)
- **Expansion targets:** T1/T2=18 ✅ · T3=40 · T4=150 (shipped short on T3/T4)

#### Axes (tunable apart)
- **Intensity** `GuiltTier` — silence <50; then 50/70/100/150 → MILD…EXTREME
- **Cadence** `GuiltCadence` — 100→/10, 250→/7, **320→/5 forever** (D48 floor)
- **When** `GuiltFiring` — baseline, pending gap, `MIN_GAP_MS=5.5s`
- **What** `GuiltSelector` / `GuiltRotation` / `GuiltHistory` (7d exclusion)

#### Surfaces
- Bubble nudge (fire), Home/panel (`current()` pin), block (own draw, no reverse_psych)
- No hardcoded `GuiltLine(` outside pack parser — build-enforced

*BrainState (50/150 mascot) ≠ GuiltThresholds (what we say) — keep separate.*

---

### Challenges (`challenge/`)
| Spec | Type | Target | Sensor | Status |
|------|------|--------|--------|--------|
| `walk_20` | WALK | 20 steps | STEP_EVENTS → STEP_CUMULATIVE fallback | **enabled** (D50 ✅ device) |
| `jump_10` | JUMP | 10 jumps | ACCEL_PEAKS | **enabled** (D53 ✅ device) |
| `face_down_30` | FACE_DOWN | 30 s hold | ORIENTATION_HOLD | **enabled** (D54 ✅ device) |
| `forehead_30` | FOREHEAD | 30 s hold | PROXIMITY_HOLD | **enabled** (D55 ✅ device) |
| — | fake-scroll feed | — | not a sensor challenge | not started |

**Suite is 4/4.** `IMPLEMENTED` now equals the full declared `SensorStrategy` set — a legitimate
state, not a bug; the test says so and names what to do when the next strategy is declared early.

- **`ChallengeRegistry.IMPLEMENTED` is the gate** — a spec naming an unimplemented strategy fails the build (`ChallengeRegistryTest`), so a challenge can never ship unable to complete
- Engine shape: `ChallengeSpec` (data) → `ChallengeSensors.sourceFor` → `ChallengeSensorSource` impl; pure detector beside each thin source (`JumpDetector`, `HoldDetector`)
- **Counts vs holds** — `ProgressUnit {COUNT, SECONDS}`. Counts are monotonic; a hold's `onHoldElapsed` is absolute and **resets to 0 on a break** (never pauses — the anti-cheat), so `isComplete` is NOT sticky for holds. Ring counts down for seconds, arc always fills
- **Holds need two things counts didn't** (D54): `FLAG_KEEP_SCREEN_ON` while the challenge panel is up (a 30s default screen timeout == the hold length, and a sleeping screen stops the sensor), cleared on every teardown path; and `ChallengeHaptics` — 400ms buzz complete / two-pulse break — because a face-down ring is invisible and flipping up to check destroys the hold
- Reward **flat 15 min** for every challenge vs tap 5 min — inequality tested; flat was chosen over effort-scaled so choice stays about accessibility, not optimisation (D53)
- **Availability is per-spec** (`ChallengeAvailability`), not app-wide: only the step sensors need `ACTIVITY_RECOGNITION`, so a denied motion permission must not hide jump/holds. `MotionStatus` remains the step-specific question for Settings + PermissionHealthReader
- Chooser panel + "Surprise me" (never repeats the previous draw); unavailable → no row

---

### Brand (`ui/theme/`) — D58
- **`Brand.kt` is the ONE place a brand colour is written.** Plain Kotlin `0xAARRGGBB` Longs, no Compose/Android imports, because the bubble + block + ring are plain views over other apps and can NEVER read `MaterialTheme`. Compose does `Color(Brand.X)`; views do `Brand.X.toInt()`
- Palette **sampled from the mascot art**: cobalt `#0050B0` (cap) leads — calm ground, coral `#F08090` (brain) accents. Coral is `secondary`, never `primary`
- **No `dynamicColor`** — the parameter is deleted, not defaulted false. It used to derive the palette from the user's wallpaper
- **XML layouts carry zero brand colours** — applied in code at inflation (`BlockScreenController.styleFromBrand`). Sole exception: `themes.xml` windowBackground, mirrored in `colors.xml` with cross-comments
- Button hierarchy is an **invariant-6** decision: Exit = highest contrast on every panel, snooze = quietest
- Type: full `displayLarge`→`labelSmall` scale, negative tracking on display sizes. Still Roboto (licensing a display face is a product call; swap is one `FontFamily` edit)
- Nav bar: `mascot_head` (crop MEASURED at 24dp, aspect 1.03) + two hand-written vectors. Head is `Image` (untinted art); vectors are `Icon` (tint with selection)

---

### Brain & art (`brain/` + `art/mascot/` + `tools/mascot_import.py`)
- `BrainState`: HEALTHY / CRACKING / FRIED @ 50 / 150
- `MascotArt`: + GUARDIAN (block-only, not a BrainState)
- Pre-scaled hero 120dp / bubble ~40dp height; geodesic flood key + union trim

---

### UI (`ui/`)
#### Onboarding
- Disclosure → Accessibility Settings → optional Overlay permission
- `onResume` re-check; skip overlay persisted

#### Dashboard (Compose Material3)
- Tabs: **Today** (mascot + total + time + guilt + permission banner) / **Apps** / **Settings**
- Settings: perms, bubble, limit slider(s) for `blocksAtLimit` platforms, clear data, motion, notifications, pack picker

---

### Backend (not in tree yet) → SCHEMA.md
- Auth Google → JWT; POST `/sync` deltas; GET `/me/today`
- Postgres: users, sessions, devices, daily_counts, friendships, sync_batches
- Redis ZSET leaderboards; FCM taunts — Phase 4

---

### Store / Play posture → STORE_COPY.md
- ❌ Never claim block unbypassable / always works
- Honest pitch: friction, choice, number in your face
- Accessibility disclosure required

---

### Test surface (`app/src/test`)
Pure JVM coverage for: detectors, registry, BlockPolicy/Limits/Retry, PermissionHealth, guilt*, BubbleBreakdown, OverlayPlacement, TodayMerge, Challenge*, TimeEstimate, BrainState, SurfaceMatcher, ReelIdentity…

---

### Current HANDOFF focus (see HANDOFF.md)
**D58 — brand pass (CURRENT):** no purple, palette must NOT move when the wallpaper changes · light+dark sweep · hero card tint shifts at 50/150 · mascot head in FULL COLOUR in the nav bar · **Exit still the most prominent button on all three panels** · no new bubble churn.
**D55 — forehead hold:** chooser is 4 deep + Surprise me · ring counts down, resets on break · **thumb-on-a-table must NOT count** (needs upright too) · **OEM pocket mode** — report if covering proximity locks your screen · neither proximity nor accel left registered · absent row on a device with no proximity sensor.
**D54 — face-down (✅ verified) · D53 — jump + chooser (✅ verified).**
Also still owed: D51 permission integrity device runs; older overlay/guilt acceptance runs; the **17s stall** answer was never recorded either way.

---

## Package → responsibility (quick index)

```
com.scrollkiller
├── MainActivity / ScrollKillerApp
├── service/     detection, platforms, overlay, block, placement, probes
├── data/        Room, CountRepository, SettingsPrefs, latency
├── guilt/       pack, tiers, cadence, firing, selector, history
├── challenge/   specs, sensors, controller, ring UI
├── permission/  PermissionHealth, notifier
├── brain/       BrainState, MascotArt
├── ui/          dashboard, onboarding, theme
└── stats/       TimeEstimate
```

---

## Audit checklist (maintain this map)

When a session ends, update **only** these bullets if they changed:

- [ ] Phase / CURRENT label
- [ ] Platform table (maturity, gating, blockEnabled, packages)
- [ ] Challenge registry enabled list
- [ ] Guilt pack sizes / expansion targets
- [ ] Latest ADR number + HANDOFF headline
- [ ] Open P2 checkboxes
- [ ] The `ScrollKiller/` vault notes, when a fact in one of them changes (they drift silently —
      `Challenges.md` was found two challenges behind)
- [ ] **Fence balance** — every doc's `grep -c '^```'` must be EVEN before you finish (D56)

*Do not paste full ROADMAP status-log entries here — link them.*