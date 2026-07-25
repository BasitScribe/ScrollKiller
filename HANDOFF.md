# HANDOFF — manual on-device test checklist

## ← CURRENT: panel containment, count latency, platform icons (D38/D39/D40)

Three changes, **no detection touched** — no threshold, gating, limit, marker or debounce
calibration change. D11's 200ms IG quiet gap and YT's 500ms identity floor are byte-for-byte
what they were.

1. **D38 — the expanded panel stays on screen.** The bubble's window carries
   `FLAG_LAYOUT_NO_LIMITS` (that is what lets the pill sit flush against a real edge), so the
   system never pulled it back when it grew ~5× taller on expand. It now does its own
   containment against the insets.
2. **D39 — the count no longer waits on the database.** `record()` bumps an in-memory counter
   synchronously and the read Flows merge it with Room, so the bubble moves on detection
   instead of after two SQLite commits plus Room's invalidation round trip.
3. **D40 — platform icons replace the `IG`/`YT` text** in the panel's bars.

Build is green: `testDebugUnitTest` **108 tests pass** (22 new — `OverlayPlacementTest` 12,
`TodayMergeTest` 10), `assembleDebug` succeeds, `lintDebug` finds nothing new (its one error is
the pre-existing `local.properties` backslash escaping).

**⚠️ Run 2 is not yet done and is the one that matters.** The latency instrumentation is in and
the debug APK is installed on `00116646S004404`, but the device was PIN-locked, so the deltas
below were never captured. **Nobody has measured which layer was actually slow.** The D39 fix
targets the DB round trip because that is the only layer that is structurally unbounded — but
that is an argument, not a measurement, and Run 2 is what settles it. Do it before believing
the fix.

### Run 1 — the panel stays on screen at all four edges
The reported bug: expanding near an edge ran the panel off the display.
- [ ] Drag the pill **flush to the right edge**, tap to expand → the panel is **fully visible**
      and horizontally **centred**, not hanging off the right.
- [ ] Same at the **left edge** → panel centres; it does not stay pinned left.
- [ ] Same at the **bottom edge** → the panel grows upward as needed; its bottom sits above the
      nav bar / gesture pill, nothing clipped.
- [ ] Same at the **top edge** → the panel's top clears the status bar and any camera cutout.
- [ ] Expanding does **not** move the pill vertically when there is room — the header stays
      under the finger that just tapped it. Only x jumps (to centre).
- [ ] Collapse → the pill returns to **exactly where it was dragged**, not to the panel's
      centred position.
- [ ] **Rotate to landscape** with the panel open, and again with it closed → in both cases the
      overlay ends up fully on screen, not off the bottom/right of the new orientation.
- [ ] Drag while the panel is **open** → it collapses to the pill and the drag proceeds
      normally. (Intended, see D38 — not a dropped gesture.)
- [ ] Drag hard **past** an edge and release → the pill sits flush, and the *next* expand still
      centres correctly (i.e. the overshoot was not stored).

### Run 2 — count latency, before vs after ← **DO THIS ONE**
The build logs one line per counted advance. Capture with:
```
adb logcat -c
adb logcat -s ScrollKiller | Select-String "LATENCY"
```
Each line reads:
```
LATENCY seq=7 platform=instagram OK detect→optimistic=0ms detect→persisted=41ms
        detect→emitted=1ms detect→rendered=18ms | write=41ms roomEmit=- layout=17ms
```
- `detect→optimistic` — in-memory bump. Should be **~0ms**.
- `detect→emitted` — when the overlay's collector got the new number. **This is the number the
  user feels.** Post-fix it should track `optimistic`, not `persisted`.
- `write` — the two SQLite commits.
- `roomEmit` — Room's InvalidationTracker round trip. Prints `-` when the optimistic emission
  already closed the trace first, which is the healthy case.
- [ ] **One deliberate swipe on Instagram Reels**, paused ~2s either side so the line is
      unambiguous. Record `detect→emitted` and `detect→rendered`.
