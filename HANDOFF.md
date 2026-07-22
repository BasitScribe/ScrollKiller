# HANDOFF — manual on-device test checklist

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
- [ ] Tap the bubble (no drag) → ScrollKiller **Home** opens.
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
