# HANDOFF — manual on-device test checklist

> **Editing any doc? Check fence balance before you finish.** An unclosed ``` does not fail a build —
> it silently swallows the rest of the vault's render, which is how one stray fence in PROJECT_MAP
> hid everything after it. Every count below must be **EVEN** (D56):
> ```
> for f in CLAUDE.md HANDOFF.md docs/*.md ScrollKiller/*.md; do echo "$(grep -c '^```' "$f") $f"; done
> ```

## ✅ CLOSED: the block saga (D72) — no longer a checklist

**The attach-timing question is ANSWERED and the block is verified working on a device.** The
capture read `attachedSync=false` → PENDING_ATTACH → `ATTACH LANDED (listener)` → SHOWN →
`PROBE next-frame attached=true`, and the buttons were exercised live: `challenge.open` → done,
`chooser.option[forehead_30]` → done, `challenge.exit` → done → launcher started. The window was
always healthy; the one-frame-early `isAttachedToWindow` read was the entire root cause, and D52's
and D70's "the ROM is refusing" attribution is corrected in D72.

Nothing below in the attach-timing section needs re-running unless a future change touches
`BlockScreenController.show`. What DOES remain open is the YouTube calibration (Run E) and the
restored reprieve (new Run H).

## ← CURRENT: the unified limit + strict mode (D76/D77), and the YouTube block (D73)

The block itself is proven, so a failure in these runs is a YouTube or a reprieve problem — it is no
longer confounded by the block failing to draw at all.

### Run K — first run opens on the welcome screen (D78)
**Needs a FRESH INSTALL** (`adb uninstall com.scrollkiller` first) — the whole point is the
first-run experience, and an upgrade deliberately skips it.
- [ ] Fresh install → the app opens on **"How much do you actually scroll?"** with the healthy
      mascot, a card reading **Today / 0**, and "Show me". It must NOT open on the disclosure.
- [ ] The welcome asks for **nothing** — no permission prompt, no Settings deep-link, no toggle.
- [ ] Tap "Show me" → the **disclosure** screen, unchanged. **Invariant 5:** the disclosure must
      still appear before you are ever sent to Accessibility Settings. If tapping through lands on
      the dashboard or the overlay step, stop — that is a Play-policy defect.
- [ ] Kill the app from the welcome screen without tapping through, relaunch → welcome **again**
      (the flag is written on the way out, so an unfinished launch does not consume it).
- [ ] Tap through, then relaunch → welcome does **not** reappear.
- [ ] **THE UPGRADE CASE.** Install the previous build, grant accessibility, then install this one
      over it. It must open on the **dashboard** — an existing user must never be shown a
      "here's what this app does" screen, which would read as the app having reset itself.

### Run L — empty states (D78)
- [ ] Fresh install with accessibility granted but nothing scrolled → Today's **"By app"** card
      reads "Nothing counted yet…" rather than being a titled empty box.
- [ ] The **Apps** tab shows its explanatory line, not a bare "Apps" heading over blank space.
- [ ] Settings → **Clear all data** → both empty states return immediately.
- [ ] Tone check: neither reads as an error. No red, no warning icon, no apology — nothing counted
      is the app working, not failing.
- [ ] Scroll one reel → both empty states are replaced by real rows.

### Run I — the ONE daily limit (D76)
Per-platform limits are gone. One slider, one budget, summed across every blocking app.
- [ ] Settings shows **one** "Daily scroll limit" slider, not one per app. Under it, a
      **"Counts: Instagram Reels · YouTube Shorts"** line naming exactly which apps are in the
      budget.
- [ ] **Upgrade migration.** On a build that already had per-platform limits set (e.g. IG 60,
      YT 40), the new single limit reads **100** — the sum of what you actually chose, so your
      ceiling is unchanged. If you had set only ONE slider, the limit is that value. If you never
      touched them, it is 100 (the default). A limit that came out *lower* than the sum is the
      migration bug to report — it would block you earlier than you ever asked for.
- [ ] **The budget is shared.** Set the limit to 20. Scroll ~12 reels on Instagram, then switch to
      YouTube Shorts and keep going. The block must fire on **Shorts** at a combined 20 — even
      though neither app alone reached it. This is the whole point of D76 and the case the old
      per-platform shape got wrong.
- [ ] `block: attempt at 20/20 on YOUTUBE → …` — the left number is the COMBINED blocking total,
      not YouTube's own count.
- [ ] **SHADOW apps do not spend the budget.** With TikTok or Snapchat installed, scroll them a
      lot. Their counts appear on the Today tab (correct) but must **not** move the number the
      block fires at. If scrolling Snapchat pushes you into a block on Instagram, stop and report:
      Snapchat is a known overcount (D32) and must never be able to cover a screen.
- [ ] **A SHADOW app never gets blocked**, however far past the limit the total is — standing in
      TikTok with the budget spent shows the bubble, never the block.

### Run J — strict mode, and the sensorless device (D77)
The free "5 more minutes" is gone for good. **This run is invariant 6, so treat any failure as a P0.**
- [ ] The block panel shows **Exit** and **"Earn your way out — 15 minutes"**, and **no third
      button**. No gap or stray outline where the snooze used to be.
- [ ] **Exit works** from the block, the chooser and a running challenge.
- [ ] **Hardware BACK works** from all three panels (routes through `BlockRootView.dispatchKeyEvent`,
      so it must work whatever holds focus — try it after tabbing focus onto a button).
- [ ] Complete a challenge → **15 minutes of quiet**, then the next reel blocks again. This is now
      the only reprieve that exists, so if it broke, the way past the block is gone entirely.
- [ ] **THE SENSORLESS CASE — the one that matters most.** On a device (or emulator) with no usable
      challenge sensor, or with motion permission declined and no accelerometer/proximity fallback,
      the block panel is **Exit alone**. Confirm that **Exit still leaves** and **Back still leaves**.
      That is the strictest state the app can be in and the only control on the screen; if either
      fails there, the block is a trap and that is a P0 against invariant 6.
      *(`BlockEscapeTest` asserts this structurally against the layout on every build — this run is
      the device half of the same claim.)*
- [ ] Settings → Accessibility: the service description must **no longer** promise "you can always
      ask for a few more minutes". It should read "You can always leave the block." A stale promise
      here is a Play-policy claim about what the app does, not a copy nit.

### ~~Run H — "5 more minutes" is back and works (D75)~~ — SUPERSEDED by Run J (D77)
**Do not run H.** D77 deleted the snooze for good, so every check below is now inverted. Kept for
the record only; the Exit/Back/challenge checks live in Run J.

D74 deleted it; D75 put it back pending a product call. It has never been on a phone in either state.
- [ ] The block panel shows **both** reprieves: "5 more minutes" (quiet ghost outline) and
      "Earn your way out — 15 minutes". Exit is still the loudest control on the screen.
- [ ] Tapping "5 more minutes" **dismisses the block and lets you keep scrolling.** The count keeps
      climbing while you do.
- [ ] After ~5 minutes the **next** reel re-blocks. Not a reel before it, and no timer fires in
      between — the grace is a deadline compared on each count emission.
- [ ] The reprieve **survives leaving Instagram and coming back**, and survives force-stopping
      ScrollKiller (it is persisted, D49). A reprieve a crash silently revokes is a broken promise.
- [ ] A completed challenge still grants the **longer** 15-minute reprieve. If the two feel the
      same, the inequality broke — `BlockLimitsTest` pins it, so report rather than retune.
- [ ] `adb logcat -s ScrollKiller` shows `TAP snooze → done` when tapped. A tap that logs entry but
      not `done` means the handler is a no-op; a tap with no log at all means the touch never
      arrived. Those need opposite fixes (D71).

### Run E — the YouTube limiter actually fires (D73)
YT counting is already proven accurate (5 distinct channels counted, idle and likes ignored). This
run is about **the block**, which has never been on a phone for YouTube.

**Build:** a **debug** build (`assembleDebug`). The `YTPROBE` lines below are `BuildConfig.DEBUG`-only
and are the evidence for the surface half of this run.

**Setup — do these in order, or the run is not deterministic:**
1. Settings → **Clear all data**. This zeroes today's counts *and* any grace deadline left over
   from a snooze or a completed challenge — a live grace silently suppresses the block and would
   read as "the limiter is broken".
2. Settings → **YouTube Shorts limit → 20**. That is `MIN_DAILY_LIMIT`, the lowest the slider goes
   (range 20–300, step 10), so it is the fastest honest crossing available.
3. Start the log:
   ```
   adb logcat -c ; adb logcat -s ScrollKiller
   ```

**Then open Shorts and swipe. Confirm, in this order:**
- [ ] **The YT slider exists in Settings at all.** If it does not, `blocksAtLimit` is false for
      YouTube and nothing below can pass — stop and report that.
- [ ] **Counting on the Shorts surface.** Swiping produces lines carrying **both** of these:
      ```
      YTPROBE type=CONTENT_CHANGED ... ytCounted=<n> branch=identity-counted ... marker=MATCH(reel_recycler)
      ```
      `branch=identity-counted` is the count; `marker=MATCH(reel_recycler)` is the surface. If you
      see counting with `marker=NO_MATCH`, the gating is wrong — **stop, that is the mis-gate this
      run exists to catch.**
- [ ] **The block attempt fires at the limit, on YouTube, against YouTube's own count:**
      ```
      block: attempt at 20/20 on YOUTUBE → PENDING_ATTACH
      ```
      The two numbers are `YT count / YT limit`. If the left number is the grand total across
      platforms rather than YouTube's own, that is a per-platform regression — report it.
- [ ] **The block actually draws:**
      ```
      block: SHOWN on YOUTUBE via=listener (focused=true bubbleAttached=...)
      ```
      `via=listener` is the expected path per D72; `via=next-frame` is equally healthy.
      `via=deadline` means the attach was slow — note it, it is worth knowing. A
      `no-attach-by-deadline` line instead means a genuine refusal, which after D72 would be new.
- [ ] **The block visibly covers the Shorts player**, and it is ScrollKiller's own screen (dark ink
      ground, "Reels are Locked", Exit loudest).
- [ ] **Write down the count it fired at versus 20.** A few either way is D73's accepted error bar,
      but these numbers are the calibration data D57 has been waiting five sessions for.
- [ ] **Exit works**, and re-entering Shorts while still over the limit **re-blocks** (expect a
      second `block: SHOWN on YOUTUBE`, which is correct re-fire, not churn — D72).
- [ ] The **Beta badge is still on YouTube** in the Apps tab. It must not have disappeared: the
      count is still uncalibrated and D73 turns on eligibility and nothing else.
- [ ] **Instagram still blocks exactly as before.** YT joining must not have moved IG's behaviour.
- [ ] **ReVanced too, if installed** (`app.revanced.android.youtube`) — same spec, same markers,
      never separately toured, so this is the likeliest miss.

### Run F — the limiter does NOT fire where it must not
**This is the half that matters more**, because D73 lets an *uncalibrated* count cover a screen. A
block over the wrong YouTube surface is a P0-adjacent bug.

**What the code guarantees, so you know what you are testing.** YouTube's doom surface is set in
exactly one place, `onSurfaceEvent`, and both call sites are behind a marker match — the
content-changed path returns early unless `reel_recycler` matched, and the scroll path is wrapped in
`if (markerMatched)`. The block only renders while on-surface. So a block on the home feed should be
*structurally impossible*; this run is checking that the marker itself does not appear somewhere
unexpected.

Keep the limit at 20 and the count above it, so the block is armed the whole time.
- [ ] **YouTube home / subscriptions feed**, scrolled well past a **Shorts shelf** → **no block, and
      no new count**. This is the exact false-match D28 dropped the `shorts_*` guesses to avoid.
      Expect `marker=NO_MATCH` on any `YTPROBE` lines, and **no** `block: attempt ... on YOUTUBE`
      line at all. A block here: **stop and report.**
- [ ] **Search results** (`results`) and **browse** (`browse_fragment`) → no block, `NO_MATCH`.
- [ ] A **normal, non-Shorts video** and its **comments** → no block.
- [ ] **The 3-second hysteresis edge.** From a blocked-and-exited state, leave Shorts and land on
      the home feed *fast* (Back, immediately). The surface is held for `SURFACE_HYSTERESIS_MS`
      (3s) after the last matching event, so this is the one window where a stale surface could let
      a block draw over the feed. Nothing should appear. If something does, note **how fast** you
      left — that is the whole diagnostic.
- [ ] **TikTok and Snapchat past their limits → no block.** They stay BETA with no
      `blocksWhileUncalibrated` override, and a test forbids that override on a SHADOW platform.
- [ ] Bubble behaviour is unchanged throughout: it shows on Shorts, hides off it.

### ~~Run G — "5 more minutes" is gone, and nothing went with it~~ — SUPERSEDED by Run H (D75)
**Do not run G.** D75 restored the snooze, so its first check ("no third button") is now inverted
and would fail correctly. The Exit / Back / challenge checks below are still worth doing and are
carried into Run H. Kept for the record only.
- [ ] The block panel shows **Exit** and **"Earn your way out — 15 minutes"**, and **no third
      button**. No gap, no stray outline where it used to be.
- [ ] **Exit still works** from the block, the chooser and a running challenge.
- [ ] **Hardware BACK still works** from all three panels (it routes through
      `BlockRootView.dispatchKeyEvent`, untouched by this change).
- [ ] Complete a challenge → **15 minutes of quiet**, then the next reel blocks again. This is now
      the only reprieve that exists, so if it broke, the block has become inescapable-by-effort —
      still exitable, but the feature is dead.
- [ ] On a device with **no available challenge**, the panel is Exit alone. That is intended (D74);
      confirm Exit works there, because it is the only control left.

---

## ← CURRENT: the attach-timing diagnostic build (D72 pending)

**This build is an EXPERIMENT, not a fix.** It exists to settle one question: when the block "fails
to attach", is the window actually being refused, or are we reading `isAttachedToWindow` a frame too
early and tearing down a healthy window?

The hypothesis: `mAttachInfo` is set in `ViewRootImpl.performTraversals()`, a Choreographer frame
*after* `addView` returns — so the check this project has made since D52 reads a healthy window as
failed. If true, every "the ROM is refusing" verdict recorded so far was a misread of our own timing.

**A side effect you should expect: the block may simply start working.** That is not a coincidence,
it is the proof. What matters is the log either way.

```
adb logcat -c && adb logcat -s ScrollKiller
```

### Run A — the decisive lines
Scroll past the limit on Instagram. Read the log in this order:
- [ ] `block: addView returned; attachedSync=false` — **expected false**, and on its own it means
      nothing. This is the reading the old code treated as a refusal.
- [ ] `block: ATTACH LANDED (listener)` and/or `block: PROBE next-frame attached=true`.
      **If either says true, the hypothesis is CONFIRMED** and there was never a ROM refusal.
- [ ] `block: SHOWN on INSTAGRAM via=listener|next-frame` → the block is on screen.
- [ ] If instead you get `block: PROBE deadline attached=false` followed by
      `block: DIAG stage=no-attach-by-deadline ...` — the hypothesis is **dead** and the DIAG line
      is the payload. Paste it whole; it carries `appOpSAW`, `bubbleAttached`, the device string and
      the exact params.

### Run B — the 30-second test that splits the top two hypotheses
- [ ] Trigger a block **over an app that is not Instagram** (any app you can reach the limit in, or
      re-point the limit temporarily). If the block appears everywhere EXCEPT Instagram, the cause
      is Instagram calling `setHideOverlayWindows` — app-specific, not the ROM, and already written
      down as a known risk in STORE_COPY.md.
- [ ] Note `bubbleAttached=` in any DIAG line. **Bubble up + block refused** means nothing is
      refusing our overlays wholesale and the difference is this window's shape (focusable,
      full-screen, opaque — none of which the bubble is).

### Run C — the crash is gone even if the cause is not
- [ ] No crash, no red `FATAL EXCEPTION`, at `OverlayController.kt:415` or anywhere else.
- [ ] If something still throws, it now logs `block: show() THREW — <exact.class.Name>: <message>`
      or `block: render BLOCK branch threw`. **Paste that line** — it names the type, which is the
      one thing the previous log never gave us.
- [ ] The count keeps climbing after any such throw. Before, a throw here cancelled the Flow
      collection and silently killed the counter for that surface.

### Run D — invariant 6 still holds during the pending window
The window is now on screen for up to 250ms with `view` unset — the old trap state, made safe by
D71's ownership tracking. Confirm that safety is real:
- [ ] Exit and hardware BACK both still work on a normally-shown block. ("5 more minutes" was
      removed at D74 and is deliberately no longer on this list — see Run G above.)
- [ ] `--es mode no_attach` (the injector now lies at the probe, exercising the real failure path):
      window is removed at the deadline, **nothing left on screen**, launcher reachable.
- [ ] Leave Instagram mid-block → block goes, no `SWEEP` line.

**Not built this session, deliberately:** the accessibility fallback
(`performGlobalAction`) and the retry-ladder rework. Both wait on this log — they reverse D49 and
are large, and if the hypothesis holds this device may not need them.

---

## D70/D71 — the block draws again, and cannot trap you

**This outranks everything below it, including the CI run.** Invariant 6 failed on a real device:
the block covered the screen, Exit and both other buttons did nothing, Back did nothing, and the
launcher could not be reached. Nothing ships until these pass.

Run with logcat open the whole time — the tap logging is half the fix:
```
adb logcat -c && adb logcat -s ScrollKiller
```

### Run 1 — BUG 1: the block draws with the permission granted (D70)
The device is expected to be carrying a latched `overlay_runtime_denied` from an earlier session, so
this is a recovery test, not just a happy path. **Do not clear app data first** — that would erase
the very state being fixed.
- [ ] Confirm "Display over other apps" is **ON** for ScrollKiller in system settings.
- [ ] Open IG Reels and scroll once. Logcat: `bubble attached; clearing the stale runtime-denied flag`.
      That line is the self-heal firing; it should appear **once**, then never again.
- [ ] Home screen banner is **gone** on the next app open.
- [ ] Scroll past the limit → **the block appears.**
- [ ] `BLOCK PREVENTED ... overlay permission missing` must **NOT** appear. Neither must
      `entered INSTAGRAM with the block dead`. Those two lines are the bug; either one is a fail.
- [ ] Logcat shows `block: attempt at N/limit on INSTAGRAM → SHOWN`.

### Run 2 — BUG 2: every way out works, and each one logs (D71)
Trigger a real block for each. Every tap must produce a **pair** of lines — the second one is what
distinguishes "fired but did nothing" from "never registered".
- [ ] **Exit** → `block: TAP block.exit (showing=true)` then
      `block: TAP block.exit → done (showing=false, window=false)`, then
      `block: exit → launcher started`. **You land on the home screen.**
- [ ] **5 more minutes** → `TAP snooze` pair, block goes, scrolling resumes for 5 min.
- [ ] **Earn your way out** → `TAP challenge.open` pair; chooser appears; each row logs
      `TAP chooser.option[walk_20]` etc.; `TAP chooser.exit` and `TAP chooser.back` both work.
- [ ] **Hardware BACK** → `block: TAP back(hardware) wired=true` then `→ dispatched`, then the
      `TAP back` pair. Block tears down, same as Exit.
- [ ] **BACK from the chooser panel and from a running challenge** — not just the block panel. This
      is the case the old focus-dependent listener would have broken.
- [ ] Leave Instagram with a block up (swipe home) → block disappears. No `SWEEP` line, because
      nothing should be orphaned.

### Run 3 — the trap itself, forced (the reason the injector ships)
This is the reproduction that was impossible before. DEBUG build only.
```
adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.BLOCK_FAIL --es mode no_attach
```
- [ ] Logcat: `block: FAILURE INJECTOR armed=NO_ATTACH (DEBUG only)`.
- [ ] Scroll past the limit. Logcat:
      `block: addView returned but the view never attached; window removed. Cooling down.`
      then `block: FAILED — no attach while canDrawOverlays=true. The ROM is refusing.`
- [ ] **THE POINT: no window is left on screen.** Instagram is still usable, the launcher is
      reachable, and there is no black rectangle. Before the fix this is exactly where the trap was.
- [ ] Wait 30s (the retry cooldown), scroll again → the attempt repeats and still leaves nothing
      behind. **No stacking.** Two overlapping dead windows was the "Exit did nothing" second cause.
- [ ] Now `--es mode throw` → `addView refused`, same outcome, nothing on screen.
- [ ] `--es mode off` → the next block draws normally and Exit works. **Do not leave it armed.**

### Run 4 — Exit is reachable at any size
- [ ] System font size / display size to **maximum**, trigger a block.
- [ ] The panel **scrolls** and Exit is reachable. It must not be laid out past the bottom edge.
- [ ] Repeat on the challenge panel (the 180dp ring is the tallest content).

---

## ← CURRENT: the first CI run (3a)

**Not on-device.** This is the first verification in this project that runs on a machine other than
yours, which is the whole point of 3a — every green build before it was local, and that is exactly
how ten source files stayed untracked for weeks while the build passed daily.

`android.yml` and `backend.yml` trigger on **pull_request** and on push to **main** only, so pushing
the branch alone does not run them (deliberate — running both on push *and* PR pays twice out of a
2,000-minute private-repo budget, D68). `security.yml` has no path filter and no branch filter, so it
should already have run on the branch push.

### Run 1 — open the PR
- [ ] Open `feat/3a-ci-baseline` → `main`: <https://github.com/BasitScribe/ScrollKiller/pull/new/feat/3a-ci-baseline>
- [ ] Three checks appear: **android**, **backend**, **security**.

### Run 2 — android.yml, the risky one
Expect noise. It has never run.
- [ ] **The daemon JVM pin is the predicted failure (D67).** `gradle/gradle-daemon-jvm.properties`
      demands `vendor=jetbrains, version=21`; the runner has Temurin. If it fails, the error names a
      toolchain/vendor mismatch. Work the ladder in D67 in order, and **do not relax the local pin**:
      (1) foojay may auto-provision JBR and it just works; (2) `-Dorg.gradle.java.home=$JAVA_HOME`;
      (3) a CI-only rewrite of the vendor line, never committed.
- [ ] `./gradlew` executes at all — the exec bit was `100644` and is now `100755`. A
      `Permission denied` here means the mode change did not survive.
- [ ] 284 tests run and pass on the runner (same number as local).
- [ ] `app-debug-apk` artifact is uploaded.
- [ ] **Do NOT read a green `lintDebug` as the local lint error being fixed.** That error lives in
      `local.properties`, which is gitignored and absent on a runner. Environment difference, not a fix.

### Run 3 — backend.yml
- [ ] `quality` green: ruff, ruff format, mypy --strict, 34 tests at 100% coverage.
- [ ] `image` green, including the **non-root assert** (`id -u` must print `10001`).
- [ ] Trivy: note whether the base image digest trips a HIGH/CRITICAL **with a fix available**. If it
      does, the correct response is bumping the digest, not adding an ignore entry.
- [ ] The SBOM artifact is attached to the run.

### Run 4 — the budget guarantee (the check most likely to be silently wrong)
- [ ] Push a **docs-only** commit to the branch. **Neither android nor backend may run.** `security`
      should still run — it is unfiltered on purpose, because a secret can land in any file.

---

## Previous: the brand pass (D58)

Visual only — no detection, block, challenge or count logic changed. Build green: **284 tests**,
`assembleDebug` + `compileReleaseKotlin`. Audited: no brand ARGB literal survives outside `Brand.kt`.

### Run A — there is a brand, and it does not come from your wallpaper
- [ ] No purple anywhere: Home, Apps, Settings, block screen, chooser, challenge panels.
- [ ] **Change your wallpaper to something violently coloured, reopen the app.** The palette must not
      move. `dynamicColor` is deleted; if the app shifts hue, it came back.
- [ ] Sweep **light AND dark** (system toggle) on all three tabs. Text must stay readable on both —
      dark theme steps primary down to light cobalt on purpose.
- [ ] Launch cold: the window should flash the brand canvas, **not white** (and not white on a
      dark-theme device).

### Run B — Home reads as one object
- [ ] Hero card: mascot + count + label + time on a faintly tinted card.
- [ ] Cross **50** and then **150**. The whole card's tint shifts with the state, not just the
      numeral's colour — mint → orange → red.
- [ ] The guilt line (once above 50) has an accent rule down its left edge.

### Run C — nav bar
- [ ] Today tab shows the **mascot's head in full colour** — it must NOT be a flat blue silhouette
      (it is an `Image`, deliberately untinted).
- [ ] It is legible, not mush, at real size on your screen.
- [ ] Apps and Settings show a phone and two sliders, and both **do** tint with selection.

### Run D — invariant 6 survived a visual pass (the one that matters)
This is the check a restyle is most likely to have quietly broken.
- [ ] On the block panel, chooser, and challenge panel: **Exit is the most visually prominent button**
      — near-white fill. It must NOT have become a faint ghost button.
- [ ] Exit is still the FIRST control on each panel.
- [ ] **Back leaves Instagram from all three panels. Exit leaves from all three.**
- [ ] "5 more minutes" is deliberately the quietest button — but still clearly tappable and never
      disabled.

### Run E — the bubble did not get more expensive
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] One bubble window construct for the session; no churn while scrolling.
- [ ] Cross a state threshold mid-scroll: the pill re-tints, no window rebuild.
- [ ] Scrolling still feels the same — no new stutter from the restyle.

### Not in this step
- **YouTube is BETA by decision now (D57)**, not pending. It counts, it never blocks. Promotion needs
  the Shorts capture: 15 swipes → 15 ±2 `identity-counted`, 30s idle → zero, `reel_recycler` markers
  matching. Runs 1 and 2 of the D34/D35 block below are still the exact procedure.
- A bundled display font is not shipped; the hero numeral is where one would earn its keep.

---

## forehead hold (D55) — the challenge suite is complete

Challenge 4 of 4. Build is green: **284 unit tests pass**, `assembleDebug` and `compileReleaseKotlin`
both succeed. `HoldDetector` is reused **unchanged** from face-down, so the timing is already covered
off-device — these runs are about **the two sensors and the cheat**.

Setup: Settings → daily limit to **20**, then scroll Instagram Reels past it → chooser →
**Hold the phone to your forehead for 30 seconds**.

### Run A — the chooser is now four deep
- [ ] Four challenges listed + **Surprise me**: Walk 20, Jump 10, Face down 30s, Forehead 30s.
- [ ] Exit still **first**, above the options.
- [ ] Tap Surprise me a few times — it should never hand you the same challenge twice in a row.

### Run B — the hold works
- [ ] Phone to forehead → ring counts **down** 30s→0, arc fills, completes.
- [ ] Long buzz on completion, **15 minutes** granted, block comes down.
- [ ] Take it away at ~15s → resets to **30s** and double-buzzes. Never resumes from 15.

### Run C — the thumb cheat must fail (the load-bearing one)
- [ ] Lay the phone **flat on a table** and cover the proximity sensor with your thumb. The ring must
      stay at **30s** and never tick. Proximity alone is not the challenge — it needs the phone
      **upright** too, or this is a two-second cheat on a thirty-second task.
- [ ] Hold the phone upright but **uncovered** → also must not count.
- [ ] Only both together count.

### Run D — OEM pocket mode (may decide whether this challenge is viable here)
- [ ] Cover the proximity sensor and watch the screen. On stock Android nothing happens —
      `FLAG_KEEP_SCREEN_ON` holds it. **If your ROM locks the screen when proximity is covered**
      (some vendors ship a "pocket mode"), say so: we cannot override that, and it means forehead is
      not viable on this device. It does not block the other three.

### Run E — no leak, both sensors
```
adb shell dumpsys sensorservice | Select-String -Pattern -i "scrollkiller|proximity|accelerometer"
```
- [ ] After completion / cancel / Exit / Back → **neither** proximity **nor** accelerometer
      registered to us. Both were registered on one listener, so a half-release would be the bug.
- [ ] Screen timeout back to normal after each of those paths.
- [ ] **Back** and **Exit** both leave Instagram from the challenge panel mid-hold.

### Run F — graceful absence
- [ ] If your device has no proximity sensor (or use the emulator's sensor panel to disable it), the
      **forehead row must simply be absent** from the chooser — not present and broken.

### Not in this step
- **fake-scroll feed** is still unbuilt — it is in the roadmap's challenge line but is not a sensor
  challenge.
- YouTube is **still `Maturity.BETA`**. Unrelated to this work and now open across several sessions.

---

## face-down hold (D54) — ✅ verified

Challenge 3 of 4. Build is green: **284 unit tests pass**, `assembleDebug` and
`compileReleaseKotlin` both succeed. `HoldDetector` is fully covered off-device (accumulation,
break-resets, flip-flop banking nothing, truncation, backwards clock), so these runs are about
**hardware, the screen, and the buzz** — not the timing logic.

**Before you start: set your screen timeout to 30 seconds.** Run C is specifically about the
interaction between the hold length and the screen timeout, and a 5-minute timeout hides the bug.

Setup: Settings → daily limit to **20**, then scroll Instagram Reels past it → chooser →
**Phone face down for 30 seconds**.

### Run A — the hold accumulates and the label counts down
- [ ] The ring label starts at **30s** and counts **down** — 29s, 28s… — one tick per second, while
      the arc **fills**. (Flip the phone face down, wait ~5s, flip up quickly to read it: you will
      have broken the hold, which is Run B, but you can see the label.)
- [ ] Held continuously for 30s → completes.

### Run B — breaking RESETS, it does not pause (the anti-cheat)
- [ ] Hold face down ~15s, flip up, flip back down. The ring restarts from **30s**, *not* from 15.
- [ ] Flip back and forth **five times**, ~5s down each. Total progress must still be **zero** —
      if any of it banked, the reset is behaving as a pause and the challenge is cheatable.
- [ ] The prompt on screen says the timer restarts, so this is not a surprise to the user.

### Run C — the screen must NOT sleep mid-hold (the one that would ship broken)
With the screen timeout at **30 seconds**:
- [ ] Start the challenge, put the phone face down, and **do not touch it for 30 seconds**.
- [ ] It **completes**. If it stalls at ~28s and never finishes, `FLAG_KEEP_SCREEN_ON` is not being
      applied — that is the whole point of this run.
- [ ] Then cancel a challenge and leave the block on screen: the screen **should** now time out
      normally. The flag must be scoped to the challenge panel, not the whole block.

### Run D — haptics, felt not seen
- [ ] Completion at 30s → **one long buzz**. This is what tells you to flip the phone over at all.
- [ ] Break the hold at ~10s → **two short buzzes**, clearly distinguishable from the long one.
- [ ] Both are felt through a table/cushion with the screen face down.
- [ ] Sanity: no buzz repeating every ~200ms while the phone sits face UP on the challenge screen
      (a hold idling at 0 must not fire the break buzz on every sample).

### Run E — no leak, and invariant 6
```
adb shell dumpsys sensorservice | Select-String -Pattern -i "scrollkiller|accelerometer"
```
- [ ] After completion / cancel / Exit / Back → accelerometer **not** registered to us.
- [ ] Screen timeout back to normal after every one of those paths.
- [ ] **Back** and **Exit** both leave Instagram from the challenge panel mid-hold.
- [ ] Cancel at 20s, re-enter → restarts at 30s, never resumes.
- [ ] No `BLASTBufferQueue` construct/destruct pair when the KEEP_SCREEN_ON flag toggles — it is a
      relayout, not a window rebuild.

### Not in this step
- **Forehead 30s is not built.** `IMPLEMENTED` still excludes `PROXIMITY_HOLD` and a test asserts
  the registry has exactly 3 enabled specs, so it cannot appear in the chooser.
- YouTube is **still `Maturity.BETA`** — unrelated to this work, still unresolved across sessions.

---

## jump challenge + chooser (D53) — ✅ verified

Challenge 2 of 4. Build is green: **266 unit tests pass**, `assembleDebug` and
`compileReleaseKotlin` both succeed. `JumpDetector` is fully covered off-device (shake rejection,
landing ringing, refractory, arm expiry, both baseline paths), so these runs are about the
**hardware and the UI**, not the counting logic.

Setup: Settings → daily limit to **20** (`MIN_DAILY_LIMIT`), then scroll Instagram Reels past it.

### Run A — the chooser offers what this device can actually run
- [ ] "Earn your way out — 15 minutes" appears on the block panel.
- [ ] Tapping it opens the chooser with **Walk 20 steps — 15 minutes** and
      **Jump 10 times — 15 minutes**, plus **Surprise me**.
- [ ] **Exit is the FIRST control** on the chooser, above the options (invariant 6 traversal).
- [ ] Now revoke the motion permission (Settings → Apps → ScrollKiller → Permissions → Physical
      activity → Deny) and re-trigger the block. The chooser must show **Jump only** — no Walk row,
      and **no "Surprise me"** (it is hidden when there is nothing to be surprised by). This is the
      per-spec availability change; if Walk still appears, `ChallengeAvailability` is not being
      consulted. Re-grant afterwards.

### Run B — one jump counts once
```
adb logcat -c
adb logcat -s ScrollKiller > jump.log        # leave running
```
Pick **Jump 10 times**, then jump **10** times at a normal pace.
- [ ] The ring reaches **10/10** in 10 jumps — **not** 5, and **not** 20. A count running ahead
      means the refractory window is too short for your landing; a count lagging means the
      free-fall threshold is too tight. Either way, note how many jumps it actually took.
- [ ] The ring moves **within ~1 second** of the first jump (baselining takes 10 samples, ~200ms at
      `SENSOR_DELAY_GAME`). A ring that sits at 0 for several seconds means baselining is stalling.
- [ ] Completion grants **15 minutes**, the block comes down, reels resume.

### Run C — shaking must NOT count (the load-bearing one)
- [ ] Start Jump, then **shake the phone hard for 15 seconds** without leaving the ground —
      overhand, underhand, whatever a cheater would try. The ring must stay at **0/10**.
- [ ] Also try: slapping the phone against your palm, and setting it down hard on a table. Both
      must score zero.

If any of these count, the free-fall arm is being satisfied by something other than airtime —
paste the sequence you used, because that gesture names the fix.

### Run D — no sensor leak (the battery complaint nobody traces back to us)
```
adb shell dumpsys sensorservice | Select-String -Pattern -i "scrollkiller|accelerometer"
```
Check after **each** of these, separately:
- [ ] Challenge completed → accelerometer **not** registered to us.
- [ ] Challenge cancelled with Back → not registered.
- [ ] Exit from the challenge panel → not registered.
- [ ] Exit from the chooser (nothing was ever started) → not registered.
- [ ] Turning the screen off with the block up → not registered.

### Run E — invariant 6 from all three panels, and no window churn
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] **Back** leaves Instagram from the block panel, the chooser, AND a running challenge.
- [ ] **Exit** does the same from all three.
- [ ] Cancel a challenge at 6/10, re-enter it → progress restarts at **0/10**, never banked.
- [ ] Across all of the above: the block window is constructed **once**. Panel switches are child
      toggles, so there must be no construct/destruct pair when moving block → chooser → challenge.
      A pair there is a D30/D52 regression — stop and report.

### Not in this step
- **Face-down 30s and forehead 30s are not built.** `ChallengeRegistry.IMPLEMENTED` still excludes
  `ORIENTATION_HOLD` and a test asserts it, so they cannot appear in the chooser.
- YouTube is still `Maturity.BETA` in `PlatformSpec` — unrelated to this work, still unresolved.

---

## block stabilisation — churn, runtime revocation, ReVanced (D52)

Your capture's three issues, and **two of them were the same bug**.

`BlockScreenController.view` was doing double duty — the view handle *and* the idempotence guard.
When `addView` failed it was left null, the guard never armed, and **every count emission inflated
a fresh `block_root`**. That is the repeated teardown. And it kept failing because of issue 2: this
ROM refuses `SYSTEM_ALERT_WINDOW` at runtime **while `canDrawOverlays()` still returns true**.

**The reframing:** the authoritative signal is not a permission query, it is whether the window
actually exists. `show()` now reports its outcome, the attach is verified, a detach we didn't ask
for is caught, and a 30s cooldown makes repeat triggers free.

### ⚠️ Setup
- Re-grant "Display over other apps" (you already did) and set the limit to **20**.
- `adb logcat -s ScrollKiller` — every show and hide now names itself:
  `block: SHOWN`, `block: ALREADY_SHOWING`, `block: hide (snooze)`, `block: WINDOW LOST`,
  `block: COOLING_DOWN`.

### Run 1 — no churn while blocked ← **the headline**
- [ ] Trigger the block, leave it up ~60s, tapping the screen and trying to scroll.
- [ ] `adb logcat | Select-String "assignParent|block: "` → **one** `block: SHOWN`, then only
      `ALREADY_SHOWING` (or nothing), and **zero** `assignParent` teardowns until you
      Exit / snooze / complete a challenge.
- [ ] Any repeated inflation here means the churn has another source — capture the `block: hide (…)`
      reason, which now tells us which path fired.

### Run 2 — runtime revocation is caught (the ROM-lies case)
- [ ] With the block **up**, revoke "Display over other apps" from system Settings.
- [ ] Come back → logcat shows `block: WINDOW LOST — system detached it`.
- [ ] The D51 notification fires.
- [ ] Open ScrollKiller → the banner reads **"Your device is blocking ScrollKiller… even though the
      permission looks granted"** — *not* "grant the permission". If it says the latter, the
      observed-denial flag isn't reaching the UI.

### Run 3 — no retry storm after a refusal
- [ ] Still revoked. Keep scrolling past the limit for ~2 minutes.
- [ ] logcat shows `block: COOLING_DOWN` far more often than attempts, and **no repeated
      `block_root` inflation**.
- [ ] Attempts should be roughly **one every 30s** — not zero (a refusal is not permanent) and not
      one per reel.

### Run 4 — recovery
- [ ] Re-grant → within ~30s of the next reel over the limit the block appears.
- [ ] Banner clears on resume; notification clears.

### Run 5 — ReVanced counts
- [ ] Scroll Shorts in **ReVanced YouTube** → the count climbs and `DIAG` shows
      `pkg=app.revanced.android.youtube`.
- [ ] **If `marker=NO_MATCH`:** expected, and not a bug — YT is ENFORCED so it undercounts rather
      than counting a feed, and it is BETA so it can't block anyway. The follow-up is the
      **ReVanced Shorts tour** below. Do not flip anything.
- [ ] YouTube must still be **BETA** in the Apps tab.

### Run 6 — the 17s stall, re-checked
- [ ] Watch `LATENCY` lines for a repeat of `layout=17481ms`.
- [ ] **Gone** → it was the churn, and this closes.
- [ ] **Recurs** → it is real, independent of churn, and worth its own session. Note the seq and
      what was on screen.

### Follow-up owed: ReVanced Shorts tour
Same procedure as the 2026-07-24 tour, on `app.revanced.android.youtube`:
```
adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.DIAG_LABEL --es label "RV_SHORTS"
```
Confirm the Shorts player still emits `reel_recycler`. If it does, nothing changes. If it doesn't,
the new id goes in YouTube's `surfaceMarkers` — and either way YT stays BETA until the two
calibration runs pass.

### Not in this step
- The three remaining challenges — stabilisation first, as agreed.
- Promoting YouTube. Untouched.

---

## STILL OUTSTANDING: permission integrity — never fail silently (D51)

**This section is different from every other one in this file: the failure is reproducible on
demand.** You do not have to wait for it — revoke a permission and it happens.

### What went wrong, and what changed
The run that looked like a block regression was not one. Detection was perfect throughout
(`clips_viewer` MATCH, `counted=true`), the block fired **in code** 100+ times, and Android refused
the window each time (`AppOps: Operation not started op=SYSTEM_ALERT_WINDOW`) because a debug
reinstall had revoked "Display over other apps". **The app counted to 108 and told nobody.**

`BlockScreenController.show()` had inherited the bubble's fail-soft — `Log.d` and return — which is
right for a cosmetic floating pill (D16) and wrong for the core promise. Now:

1. **A Home banner** — the guaranteed signal. Needs no permission and no running service.
2. **A notification** — fires **once per day on entering Reels** with the block dead, and **again
   at the limit**. On the failed run that would have been reel 1, not reel 108.
3. Both read one `PermissionHealth` model, so Home cannot claim to be fine while the block is dead.

### ⚠️ Setup
- Grant **Permission alerts** (Settings → Permission alerts) first, or run 2/3 will correctly show
  nothing — which is itself run 6.
- Set the limit back to **20**.
- Have logcat open: `adb logcat -s ScrollKiller`. The new signal is `W`, not `D`.

### Run 1 — reproduce the original bug, and see it reported
- [ ] System Settings → Apps → ScrollKiller → **revoke "Display over other apps"**.
- [ ] Open ScrollKiller → Home shows **"ScrollKiller is not fully active"** above the mascot, and
      the body text **names** the permission ("Display over other apps is off…").
- [ ] It also says counting still works — that must be there; it is true and it is the difference
      between an honest warning and a scary one.
- [ ] Tap **Fix** → lands directly on the "Display over other apps" screen for ScrollKiller, not a
      settings root.

### Run 2 — the early warning (the reel-1 fix)
- [ ] Still revoked. Open **IG Reels** and scroll once → a notification within seconds naming the
      permission. **You should not have to reach the limit.**
- [ ] Leave Reels and re-enter **three more times** → **still exactly one** notification. If you
      get four, the once-per-day day key is not persisting.
- [ ] Force-stop ScrollKiller, re-enter Reels → **still no second notification today**. This is the
      case an in-memory flag would fail.

### Run 3 — at the limit
- [ ] Still revoked, limit 20. Scroll past 20 → the **same** notification updates to *"You hit your
      limit — ScrollKiller can't block"*. It must **update, not stack** — one notification, not two.
- [ ] Keep scrolling to ~40 → the phone must **not buzz per reel** (`setOnlyAlertOnce`).
- [ ] logcat shows `W ... BLOCK PREVENTED at 21/20 on INSTAGRAM: overlay permission missing`.
- [ ] The count keeps climbing accurately underneath. Detection must be completely unaffected.

### Run 4 — recovery, with no restart
- [ ] Tap the notification → grant the permission → **return to ScrollKiller without force-stopping
      it**.
- [ ] Banner is **gone** on resume.
- [ ] Notification is **gone** (it cancels itself on the next emission once healthy).
- [ ] Scroll one more reel over the limit → **the block appears normally**.

### Run 5 — accessibility off (the banner is the only possible signal)
- [ ] Turn the accessibility service off. Open Home → banner names the **detection service**, and
      Fix routes to Accessibility settings.
- [ ] **No notification appears, and that is correct** — nothing is running to post one. This run
      exists to prove the banner is the layer that cannot fail.

### Run 6 — notifications denied (honest about itself)
- [ ] Deny/disable ScrollKiller notifications, keep everything else granted → Home shows the
      **quieter** "Heads-up alerts are off" banner, not the red one. The app works; it just can't
      warn you out of app.
- [ ] Now also revoke the overlay permission → the banner switches to the **error** tone and names
      the overlay, not notifications (worst-gap-first).
- [ ] Re-grant via Settings → Permission alerts → warnings resume.

### Not in this step
- The three remaining challenges (jump / face-down / forehead). Deferred deliberately: an integrity
  fix outranks new surface area.
- Any change to the bubble's fail-soft — it is genuinely optional (D16) and the banner covers it.

---

## STILL OUTSTANDING: the challenge engine — "Walk 20 steps" (D50)

The block no longer has only a free tap past it. **One challenge, built end to end**, behind a
`ChallengeSpec` abstraction so jump / face-down / forehead are a spec entry plus a sensor strategy
from here — not a rewrite.

1. **"Walk 20 steps — get 15 minutes"** appears on the block beside "5 more minutes".
2. **The reward is deliberately unequal: 15 vs 5.** Equal rewards would make the challenge strictly
   dominated — nobody walks twenty steps for what one tap gives free — so the gap *is* the
   incentive, and a test asserts it can't be tuned away.
3. **Live ring**, one step at a time. Batching is switched off precisely so it doesn't sit still
   and then jump.
4. **Invariant 6 shaped this, it didn't just survive it:** the challenge panel carries its **own
   Exit**. A challenge screen with only "Back" would be a second screen to escape before you can
   escape.

### ⚠️ Before you start
- **Grant Motion access first:** ScrollKiller → Settings → "Motion & step access" → Grant. The
  challenge button is **hidden** until you do — that is correct behaviour, not a bug.
- The permission cannot be requested from the block screen (an AccessibilityService has no
  Activity), which is exactly why the row is in Settings.
- **Set the limit to 20** again so blocks are cheap to trigger.
- The block window is unchanged apart from a second panel inside it — if anything about Exit, Back
  or the D49 runs regresses, that is a **stop-everything**.

### Run 1 — Exit still works, including from inside the challenge ← **DO THIS ONE FIRST**
- [ ] Block fires → tap **"Walk 20 steps"** → the challenge panel appears with a 0/20 ring.
- [ ] Tap **Exit** *from the challenge panel* → launcher, Instagram gone, **no overlay left over
      the launcher**.
- [ ] Re-enter, start the challenge, press **Back** → same as Exit. Back must not merely return to
      the block, and must never reveal reels.
- [ ] Re-enter, start the challenge, press **Home** → everything tears down cleanly.
- [ ] With **TalkBack on**, on the challenge panel: the first control reached is **Exit**, not
      "Back".
- [ ] Anything that leaves you stuck: **stop and report**.

### Run 2 — walk 20 steps
- [ ] Start the challenge → ring reads **0 / 20**.
- [ ] Walk. The ring must move **step by step, live** — roughly one increment per footfall.
- [ ] **If it sits at 0 and then jumps to 8 or 12 at once, that is the batching bug.** Report it;
      it means `maxReportLatencyUs = 0` is not taking effect on this device.
- [ ] At 20/20 the block clears on its own and Instagram is usable again.

### Run 3 — the reward is real (15, not 5)
- [ ] Right after completing, scroll Reels → **no block**.
- [ ] Keep checking past the **5-minute** mark — a block at ~5 minutes means the challenge granted
      the tap's reprieve, which is the exact failure the unequal reward exists to prevent.
- [ ] Around **15 minutes**, the next reel **re-blocks**.
- [ ] Now use **"5 more minutes"** instead and confirm it still re-blocks at ~5. The two paths must
      grant visibly different amounts.

### Run 4 — cancel banks nothing
- [ ] Start the challenge, walk **10 steps**, tap **Back** → returns to the block screen (not out
      of Instagram).
- [ ] Start the challenge again → the ring reads **0 / 20**, not 10 / 20.

### Run 5 — graceful when the sensor isn't there
- [ ] System Settings → Apps → ScrollKiller → Permissions → **revoke Physical activity**.
- [ ] Trigger a block → the challenge button is **gone**. Exit and "5 more minutes" both still work.
- [ ] Re-grant in ScrollKiller → Settings → the button is back on the next block.
- [ ] If you have a device with no step sensor at all: the Settings row itself should not appear.

### Run 6 — no battery leak
```
adb shell dumpsys sensorservice | Select-String -Pattern "scrollkiller"
```
- [ ] After **completing** a challenge → no lingering registration.
- [ ] After **cancelling** → no lingering registration.
- [ ] After **leaving Instagram mid-challenge** (Home while the ring is up) → no lingering
      registration. This is the one most likely to leak.

### Not in this step
- Jump / face-down / forehead challenges — each is now a `ChallengeSpec` plus one sensor strategy.
- Rationing the free tap. Considered and deferred: it needs a persisted per-day counter and a
  day-rollover reset, and it is a better second move than a first one.

---

## ✅ VERIFIED 2026-07-27: THE BLOCK IS LIVE ON INSTAGRAM (D49)

**All six runs below PASS on device.** Exit works from every state, the block fires at the limit on
Reels, it stays silent on feed/DMs/profile, "5 more minutes" grants a reprieve and then re-blocks,
there is no window churn, and the count is still accurate underneath. Invariant 6 holds in
practice, not just in the tests. Runs kept below as the regression checklist — re-run them after
anything that touches the overlay, the surface markers, or the block policy.

The app finally stops you instead of just counting you. Everything below is real on a device for
the first time — **these runs matter more than any previous set in this file**, because from this
build ScrollKiller can cover another app's screen.

**Instagram only.** YouTube, TikTok and Snapchat are `Maturity.BETA` and cannot block however
their flags are set — three tests hold that shut. Nothing about YouTube was touched.

1. **A daily limit you set.** Settings → "Daily limit — Instagram Reels", 20–300 in steps of 10,
   default 100. Persisted, per-platform, and read on every count emission so it is live on the
   *next reel* — not next time you open Instagram.
2. **The block fires on `clips_viewer` and nowhere else.** Same marker counting uses (D26), so it
   physically cannot land on the feed, DMs, profile grid, Explore or Stories.
3. **"5 more minutes"** replaces the old Unlock stub. A persisted wall-clock deadline, so it
   survives leaving Instagram *and* survives ScrollKiller being force-stopped.
4. **Always exitable is CLAUDE.md invariant 6 now**, not a comment.

### ⚠️ Before you start
- **Android 13+ sideload:** the accessibility service may need App info → ⋮ → **Allow restricted
  settings** before it can be enabled. This is OS friction, not a bug.
- **Set the limit to 20** in Settings first. Scrolling to 100 by hand five times is not a test
  plan.
- If the block ever refuses to go away, that is a **P0** — note exactly what was on screen and
  stop. Force-stopping ScrollKiller removes every overlay it owns.

### One bug this session found and fixed, worth knowing while you test
The 3-second surface hysteresis fired while the block was up — and a blocked user *cannot scroll*,
so nothing re-armed it. The block appeared and then **vanished three seconds later**, handing the
reels straight back. If you ever see that behaviour return, the hysteresis fix has regressed.

### Run 1 — Exit works, from every state ← **DO THIS ONE FIRST**
Deliberately first: never raise a block on purpose before the way out is proven.
- [ ] Limit 20. Scroll IG Reels past 20 → block appears.
- [ ] Tap **Exit** → you land on the launcher, Instagram is gone, **no overlay is left over the
      launcher**.
- [ ] Re-enter IG Reels → the block comes back (it should — you are still over the limit).
- [ ] Press **Back** → same result as Exit. Back must never just dismiss the block to reveal reels.
- [ ] Re-enter, then press **Home** (gesture or button) → the block tears down on its own; nothing
      is left over the launcher or the next app you open.
- [ ] Re-enter, then swipe to **recents** and switch to another app → block is gone.
- [ ] With **TalkBack on**: the first control reached is **Exit**, not "5 more minutes".
- [ ] Anything that leaves you stuck: **stop and report**. This is the invariant.

### Run 2 — the block fires at the limit
- [ ] Limit 20, scroll IG Reels to 20 → **full-screen block**, GUARDIAN mascot, "Reels are Locked".
- [ ] The guilt line is **tier-appropriate** and its number **matches the counter you just saw** —
      a line quoting a different count is the D47 bug returning.
- [ ] Keep the block up for a minute → it **stays**. It must not self-dismiss (see the bug above).
- [ ] Move the limit to 40 in Settings, return to Reels → **no block** until you pass 40. The new
      limit must apply on the next reel, not the next app launch.

### Run 3 — NO block off-surface (the one that must never fail)
At a count comfortably over the limit, visit each surface and confirm **bubble only, never the
block**:
- [ ] IG **home feed** — [ ] **DMs** — [ ] **profile grid** — [ ] **Explore** — [ ] **Stories**
- [ ] Stories is the specific one to watch: internally Instagram calls them "reels"
      (`reel_viewer_*`). A block over Stories means the marker has been broadened from
      `clips_viewer` and is a **stop-everything** regression.
- [ ] Open **YouTube Shorts** past 20 → counts, **never blocks** (BETA).

### Run 4 — "5 more minutes" is genuinely granted
- [ ] At the block, tap **5 more minutes** → block goes, reels resume immediately.
- [ ] Scroll for a minute → **no block**, even though you are still over the limit.
- [ ] Leave IG, come back inside the window → **still no block**.
- [ ] **Force-stop ScrollKiller** mid-grace (Settings → Apps → ScrollKiller → Force stop), reopen
      IG Reels → **still no block**. This is the persistence check; an early re-block here means
      the deadline is not surviving process death.
- [ ] Wait out the five minutes, then scroll one more reel → **re-blocks**. It should re-block on
      the *next reel*, not on a timer while you sit there.

### Run 5 — no window churn (D17/D30 regression check)
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] Raise and dismiss the block **5 times** → one construct/destruct pair **per block episode**
      on the block window, and **zero** on the bubble window. The bubble is attach-once and must
      not be disturbed by blocking at all.

### Run 6 — the count is still accurate underneath
- [ ] Set the limit to 300 so nothing blocks, then do **50 deliberate swipes** → count lands
      **50 ±2** and the bubble and Home agree. The block must not have cost us detection.

### Not in this step
- Challenges (face-down / walk / jump) — the next checkbox. "5 more minutes" is the only way past
  the block today; a challenge will become a second, *earned* way past it, never a replacement for
  Exit.
- Blocking on any platform other than Instagram.

---

## STILL OUTSTANDING: the cadence plateaus, and the pool targets are now in the build (D48)

D47 named two levers for the tier-4 content shortfall and took neither. This takes both. **No new
on-device runs of its own** — it changes numbers the D46 runs below already exercise, and it
updates two of them (see the ⚠️ notes in that section). Nothing here blocks the next session.

1. **The cadence plateaus at every 5 scrolls.** `GuiltCadence.SCHEDULE` is now three rows —
   100→every 10, 250→every 7, **320→every 5, and every 5 forever**. The 400→4, 500→3, 600→2 and
   800→every-scroll rows are gone. `FLOOR_SCROLLS = 5` names the plateau and a test asserts no
   count anywhere returns an interval below it.
2. **The pool targets are code.** `GuiltPoolMath.EXPANSION_TARGETS` — T1 18, T2 18, **T3 40,
   T4 150** — asserted against the engine *and* against the shipped pack.
3. **The exhaustion log names both numbers**, the rate-derived need and the committed target.

**⚠️ Read this, it is not what was asked for.** The instruction was to keep 100→10 *through*
500→3 and cap only above that. Those two cannot both hold: **400→4 and 500→3 are themselves
faster than every 5**, so "never fires faster than every 5" necessarily deletes them too. The
stated invariant won. The alternative — keep 500→3 and add a 600→5 row — is non-monotone (every
3 scrolls at 550, every 5 at 650), which contradicts D46's founding principle and would mean
deleting the well-formedness test that encodes it. **The cost: a 400–500/day user now gets a line
every 5 scrolls instead of every 4 or 3.** If you want that band kept sharp, it is a one-line
revert of the table — say so and I will re-cost it. Full reasoning in D48.

### The recomputed content bill
Derived by `GuiltPoolMath` running the real `GuiltFiring` over a simulated day, so it tracks what
ships. Fires/day, and in brackets the 7-day pool that implies:

| scrolls/day | T1 | T2 | T3 | T4 (was) | T4 pool (was) |
|---|---|---|---|---|---|
| 150 | 1 | 1 | 5 (35) | 1 (1) | 7 (7) |
| 300 | 1 | 1 | 5 (35) | 18 (18) | 126 (126) |
| 500 | 1 | 1 | 5 (35) | **57** (62) | **399** (434) |
| 800 | 1 | 1 | 5 (35) | **117** (196) | **819** (1,372) |

**The honest reading: flattening does not rescue the ceiling.** 819 lines is no more writable
than 1,372. What it does is make the *middle* of the curve affordable, which is where the users
are. Tiers 1–3 are unchanged by it and always were going to be — 1 and 2 sit below the schedule's
first row and fire once each per day forever, and tier 3 spans exactly 100–149 at every 10, so it
is capped at 5/day whatever the top of the curve does.

| tier | target | shipped | what the target buys |
|---|---|---|---|
| 1 (50–69) | 18 | 18 | ✅ met, forever |
| 2 (70–99) | 18 | 18 | ✅ met, forever |
| 3 (100–149) | **40** | 18 | **closes the tier permanently** — 35 covers any user at any rate. 22 lines, cheapest complete win in the pack |
| 4 (150+) | **150** | 18 | 7-day no-repeat up to **~320 scrolls/day**. Above that the fallback carries it and logs who is hitting it |

Content expansion is a **parallel task, not done here and not blocking anything**; community
submissions are the long-term tier-4 supply. `GuiltPoolMathTest` asserts the pack is short in
*exactly* tiers 3 and 4 — green today, **red the day the content lands**, at which point the
pinned sizes and D48 get updated together.

Build is green: `testDebugUnitTest` **196 tests pass**, six consecutive full runs.

**One unrelated fix, worth knowing about:** `GuiltSelectorTest`'s least-recently-shown fallback
test was **flaky at roughly 1 run in 3**, pre-existing and not caused by this change. It seeded 3
stale lines against a fallback slice of `ceil(18 × 0.25) = 5`, so two *fresh* lines sat inside
the slice and the weighted draw picked one perfectly legally. It now seeds a full staleness
gradient and asserts the draw lands in the older half — computed *before* the draw, since drawing
records and would otherwise make the chosen line the freshest thing in its own history.

---

## STILL OUTSTANDING: count tokens + 7-day no-repeat (D47)

Two bugs that D46's repetition exposed, plus a content finding you need to act on.

1. **Lines no longer bake the number in.** "One-fifty. Your phone is worried about you" was
   written for count 150 and shown at 312. Lines now say `{count}`, substituted with the live
   total on every render — so the sentence can never disagree with the counter beside it. There
   is a `{minutes}` token too, because "that is half an hour" is the same bug in different units.
2. **No-repeat now spans 7 days, persisted.** It used to be session-scoped and pool-cycle-based,
   which at D46's cadence means a pool cycles in *minutes*. New `guilt_shown` table (DB v2→v3).
3. **The pack is too small for the cadence, and now says so out loud** — see the reckoning below.

**No detection, cadence or threshold changes.** `GuiltCadence.SCHEDULE` and `GuiltThresholds`
are byte-for-byte what D46/D41 shipped.

Build is green: `testDebugUnitTest` **192 tests pass** (26 new — `GuiltTextTest` 13,
`GuiltHistoryTest` 7, `GuiltPoolMathTest` 6), `assembleDebug` and `compileReleaseKotlin` succeed,
`lintDebug` unchanged (its one error is still the pre-existing `local.properties` escaping).

### ⚠️ The pool-size reckoning — read this before the runs
> **SUPERSEDED BY D48.** The tier-4 columns below are pre-flattening: 500/day is now 57 fires and
> 399 lines, 800/day is 117 and 819. The "other lever" in the last paragraph has since been
> taken, and the targets are now 40/**150** and live in `GuiltPoolMath.EXPANSION_TARGETS`. Use the
> D48 tables at the top of this file. Kept here because the runs below reference it.

`GuiltPoolMath` derives this by running the real firing logic over a simulated day, so it tracks
the shipped cadence rather than being a guess:

| tier | fires/day | pool needed (7d) | shipped | verdict |
|---|---|---|---|---|
| 1 (50–69) | **1**, always | 7 | 18 | fine forever |
| 2 (70–99) | **1**, always | 7 | 18 | fine forever |
| 3 (100–149) | **5**, capped | 35 | 18 | short — but *finite*, needs **40** |
| 4 (150+) | 18 @300 · 62 @500 · **196 @800** | 126 / 434 / **1,372** | 18 | 7×–76× short |

Tiers 1 and 2 fire exactly once a day *by construction* — they sit below the schedule's first
row, so only their tier crossing fires. Tier 3 spans exactly 100–149 at every 10, so it is
permanently capped at 5/day whatever anyone does. **Tier 4 is unbounded and is where all the
content pressure lives**, because it starts at 150 and never ends.

**Targets I'd write to: tier 3 → 40, tier 4 → 150–200.** That closes tier 3 permanently and
covers a realistic heavy user (~300 scrolls/day) at tier 4. Chasing 1,372 by hand is not a plan;
above the target the least-recently-shown fallback carries it and logs who is hitting it. The
other lever — flattening the cadence above ~500, where it eats content faster than anyone can
write it — was deliberately left alone this session because D46 was to stay intact.

### Run 1 — no line ever shows a wrong number
The bug: at 312 scrolls the app said "One-fifty".
- [ ] Get past 150 (seed the count — see the D46 handoff below for the `sqlite3` recipe) and
      watch ~10 fires. **Every number in every line matches the pill's count**, exactly.
- [ ] Scroll to ~312 specifically and read the tier-4 lines. None says 150, one-fifty, a hundred,
      seventy, fifty, or any other fixed number.
- [ ] Lines mentioning time (`… minutes gone`) show a **plausible** figure — roughly
      `count × 6 / 60` minutes. At 312 that is ~31 minutes, not "half an hour".
- [ ] **No line displays literal braces** (`{count}`, `{streak}`). Braces on screen means a token
      reached the renderer unhandled — report the text.
- [ ] Watch one line across several scrolls while it is still displayed → the number **ticks up
      with the counter**. That is intended: the line is re-rendered live, not frozen at fire time.
- [ ] Grep check, no device needed — this is enforced by `GuiltPackTest`, so a green build
      already proves it:
      ```
      grep -nE '"text": "[^"]*(fifty|hundred|seventy|thirty|century|hour|minutes)' app/src/main/assets/guilt_pack.json
      ```
      should return **only** lines where the word is preceded by `{minutes}`.

### Run 2 — the same line is not seen twice in a week
The window is 7 days of **wall clock**, persisted in `guilt_shown`, so faking the date works.
```
adb shell settings put global auto_time 0
```
…then set the date by hand in Settings → System → Date & time. Restore `auto_time 1` after.
- [ ] **Day A**: clear data, scroll past 70, then open/close the app ~8 times noting each header
      line. All 8 should be **different**.
- [ ] **Day B, C, D** (advance the date one day each time, force-stopping between): same tier,
      note the lines. **Nothing from day A reappears.**
- [ ] Inspect the table directly — it should have one row per line you have seen:
      ```
      adb shell "run-as com.scrollkiller sqlite3 /data/data/com.scrollkiller/databases/scrollkiller.db \
        'SELECT COUNT(*) FROM guilt_shown;'"
      ```
- [ ] **Jump the date forward 8 days**, force-stop, reopen → lines from day A are **available
      again** (the window expired), and the row count drops as the prune runs at startup.
- [ ] **Force-stop mid-week and reopen** → the exclusions still hold. This is the whole reason
      the table exists; if repeats come back after a restart, the load is not priming.
- [ ] **Settings → Clear data** → `guilt_shown` empties too, and the next run starts fresh.
      (Deliberate — it is the user's data, and it makes these runs repeatable.)

### Run 3 — the undersized pool logs instead of quietly repeating
This WILL happen at tier 4 with the shipped 18 lines; the point is that it is loud and graceful.
```
adb logcat -s ScrollKiller | Select-String "guilt pool exhausted"
```
- [ ] Seed to ~400 and scroll through ~25 fires in one day (tier 4 burns 18 lines fast).
- [ ] Once every tier-4 line has been shown, the log emits **one warning per exhausted draw**
      naming the tier, the pool size, today's count and **the target size**, e.g.
      `guilt pool exhausted: AMBIENT/EXTREME has 18 lines … needs ~434 … EXPAND THIS TIER`.
- [ ] The app **keeps working** — lines still appear, still tier-4, never blank.
- [ ] The repeats it does show are the **least-recently-shown** ones, not the line from a minute
      ago. Watch ~6 fallback fires; an immediate back-to-back repeat is a bug.
- [ ] Tiers 1–3 do **not** log this in normal use. If tier 1 or 2 ever warns, something is wrong
      with the window, not the pack — they consume one line a day.

### Run 4 — the DB migration (upgrade path)
`guilt_shown` is new, so an existing install must migrate rather than crash.
- [ ] Install the **previous** build first, use it enough to have counts, then install this one
      **over the top** (no uninstall) → the app opens, counts survive, no crash.
- [ ] `adb logcat -s ScrollKiller` shows `guilt history primed: 0 lines` on first launch after
      the upgrade. An empty history is correct: nothing is back-filled, so the first week simply
      has no exclusions, which is exactly the pre-D47 behaviour.

### Run 5 — nothing else moved
- [ ] The D46 cadence is unchanged: every 10 at 100–110–120, tightening at 320/400/500.
- [ ] Home, the bubble nudge and the expanded panel still show the **same line** at one moment.
- [ ] No window churn during a burst (`BLASTBufferQueue` — one construct, resizes only).

### Not in this step
- **YouTube: the first Short after entering isn't counted until you scroll.** Known, and
  **accepted** — deferred deep-link entry credit, batched separately. Not a regression here.
- **YouTube is still `Maturity.BETA`.**
- **The pack expansion is NOT done** — it is the next roadmap item, sized above.
- **Earlier handoffs' runs are still outstanding** (D38–D46), see below.

---

## STILL OUTSTANDING: guilt lines repeat on an escalating cadence (D46)

A line used to fire **once per tier crossing** (50, 70, 100, 150) and then the app went quiet for
the rest of the day — so at 400 reels it had already said everything it was going to say. Lines
now **repeat**, on a cadence that tightens as the count climbs.

**No detection touched, and no intensity thresholds touched.** `GuiltThresholds`' 50/70/100/150
are byte-for-byte what D41 shipped. D11's 200ms IG quiet gap and YT's 500ms identity floor are
unchanged.

**The schedule** (`GuiltCadence.SCHEDULE` — one table, edit it there and nowhere else):

| daily total | a line fires |
|---|---|
| 50, 70 | once each, on the tier crossing only |
| 100–249 | every **10** scrolls |
| 250–319 | every **7** |
| 320–399 | every **5** |
| 400–499 | every **4** |
| 500–599 | every **3** |
| 600–799 | every **2** |
| 800+ | every **scroll** |

Two axes, kept separate: **`GuiltTier` decides how hard** (which pool the line comes from,
unchanged), **`GuiltCadence` decides how often**. They compound at the top — at 800 the lines
are the most savage in the pack *and* as frequent as the display allows.

**The one call worth arguing with: the min-display-gap is 5.5s, not the ~1.5s the brief
suggested.** The schedule governs *eligibility*, in scrolls; the gap governs *display*, in
seconds — and at 800+ they genuinely disagree, because every scroll is eligible and a fast
scroller produces one a second. A gap shorter than the 4s a line is displayed for means every
line is overwritten before it can be read, and the pill never collapses, so the bubble stops
being a counter at exactly the moment the app is trying hardest to be heard. So the gap is the
full 4s display **plus** a guaranteed 1.5s counter-only window (`MIN_GAP_MS = DISPLAY_MS +
COUNT_VISIBLE_MS` — derived, not a literal). Practical ceiling at 800+ is ~11 lines a minute.
**If that reads as too slow on a real device, `COUNT_VISIBLE_MS` is the one number to change.**

Build is green: `testDebugUnitTest` **166 tests pass** (19 new — `GuiltFiringTest` 14,
`GuiltCadenceTest` 4), `assembleDebug` and `compileReleaseKotlin` succeed, `lintDebug` unchanged
(its one error is still the pre-existing `local.properties` backslash escaping).

**Getting to a count.** Runs 2 and 3 need counts in the hundreds, which is not something to
reach by thumb. The cadence is driven purely by the daily total in Room, so seed it — **force-stop
first**, because Room holds the database open in WAL mode and writing underneath a live app is
how you get a corrupt page rather than a seeded count:
```
adb shell am force-stop com.scrollkiller
adb shell "run-as com.scrollkiller sqlite3 /data/data/com.scrollkiller/databases/scrollkiller.db \
  \"INSERT OR REPLACE INTO daily_counts (date, platform, count) VALUES (date('now','localtime'), 'instagram', 318);\""
```
Then reopen the app. Force-stopping also clears the in-memory optimistic counts (D39), which
matters: the read path merges Room with them by `max`, so without the restart the old number wins.
Table is `daily_counts (date, platform, count)`, PK `(date, platform)` — verified against
`DailyCountEntity`. If `sqlite3` isn't on the device, the fallback is Run 1 only (100–130 is
reachable by hand in a couple of minutes) plus trusting `GuiltFiringTest`, which covers every
band with an exact expected-fire list.

**Watch the fires without staring at the pill.** Every fire re-renders the bubble; the count is
in `LATENCY` lines already. Easiest confirmation is visual — but note the count showing on the
pill *at the moment the line appears*, since that is the number the schedule is evaluated on.

### Run 1 — every 10 scrolls from 100
Start below 100 so you see the crossing too.
- [ ] Scroll up to **100** → a line fires **on the crossing** (it does not wait for 110).
- [ ] Keep scrolling → the next line is at **110**, then **120**, then **130**. Note the count on
      the pill each time it appears.
- [ ] Between them the pill is a **plain counter** — no line, and it collapses back within ~4s of
      each one appearing.
- [ ] At **150** a line fires **on the crossing**, and it is visibly **harsher** than the ones at
      100–140 (tier 4 pool). Escalation and cadence are independent — confirm both moved.
- [ ] Nothing fires between 50 and 99 except the single lines at **50** and **70**. Scroll
      50→99 and confirm exactly **two** lines in that whole stretch.

### Run 2 — every 5 scrolls at 320+
> **⚠️ UPDATED BY D48.** The second checkbox is superseded — the spacing no longer tightens past
> 320. Replace it with the one marked ✅ below.

Seed to ~318 (see above).
- [ ] Scroll past 320 → lines at roughly **325 / 330 / 335**, i.e. **5 apart**.
- [ ] ~~Cross **400** → the spacing tightens to **4**. Cross **500** → **3**.~~
- [ ] ✅ Cross **400**, then **500** → the spacing **stays at 5**. It must not tighten. If lines
      start arriving 3–4 scrolls apart up here, the old schedule rows are back — stop and report.
- [ ] The lines are all **tier 4** (150+) and still **do not repeat back-to-back** — the
      no-repeat rotation is what stops a tight cadence degenerating into the same three lines.
      Watch ~8 consecutive fires; **any immediate repeat is a bug, report the text.**

### Run 3 — still every 5 at 800+, and the gap holds
> **⚠️ UPDATED BY D48.** The curve plateaus, so 800+ is no longer every scroll. First checkbox
> replaced.

Seed to ~798.
- [ ] ~~Scroll **slowly** (one swipe every ~6s) past 800 → **every single scroll** produces a
      line.~~
- [ ] ✅ Scroll **slowly** (one swipe every ~6s) past 800 → a line roughly **every 5 scrolls**,
      the same spacing as at 320. This is the plateau; **every-scroll firing up here is the bug.**
- [ ] Now scroll **as fast as you can** for ~30s → lines appear at most about **every 5.5s**, and
      **each one is on screen long enough to read** (~4s), with the **count visible in between**.
- [ ] The bubble is **never permanently a wall of text**. If the pill never shows a number again
      during a fast burst, the gap is not holding — stop and report.
- [ ] Put the phone down at 800+ for a minute without scrolling → **nothing fires**. The cadence
      is measured in scrolls; time alone must never fire.

### Run 4 — the quiet cases
These are the ones a repeating nag gets wrong, and all four are unit-tested — confirm on device.
- [ ] **Walk into Reels at a high count** (leave the app, come back) → **no line on arrival**.
      Repeat five times → still nothing. Only an actual scroll fires.
- [ ] **Force-stop and reopen** at a high count, then enter Reels → no line until you scroll.
- [ ] **Settings → Clear data** at a high count, then scroll → the count starts from 0 and the
      next line is the one at **50**, not an immediate high-tier line.
- [ ] Open **ScrollKiller → Today** after a fire → the header shows **that same line** (Home
      shows the latest fired line, from the same pin — it does not draw its own).
- [ ] Turn the **bubble off** in Settings, scroll to a new tier, open Home → the header still
      shows a **tier-appropriate** line. (Nothing fires with the bubble off; Home falls back to
      its own lazy draw. That is deliberate.)

### Run 5 — no window churn (D30/D38 regression check)
The nudge now runs **dozens of times a day** instead of four, so this check matters more than it
did.
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] Sit at 800+ and scroll fast for ~60s → **one** construct for the bubble window, then
      **resizes only**. A construct/destruct pair per line is the D30 regression — stop.
- [ ] The pill **stays on screen** through a whole burst while parked flush against the **right
      edge** (each line widens it to 240dp and it shrinks back — D38 under repetition).
- [ ] Expand the panel during a burst → it keeps updating to the current line and stays on
      screen.
- [ ] No repeated "displaying over other apps" notification.

### Not in this step
- **YouTube: the first Short after entering isn't counted until you scroll.** Known, and
  **accepted for now** — it is the deferred deep-link entry credit, batched separately. Do not
  file it against this change.
- **YouTube is still `Maturity.BETA`.** Promotion to STABLE is still deferred.
- **The earlier handoffs' runs are still outstanding** — see below. D38's panel containment,
  D39's latency measurement, D40/D44's panel rows, and D41–D45's tier/rotation runs have none of
  them been verified on a device.

---

## STILL OUTSTANDING: escalating, daily-refreshing guilt lines (D41–D45)

The guilt lines stopped being a flat bag of strings. They now **escalate with the day's total**,
**rotate day to day** out of a fixed pack, and **appear on every surface from a single draw**.

**No detection touched.** No threshold, debounce, marker, gating, limit or calibration change.
D11's 200ms IG quiet gap and YT's 500ms identity floor are byte-for-byte what they were.

1. **D41 — intensity tiers.** `GuiltTier` maps today's TOTAL count to intensity 1–4 at
   **50 / 70 / 100 / 150**. **Under 50 the app shows no line at all** — that is the design, not a
   bug: an app that comments on 40 reels has cried wolf by the time the number matters.
   Thresholds live in one `GuiltThresholds` object if you want to re-tune them.
2. **D42 — daily deck.** 60% of each tier's lines are live on any given day, picked by a seeded
   shuffle of `FNV-1a(date | installId)`. Nothing is stored; the rotation is a pure function of
   the date, which is why Run 3 can fake the clock and get a real answer.
3. **D44 — one pinned line.** Home's header, the bubble's threshold nudge and the bubble's
   expanded panel all read the same pinned draw, so they cannot disagree.
4. **D43 — audience packs.** Lines carry `lang`/`region`; Settings has a pack picker with one
   option ("India — Gen Z"). Structure only — the second pack is a JSON drop.
5. **D44c — the panel names the platform.** `Instagram 30`, not a bare camera glyph.

Build is green: `testDebugUnitTest` **147 tests pass** (39 new — `GuiltSelectorTest` 13,
`GuiltDeckTest` 10, `GuiltTierTest` 5, `GuiltPackTest` rewritten to 19), `assembleDebug` and
`compileReleaseKotlin` succeed, `lintDebug` finds nothing new (its one error is still the
pre-existing `local.properties` backslash escaping).

**Getting to a count fast.** Every run below needs a specific daily total. Scrolling to 150 by
hand is not the intent — seed the count instead:
```
adb shell run-as com.scrollkiller ls          # confirm the debug build is debuggable
```
…then either scroll to the boundary you care about, or clear data (Settings → Clear data) and
scroll up through 50 with the bubble visible so the crossing itself is observable. Runs 1 and 2
are the ones that genuinely want real swipes; Run 3 does not need a device at all if you'd
rather trust the unit test that fakes two dates.

### Run 1 — the line appears at 50 and escalates at 70 / 100 / 150
Open ScrollKiller → Today. The line sits under the count, above the "~N mins" estimate.
- [ ] **At 0–49: NO line on Home at all.** Not blank space with a placeholder — nothing rendered.
      Same on the bubble's expanded panel. If a line shows under 50, the tier gate is broken.
- [ ] **Crossing 50** → a line appears, and it is **mild and playful** in tone.
- [ ] **Crossing 70** → the line changes, and it is **cheekier** than the 50 one.
- [ ] **Crossing 100** → changes again, **real guilt**.
- [ ] **Crossing 150** → changes again, **the harshest tier**.
- [ ] Read all four back: the tone genuinely **escalates**. A tier-4 line showing up at 55 is the
      exact failure the tiers exist to prevent — stop and report the line's text.
- [ ] Nothing at any tier is **cruel** rather than funny (no body/family/money/grades jabs). The
      anti-uninstall principle (D9) is the acceptance criterion here, so use your judgement and
      flag anything that lands wrong — it's a one-line JSON edit.
- [ ] Between crossings, within one tier, the line **stays put** — it does not reshuffle on every
      swipe. (It repins on a tier change and on app open, nothing else.)

### Run 2 — the same line everywhere at one moment
This is the invariant most worth checking by eye, because nothing else catches it.
- [ ] Get the count past 50. On the reel surface, **tap the bubble to expand it** and note the
      line at the top of the panel.
- [ ] Without scrolling further, **switch to ScrollKiller → Today**. The header line is the
      **same sentence**.
- [ ] Now **cross a threshold** while the bubble is visible: the compact pill briefly shows the
      line (~4s) then collapses back to the number. **Expand the panel within that window** — the
      panel shows the **same line** the pill just showed.
- [ ] Go back to Home: still the **same line**. Three surfaces, one sentence.
- [ ] **Re-open the app** (leave to launcher, come back) → the line **changes**, at the same tier.
      That is the app-open refresh (`onCreate`, deliberately not `onResume`).
- [ ] Cross a threshold **while below the bubble's view** (bubble off, or off-surface), then open
      Home → the line matches the new tier. No stale tier from the last session.

### Run 3 — two simulated days serve different lines
The rotation is seeded on the **device date**, so faking the date is a real test.
```
adb shell settings put global auto_time 0
adb shell "date 072712002026.00"   # 2026-07-27 12:00 — needs root on most devices
```
If the device won't let you set the date (most retail phones won't without root), **change it
by hand in Settings → System → Date & time** with automatic time off. Restore `auto_time 1`
afterwards.
- [ ] On day A, at a fixed tier, note the lines over ~8 app opens (each open repins).
- [ ] Set the date to **day B**, force-stop ScrollKiller, reopen, and repeat at the same tier.
- [ ] The two sets are **materially different** — at least a few lines in B never appeared in A.
      Identical sets mean the daily seed isn't reaching the deck.
- [ ] Day A again → the lines look like day A's set, **not** day B's. (The deck is a pure
      function of the date, so it must come back.)
- [ ] **If you skip this run**, `GuiltSelectorTest."two simulated days serve materially different
      lines at the same tier"` and the whole of `GuiltDeckTest` cover it off-device. The device
      run adds only that the real clock reaches the seed.

### Run 4 — the bubble panel, and no window churn (D30 regression check)
The panel gained a line row and grew 180 → 240dp wide. That is a resize of the existing surface,
not a new window — prove it.
```
adb logcat | Select-String -Pattern "BLASTBufferQueue|ViewRootImpl"
```
- [ ] Expand/collapse **ten times** → **one** construct for the bubble window, then resizes only.
      A construct/destruct pair per toggle is the D30 regression — stop and report.
- [ ] Cross a threshold so the nudge fires (pill widens to 240dp, then shrinks back) → resizes
      only, and the nudged pill **stays on screen** when parked at the right edge (D38 still holds
      with the wider panel).
- [ ] Panel rows read **`[glyph] Instagram ▓▓▓░░ 30`** — the platform's **name in words**, not
      `IG`, and not a reproduced logo. Bars still **line up** across rows and are still
      proportional, longest first, summing to the headline total.
- [ ] The panel still fits on screen at all four edges with the line row present (it is taller
      than before).
- [ ] No repeated "displaying over other apps" notification through any of the above.

### Run 5 — no hardcoded lines, and the pack picker
- [ ] Settings has a **"Guilt pack"** row reading **"India — Gen Z"**. Tapping it opens a menu
      with that one option. Picking it is a no-op and must not crash.
- [ ] The **only** guilt strings in code are the three in `GuiltPack.FALLBACK`. This is enforced
      by `GuiltPackTest."guilt lines exist only in the pack, never in Kotlin"`, which walks
      `app/src/main/java` — so it is already proven by the green build. Eyeball confirmation:
      ```
      grep -rn "GuiltLine(" app/src/main/java
      ```
      should return **GuiltPack.kt and GuiltPackParser.kt only**.
- [ ] Edit a line's text in `app/src/main/assets/guilt_pack.json`, bump nothing else, reinstall →
      the new text appears. That is the whole point of the pack being data.

### Not in this step
- **YouTube is still `Maturity.BETA`.** The promotion to STABLE and the deep-link entry credit
  are still deferred and are batched for the next session. Unchanged here.
- The `IDENTITY_SCAN_MIN_MS` question from the previous handoff's Run 2 is still open.
- **The previous handoff's runs are still outstanding** — see below; D38's panel containment,
  D39's latency measurement and D40's icons were never verified on a device.

---

## STILL OUTSTANDING: panel containment, count latency, platform icons (D38/D39/D40)

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
**Partly superseded by D44c**: the rows now read `[glyph] Instagram 30` — the glyph is still
generic, but the platform's NAME is back beside it as text (a word, never a logo), and the panel
is 240dp wide rather than 180. Check that shape, not the glyph-only one described below.
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