- [ ] **One deliberate swipe on YouTube Shorts**, same. Record the same two.
- [ ] Then ~15 swipes each at normal cadence; note the worst `detect→emitted`.
- [ ] **Compare against the old path**: `write + roomEmit` on the same lines *is* the old
      latency (that is exactly what the count used to wait for). If `detect→emitted` is now
      ~0–5ms while `write + roomEmit` is tens of ms, the fix landed and the DB was the source.
- [ ] **If `detect→emitted` is already small but the bubble still feels late**, the fix missed:
      the delay is upstream of `record()`, i.e. in detection. On **YouTube** the prime suspect
      is `IDENTITY_SCAN_MIN_MS = 250ms` — the content-change rate limit, which can sit on an
      identity change for up to 250ms before anyone looks at it. That is a *rate limit*, not
      D11 calibration, so it is legitimately tunable — but only with this measurement in hand.
      On **Instagram** the quiet gap adds nothing: it resets on every DOWN, so the first DOWN of
      a swipe counts immediately. Report the numbers, don't guess.
- [ ] Sanity: the count still ends up **correct**, not just fast. 15 swipes → 15 ±2 on IG.
- [ ] The number **never goes backwards** mid-scroll (that would mean the merge regressed).
- [ ] Settings → **Clear data** with the bubble on screen → total goes to **0 and stays 0**. If
      it snaps back to the old number, the in-memory counts were not reset (D39).
- [ ] Force-stop and reopen → the total is whatever Room persisted, i.e. **counts survived**.

### Run 3 — platform icons in the panel
- [ ] The panel's bars are labelled with **glyphs, not `IG`/`YT` text** — a camera for
      Instagram, a video player for YouTube.
- [ ] Glyphs are **legible at a glance** over a moving video, and tinted to the same recessive
      white the text labels were.
- [ ] Bars are still **proportional to each platform's share of today's total**, longest first,
      and the **counts still sum to the headline total**.
- [ ] Rows still **line up** — the icon column is the same width the text column was.
- [ ] **Note on the art:** these are deliberately GENERIC glyphs (Material Icons, Apache 2.0),
      not the platforms' brand marks — the panel draws inside someone else's app and a
      reproduced logo is a trademark question with no upside. If you want the real marks, drop
      licensed assets in at `res/drawable/ic_platform_*.xml`; `PlatformSpec.iconRes` already
      points there and no code changes.

### Run 4 — no window churn (D30 regression check)
The whole D38 change is `updateViewLayout` calls on the already-attached window. It must not
have become an add/remove.
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] Expand and collapse **ten times** → **one** construct for the bubble window, then resizes
      only. A construct/destruct pair per toggle is the D30 regression — stop and report.
- [ ] **Drag the pill around for ~10s** → resizes only, no construct/destruct. This is new
      exposure: placement now runs on every touch-move.
- [ ] **Rotate twice** → resizes only.
- [ ] Cross 50 or 150 so the guilt nudge fires (it widens the pill to 240dp and then shrinks
      it back) → resizes only, and the nudged pill stays on screen at the right edge.
- [ ] No repeated "displaying over other apps" system notification through any of the above.

### Not in this step
- YouTube is **still `Maturity.BETA`**. Unchanged by this session.
- The `IDENTITY_SCAN_MIN_MS` question in Run 2 is deliberately left open pending the numbers.

---

## SUPERSEDED: bubble mascot fill + tap-expand panel (D37)

Two changes, no detection touched (thresholds still 50/150, no limit/gating/storage changes).
(1) The mascot now actually fills the bubble — the D36 import had never run its shared trim,
because three masters were opaque, so the 40dp bitmap was a full square canvas with the
character at ~70% of it. (2) Tapping the bubble expands a stats panel **in place** instead of
launching the app.

Build is green: `testDebugUnitTest` **86 tests pass** (9 new, `BubbleBreakdownTest`),
`assembleDebug` succeeds, and all 35 mascot drawables re-imported at the new aspect
(33x40dp bubble / 98x120dp hero, exact per density: 40/60/80/120/160px tall).

**The opaque-background caveat from D36 is GONE** — all four masters are keyed now, so
nothing should render as a dark box. If one does, that is a regression, not the known issue.

### Run 1 — bubble mascot fill ratio
Measured off the shipped xxhdpi drawable, so the device should agree: character height went
**73% → 84%** of the mascot box and width **68% → 95%** (opaque area 36% → 57%).
- [ ] The mascot **fills the pill vertically** — its head is near the top inset and its
      sneakers near the bottom, with a small even margin, not a ring of dead space.
- [ ] No dark **square** behind the mascot in any state. Cracking and fried were the opaque
      ones; they must now sit directly on the tinted pill like healthy always did.
- [ ] Still **crisp, not soft** at arm's length. Softness means a density bucket is being
      scaled at runtime; report it, don't enlarge the view.
- [ ] The mascot still renders at a **consistent size across all three states** — this is the
      property the *shared* crop protects, and the thing most likely to have broken. It must
      not appear to grow when the count crosses 50 or 150. A size jump means the trim went
      per-image; report it rather than nudging the layout.

### Run 2 — tap expands the panel in place
- [ ] Tap the bubble (no drag) → it expands **in place** into a panel. It must **NOT** open
      ScrollKiller (that was the old behaviour) and must not appear as a second window.
- [ ] Top of the panel reads `N in ~M min` with N matching the compact total.
- [ ] One **horizontal bar per platform with a count today**, longest first, each labelled
      with its short name and its count (e.g. `IG ▓▓▓▓░ 30`, `YT ▓▓░░░ 14`).
- [ ] Bars are **proportional to share of the total** — the bar lengths visibly add up to the
      whole track across all rows.
- [ ] The bar counts **sum to the headline total**. They come from the same emission, so a
      mismatch means something reintroduced a second collector — report it.
- [ ] **No row for an app with zero today.** Scroll only Instagram and there must be exactly
      one bar; no empty Snapchat/TikTok/Facebook rows.
- [ ] With nothing counted today (fresh install / after Clear all data) a tap does **nothing** —
      it refuses to open an empty panel. That is intended, not a dead tap.

### Run 3 — tap collapses back to the compact bubble
- [ ] Tap again → the panel closes and the bubble returns to just **mascot + total**. There
      should be **no `IG 30 · YT 10` subtitle** on the compact pill any more (superseded by
      the bars).
- [ ] **Drag still works** in both states, and dragging does not toggle the panel (movement
      beyond touch slop is a drag, not a tap).
- [ ] Leave Reels with the panel **open**, come back → the bubble is **compact** again, not
      still expanded.
- [ ] Cross 50 or 150 with the panel **open** → the guilt-line nudge collapses the panel,
      shows the line for ~4s, then returns to the compact count. (Re-open by tapping.)

### Run 4 — no window churn from expanding (D30 regression check)
This is the main risk in the change: the panel is a **child** toggling VISIBLE/GONE inside the
same window, which should only ever *resize* the surface.
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] Expand and collapse the panel **ten times**. Expect **one** construct for the bubble
      window and then only resizes. A repeated construct/destruct pair per toggle is the D30
      regression — stop and report; it would mean the ROOT is going GONE somewhere.
- [ ] Same check while crossing a threshold (the mascot drawable swaps) and while the digit
      count changes width (9→10, 99→100): resizes only.
- [ ] No repeated "displaying over other apps" system notification while toggling.

### Run 5 — the other two mascot surfaces still look right
The art is no longer square, so both 120dp consumers were changed to bind height.
- [ ] Home's hero mascot is **not stretched or squashed** — the character's proportions match
      the bubble's.
- [ ] Block screen GUARDIAN likewise. Still needs a platform with `blockEnabled` true and
      `Maturity.STABLE` to fire at all — today that is nobody, so this one stays **blocked**.

### Not in this step
- YouTube is **still `Maturity.BETA` in code**. The STABLE promotion and the motivational /
  idle / deep-link-entry lines were deliberately scoped OUT of this session (bubble fill +
  panel only) and are their own next session. The YT block further down is still live.

---

## SUPERSEDED: mascot art acceptance (D36)

Runs A–C below are **replaced by Runs 1–5 above** (D37 re-imported every drawable and changed
how the bubble draws the mascot). Run D is unchanged and still blocked. Kept for the record.

Art-only change: the emoji brain is gone, replaced by real mascot PNGs resolved through
`MascotArt`. Thresholds are untouched (50/150), so the *when* of each state is already
covered by the older runs — these checks are about the *what it looks like*.

Build was green: `testDebugUnitTest` 77 tests pass, `assembleDebug` succeeds, and all 35
mascot drawables are present in the APK across mdpi…xxxhdpi.

**Three masters still have opaque backgrounds** (`cracking`, `fried`, `guardian`) and will
render as dark boxes until they are background-removed and re-imported. Expect that; it is
not a wiring bug. Only `healthy` is final. — RESOLVED by D37: the importer keys them now.

### Run A — Home hero crosses states
- [ ] At a total under 50 the hero shows the **bright, upright** mascot (HEALTHY).
- [ ] Crossing 50 swaps it to the **tired, cracked** mascot (CRACKING).
- [ ] Crossing 150 swaps it to the **melted, spiral-eyed** mascot (FRIED).
- [ ] The mascot renders at a consistent size across all three — it must not appear to grow
      or shrink between states. A size jump means the masters were trimmed inconsistently;
      report it rather than nudging the layout.

### Run B — bubble mascot is crisp at 40dp
- [ ] The bubble shows the mascot to the left of the count, matching Home's state.
- [ ] It is **crisp, not soft** — the face reads at arm's length. Softness means the density
      bucket is being scaled at runtime; report it, don't just enlarge the view.
- [ ] The mascot swaps at the same 50/150 thresholds as Home.

### Run C — no window churn from the art (D30 regression check)
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] Scroll through a threshold crossing (so the drawable actually swaps). Still **one**
      construct for the bubble window, then resizes only. The compound-drawable swap is a
      resize at most; a construct/destruct pair is the D30 regression.

### Run D — block screen GUARDIAN
- [ ] The block screen shows the **"stop" hand pose** mascot, not the fried one.
- [ ] Needs a platform with `blockEnabled` true and `Maturity.STABLE` to fire at all — today
      that is nobody, so this run is **blocked** until the block is switched on for Instagram.

### Not in this step
- YouTube is **still `Maturity.BETA` in code**. The promotion to STABLE was scoped out of this
  session (art only), so the block immediately below is still live and still unresolved.

---

## YouTube IDENTITY_CHANGE acceptance + total bubble (D34/D35)

Still pending: YT remains BETA in `PlatformSpec`. Everything below it is older, still-valid
checklist.

Build is green: `testDebugUnitTest` 77 tests pass, `assembleDebug` and `compileReleaseKotlin`
both succeed. **YouTube is still BETA** — runs 1 and 2 below are what promote it to STABLE.
If either fails, paste the transcript; YT stays BETA rather than being nudged over the line.

### Run 1 — 15 swipes must count 15 ±2
```
adb logcat -c
adb logcat -s ScrollKiller > yt_swipe.log        # leave running
adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.DIAG_LABEL --es label "YTPROBE_SWIPE"
```
Open YouTube Shorts and swipe **15** Shorts at a normal pace, then stop.
- [ ] YouTube Shorts on the Apps tab moved by **15 ±2** (note the before/after numbers).
- [ ] `grep identity-counted yt_swipe.log | wc -l` is ~15 — roughly one per swipe.
- [ ] The bulk of the lines are `identity-unchanged`. That is the working state, not noise.
- [ ] `entryCredit=false` on **every** line (D29's credit is suppressed for YT now — a `true`
      here means the double-count is back).
- [ ] `identity="@…"` shows real channel handles changing once per swipe. On an
      `identity-unreadable` line the probe falls back to printing the `id:text` pairs it found
      in the tree instead — if those dominate, paste them; they name the fix.

### Run 2 — 30s idle must count ZERO (the overcount this whole design prevents)
```
adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.DIAG_LABEL --es label "YTPROBE_IDLE"
```
Sit on **one** Short for 30 seconds. Do not swipe. Let it loop.
- [ ] The YouTube count **does not move at all**.
- [ ] Zero `identity-counted` lines in that window.
- [ ] The subtitle / "like this video along with 79K other people" / "Auto-dubbed" content
      changes appear as `identity-unchanged` (or `identity-unreadable`) — never as counts.

If the count DOES move here, the `identity=` column names what it flapped to — paste those
lines, that string is the fix.

### Run 3 — the bubble shows the SUM
- [ ] Scroll ~30 Instagram reels, then ~10 YouTube Shorts.
- [ ] The bubble reads **40** (not 10, not 30) with a smaller `IG 30 · YT 10` under it.
- [ ] Home's total matches the bubble exactly.
- [ ] The breakdown line is **absent** when only one app has counts (no "IG 40" under a "40").
- [ ] The mascot follows the TOTAL — HEALTHY→CRACKING flips when the *combined* count crosses 50.

### Run 4 — no window churn (D30 regression check, and the D35 risk)
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] Across the whole session: **one** construct for the bubble window, then resizes only.
      A construct/destruct pair repeating every ~3–5s is the D30 regression — stop and report.
      The two-line text is a *resize* of the existing surface; it must not re-create it.

### Run 5 — Instagram unregressed
- [ ] Re-run the ±2/50 swipe calibration on Reels. IG's path (`DELTA_Y_FORWARD`) is untouched
      by this change and must still hold. It also must **not** gain any content-changed
      counting — that path returns immediately for a non-identity platform.

### Not in this step
- Block screen still never fires: `blockEnabled` is false for every platform, and BETA
  platforms are barred regardless. That's the next step, along with the UI/visual pass.

---

## Phase 2 Step 1 acceptance (guilt pack + YT Beta gate)

Build/install: `assembleDebug` is green (54 unit tests pass, including one that parses the
real `assets/guilt_pack.json` — so a broken pack fails the build, not the device).

### Guilt pack — the actual acceptance check
- [ ] **Varied lines, no immediate repeats.** The bubble nudge is the fast way to see this:
      the bubble shows a guilt line for ~4s when the count crosses **50** (🧠→🤯) and **150**
      (🤯→💀), then collapses back to `🤯 51`. To see many lines quickly instead of scrolling
      to 50 twice, clear data (Settings → Clear all data) and re-cross the threshold, or watch
      the block screen once Step 2 turns it on.
- [ ] Nudge wraps to ~2 lines and does **not** stretch across the screen; the bubble returns
      to `emoji + count` afterwards.
- [ ] A count arriving mid-nudge does **not** wipe the line off screen early (the tint may
      change; the text should not).
- [ ] Leaving Reels mid-nudge cancels it cleanly — coming back shows the count, not a stale line.
- [ ] Re-entering Reels at an already-fried count does **not** fire a nudge (nothing flipped).
- [ ] Bubble toggled OFF in Settings → no nudge appears at all.
- [ ] **No new window churn** (this is the D30 regression risk): filter
      `adb logcat | Select-String BLASTBufferQueue` while crossing a threshold. Expect a
      *resize*, not a construct/destruct pair.
- [ ] `adb logcat -s ScrollKiller` shows `guilt pack loaded: default-en rev1, 35 lines` once.
      If it says "using fallback", the asset didn't ship — stop and report.

### YouTube Beta gate
- [ ] Today tab + Apps tab show a small **BETA** pill next to YouTube Shorts, TikTok, and
      Snapchat — and **not** next to Instagram Reels.
- [ ] YouTube Shorts counts. SUPERSEDED by D34: the "expected large undercount" note here was
      true only while YT relied on the landing-Short entry credit. YT now counts via
      IDENTITY_CHANGE and is expected to be ACCURATE — see the acceptance runs at the top.

### Not in this step (don't test yet)
- Block screen still never fires: `blockEnabled` is false for every platform. Step 2 turns it
  on for Instagram only.
- The daily-limit slider and the Healthy/Cracking/Fried labels are Step 3.

---


Things that can't be proven off-device (accessibility events, overlays, real Instagram
view trees). Run on a physical device with Instagram installed. Filter logs with:

    adb logcat -s ScrollKiller

## Onboarding
- [ ] Fresh install → **Disclosure** screen shows (accessibility not yet enabled).
- [ ] Tap "Enable in Settings" → Accessibility settings open; enable **ScrollKiller
      Detector** → press Back → app advances to the **overlay permission** step.
- [ ] Overlay step: tap "Allow the bubble" → "Display over other apps" settings open;
      grant it → Back → app advances to **Home**.
- [ ] Overlay step is skippable: on a run where overlay is NOT granted, tap "Not now" →
      lands on Home; relaunch app → overlay step does NOT reappear (skip persisted).
- [ ] Grant overlay later (Settings) → returning to app (onResume) reflects it; bubble
      starts appearing in Instagram without reinstall.

## Bubble behaviour (overlay granted)
- [ ] Open Instagram Reels → bubble (🧠 + count) appears.
- [ ] Scroll reels → bubble count ticks up live and MATCHES the Home count for the same
      day (open Home to compare — one source of truth, no drift).
- [ ] Leave Instagram (home button / recents / another app) → bubble disappears.
- [ ] Re-open Instagram → bubble reappears.
- [ ] Bubble never appears on the launcher, in Settings, or in any untracked app.
- [ ] Drag the bubble → it follows the finger and stays where dropped.
- [ ] ~~Tap the bubble (no drag) → ScrollKiller **Home** opens.~~ SUPERSEDED by D37: a tap now
      expands the breakdown panel in place and deliberately does **not** open the app. See
      Runs 2–3 at the top.
- [ ] Bubble does not visibly jank/stutter Instagram scrolling; no battery warning after
      an extended session.

## Bubble behaviour (overlay NOT granted / skipped)
- [ ] Detection still works: scroll reels → Home count increases.
- [ ] No bubble appears anywhere; no crash, no ANR (show() no-ops on missing permission).

## Edge cases
- [ ] **Comments-scroll false-count** (D15 / RecyclerView deltaY≈996 finding — VERIFY):
      open a reel's comments and scroll the comment list. Expected: reel count does NOT
      increase (comments RecyclerView reports deltaY=0 / no forward advance). If it DOES
      over-count, drop `androidx.recyclerview.widget.RecyclerView` from
      `PlatformRegistry.instagram.containerHints` (see D15) and re-test.
- [ ] Comments open should also not spuriously toggle the bubble (still same IG package).
- [ ] **IME / dialog over Instagram**: open the comment keyboard or a share sheet. Note
      whether the bubble briefly hides (a keyboard/dialog can be a different package that
      fires window-state-changed — known v1 limitation per D16). Record severity: brief
      flicker = acceptable; bubble staying gone = needs a package/window-type filter.
- [ ] Rotate the device in Instagram → bubble stays usable (position may reset; must not
      crash).
- [ ] Force-stop ScrollKiller, reopen Instagram → service rebinds, bubble returns.
- [ ] Disable the accessibility service while the bubble is showing → bubble is removed
      (service onUnbind/onDestroy teardown).
