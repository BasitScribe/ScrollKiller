package com.scrollkiller.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.scrollkiller.BuildConfig
import com.scrollkiller.ScrollKillerApp
import com.scrollkiller.data.CountRepository

/**
 * On-device detector for short-video reel scrolling.
 *
 * Per the ScrollKiller architecture the device is the source of truth: this
 * service ONLY observes accessibility events, debounces them into discrete "reel
 * advanced" signals, and hands them to the [CountRepository]. It must never become
 * a God-class — no Room, no networking, no per-platform detection logic lives here.
 * Which apps to watch and how to debounce them is data in [PlatformRegistry]; the collapse
 * logic is the pure [SwipeDetector] or [IdentityAdvanceDetector], depending on the platform's
 * [AdvanceStrategy]. This class just wires them to events.
 *
 * Privacy invariant: we read positional/direction metadata, plus — for an
 * [AdvanceStrategy.IDENTITY_CHANGE] platform ONLY — the minimum node text needed to tell one
 * item from the next (a channel handle; see [ReelIdentity]). That value is compared against
 * the previous one and discarded in the same call: never stored, never synced, never logged
 * outside DEBUG. Captions, comments and video content are never touched, and
 * [com.scrollkiller.data.db.ScrollEvent] still records counts and package names only — so the
 * architecture invariant "no content data ever leaves the device" holds. Window-state-changed
 * events (used to gate the overlay bubble) carry only the foreground package/class name.
 *
 * Logcat (filter with `adb logcat -s ScrollKiller`): in a DEBUG build every scroll and
 * every window-state change emits ONE compact `DIAG` line (see [SurfaceDiagnostics]); stamp
 * the transcript between tour steps with:
 *   adb shell am broadcast -p com.scrollkiller -a com.scrollkiller.DIAG_LABEL --es label "IG_REELS"
 */
class ReelScrollAccessibilityService : AccessibilityService() {

    /**
     * DEBUG-only. Lets the surface-tour operator stamp the logcat transcript between steps
     * (and snapshot the current window tree) via an adb broadcast — so surfaces are
     * unambiguously separated. Registered exported (shell UID must reach it) only in debug
     * builds; never registered in release. Kept tiny: it just delegates to
     * [SurfaceDiagnostics], so the event-only service stays free of God-class logic.
     */
    private val labelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The block-failure injector shares this receiver rather than adding a second one:
            // both are DEBUG-only adb-driven test aids with the same lifetime, and one receiver
            // means one thing to register, unregister and reason about (D71).
            if (intent?.action == ACTION_BLOCK_FAIL) {
                BlockFailureInjector.arm(intent.getStringExtra(EXTRA_MODE))
                return
            }
            // Same rationale as the injector above: a third DEBUG-only adb aid with the same
            // lifetime shares the one receiver rather than adding another thing to register,
            // unregister and reason about.
            if (intent?.action == ACTION_BUBBLE_PROBE) {
                BubbleProbe.handle(
                    state = intent.getStringExtra(EXTRA_STATE),
                    reveal = intent.getStringExtra(EXTRA_REVEAL),
                    report = intent.getStringExtra(EXTRA_REPORT),
                )
                return
            }
            val root = rootInActiveWindow
            val spec = PlatformRegistry.forPackage(root?.packageName)
            val label = intent?.getStringExtra(EXTRA_LABEL)
            // A `YTPROBE*` label starts a capture: zero the monotonic counters and stamp the
            // decision table into the transcript, so each capture is self-contained and the
            // outcomes are read against the shipped branches rather than from memory.
            if (label != null && label.startsWith(YTPROBE_LABEL_PREFIX)) YtProbe.reset(label)
            SurfaceDiagnostics.logLabel(label, root, spec)
            @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
            root?.recycle()
        }
    }

    /** Whether [labelReceiver] is currently registered (so teardown is idempotent). */
    private var labelReceiverRegistered = false

    /** One detector per platform so each keeps its own quiet-gap timer. */
    private val detectors = mutableMapOf<Platform, SwipeDetector>()

    /**
     * One identity detector per [AdvanceStrategy.IDENTITY_CHANGE] platform, holding the last
     * counted per-item identity. Separate from [detectors] because the two strategies key off
     * completely different signals and a platform uses exactly one of them (D34).
     */
    private val identityDetectors = mutableMapOf<Platform, IdentityAdvanceDetector>()

    /**
     * Last time an identity evaluation was allowed to run, for the [IDENTITY_SCAN_MIN_MS] rate
     * limit. Global rather than per-platform: only one app is foreground at a time, and this is
     * a cost guard, not a correctness one.
     */
    private var lastIdentityScanMs = 0L

    /** App-wide repository; resolved lazily once the service is attached to context. */
    private val repository: CountRepository by lazy {
        (application as ScrollKillerApp).countRepository
    }

    /**
     * Draws the floating live-counter bubble. Lives here (not in the WindowManager
     * plumbing) so the service stays event-only — it just decides show/hide and
     * delegates. Reads the same repository Flow the Home screen uses.
     */
    private val overlay: OverlayController by lazy {
        OverlayController(this, repository)
    }

    /**
     * "User is doomscrolling right now" — the tracked [Platform] whose doom surface is
     * foreground, or null when off. Drives the overlay (bubble/block) visibility. Set ONLY
     * from PER-EVENT surface state (a scrolled node whose ancestry matches a marker) plus a
     * hysteresis timer, NEVER from a whole-window marker scan: the YouTube home feed embeds a
     * Shorts *shelf*, so a tree-wide scan false-positives on the feed (D28). Routed through
     * [setDoomSurface] so the overlay only reacts on a genuine transition.
     */
    private var doomPlatform: Platform? = null

    /**
     * Keeps [doomPlatform] "on surface" for [SURFACE_HYSTERESIS_MS] after the last
     * matching-surface scroll, so the bubble doesn't flicker off in the gap between swipes
     * (D28). Fires [hideSurfaceRunnable] to drop the OVERLAY only — it deliberately does NOT
     * end the entry-count session (a long watch must not re-trigger the landing count).
     */
    private val surfaceHandler = Handler(Looper.getMainLooper())

    /**
     * Drop the overlay once the surface has gone quiet — UNLESS the block is up.
     *
     * ## Why the exception exists (D49)
     * The hysteresis is armed by a marker-matched scroll. Once the full-screen block covers
     * Instagram the user CANNOT scroll, so nothing re-arms it, and the plain version of this
     * runnable tore the block down three seconds after raising it and handed the reels straight
     * back. Instagram is DELTA_Y_FORWARD, so its content-changed path does not re-arm either.
     *
     * A showing block means the user is, by construction, still on the surface — they are looking
     * at our screen over it — so the right answer is to keep waiting rather than to expire. It
     * RE-POSTS instead of simply returning so the normal 3-second behaviour resumes by itself the
     * moment the block comes down; nothing has to remember to re-arm it.
     *
     * This is NOT a way for the block to outlive leaving Instagram: that path is
     * [onForegroundChanged] → [clearSurface], which is unconditional (invariant 6).
     */
    private val hideSurfaceRunnable = object : Runnable {
        override fun run() {
            if (overlay.isBlocking) {
                surfaceHandler.postDelayed(this, SURFACE_HYSTERESIS_MS)
            } else {
                setDoomSurface(null)
            }
        }
    }

    /**
     * Entry-count session (D29): the platform whose landing reel we've already credited. A
     * "session" starts on the first matching-surface scroll and ends only on leaving the
     * tracked app ([clearSurface]) — NOT when hysteresis drops the overlay — so watching one
     * reel for a while and then swiping never re-counts the landing reel.
     */
    private var enteredPlatform: Platform? = null
    private var lastEntryAtMs = 0L

    /** Attach the bubble window ONCE, up front. It stays attached (toggled VISIBLE/
     *  GONE) for the service's lifetime — no per-app-switch add/remove churn (D17/D29). */
    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay.ensureAttached()
        registerLabelReceiver()
        // DEBUG-only, and a no-op in release: gives the bubble probe a handle on the live
        // controller so an adb command can reach the attached window. Detached in onDestroy /
        // onUnbind beside overlay.destroy(), because a stale reference here would keep a
        // torn-down controller (and its window) alive for the life of the process.
        BubbleProbe.attach(overlay)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event) // emits its own DIAG line
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // One compact DIAG WINDOW line per surface transition (needs the active-window
                // root for the window-level verdict). Compiled out of release via DEBUG.
                if (DEBUG) {
                    val root = rootInActiveWindow
                    SurfaceDiagnostics.logWindow(event, root)
                    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
                    root?.recycle()
                    probeYouTube(event, YtProbe.Kind.WINDOW_STATE)
                }
                onForegroundChanged(event)
            }
            // WINDOW_CONTENT_CHANGED is now a real counting path — but ONLY for a platform whose
            // [AdvanceStrategy] is IDENTITY_CHANGE (YouTube today), because that is where the
            // D31 capture found the per-Short signal (D34). For every other platform this is
            // still a no-op: the event fires constantly, so acting on it per event would be far
            // too costly. [onContentChanged] rate-limits before doing ANY node work.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(event)
        }
    }

    /**
     * DEBUG-only capture hook for WINDOW_STATE, which has no counting path of its own (branch
     * `probe-only-no-counting-path`) — it just gives surface transitions context in the
     * transcript. Bails immediately for anything that isn't YouTube, so no other package pays
     * for it. Window-state changes are rare, so this is probed unconditionally.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    private fun probeYouTube(event: AccessibilityEvent, kind: YtProbe.Kind) {
        val spec = PlatformRegistry.forPackage(event.packageName) ?: return
        if (spec.platform != Platform.YOUTUBE) return

        val source = event.source
        YtProbe.log(
            kind = kind,
            event = event,
            source = source,
            spec = spec,
            marker = SurfaceMatcher.matchesSurface(source, spec),
            branch = YtProbe.Branch.PROBE_ONLY,
            why = "$kind drives no counting — observation only",
        )
        source?.recycle()
    }

    /**
     * Advance detection for [AdvanceStrategy.IDENTITY_CHANGE] platforms (YouTube Shorts today),
     * whose per-item signal is a content change rather than a scroll (D34).
     *
     * WHY THIS EXISTS: Shorts reports `scrollDeltaY=0` on every scroll, so [onScrolled] can
     * never count there. What does change once per advance is the Short's identity — the channel
     * handle — carried on `TYPE_WINDOW_CONTENT_CHANGED`. But that event fires ~40× per Short
     * (subtitles, like counts, "Auto-dubbed"), so we count the identity CHANGING, never the
     * event: see [ReelIdentity] for extraction and [IdentityAdvanceDetector] for the decision.
     *
     * THREE GUARDS, in cost order — each one is why the next is affordable:
     *  1. strategy + package: a platform that doesn't use identity advance returns on a map
     *     lookup, so Instagram pays essentially nothing for this branch existing.
     *  2. [IDENTITY_SCAN_MIN_MS] rate limit, checked BEFORE `event.source` is even touched.
     *     This is the load-bearing one: it caps the ancestor walk + bounded subtree scan at a
     *     few per second no matter how hard YouTube fires. One Short lasts seconds, so nothing
     *     is missed.
     *  3. the surface marker, so the YouTube home feed's Shorts shelf can't count (D28).
     *
     * A marker-matched content change also arms the overlay hysteresis, which is why the bubble
     * now stays up while watching a single Short rather than needing a scroll to survive.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    private fun onContentChanged(event: AccessibilityEvent) {
        val spec = PlatformRegistry.forPackage(event.packageName) ?: return
        if (!spec.usesIdentityAdvance) return

        val now = System.currentTimeMillis()
        if (now - lastIdentityScanMs < IDENTITY_SCAN_MIN_MS) return
        lastIdentityScanMs = now

        val source = event.source ?: return
        try {
            if (!SurfaceMatcher.matchesSurface(source, spec)) return

            // On the surface: keep the bubble alive. creditEntry=false because the identity path
            // below already counts the Short the user LANDED on (its identity differs from
            // "nothing seen yet"), so D29's separate entry credit would double it.
            onSurfaceEvent(spec.platform, now, creditEntry = false)

            val identity = ReelIdentity.identityOf(source, spec)
            val detector = identityDetectors.getOrPut(spec.platform) {
                IdentityAdvanceDetector(spec.minAdvanceIntervalMs)
            }
            val advance = detector.onIdentity(identity, now)
            if (advance == IdentityAdvanceDetector.Advance.COUNTED) {
                // The REAL package, not the spec's canonical one: YouTube has variants (D52).
                repository.record(spec, now, sourcePackage = event.packageName?.toString() ?: spec.packageName)
            }

            if (DEBUG && spec.platform == Platform.YOUTUBE) {
                YtProbe.log(
                    kind = YtProbe.Kind.CONTENT_CHANGED,
                    event = event,
                    source = source,
                    spec = spec,
                    marker = true,   // we returned above unless the marker matched
                    branch = branchFor(advance),
                    why = reasonFor(advance),
                    // Passed through as null on UNREADABLE ON PURPOSE: that makes YtProbe fall
                    // back to its bounded DISCOVERY scan and print the `id:text` pairs actually
                    // in the tree — which is exactly the evidence needed to explain why nothing
                    // matched. Printing a dash there would hide the only useful thing.
                    identity = identity,
                )
            }
        } finally {
            source.recycle()
        }
    }

    /**
     * Map the detector's real outcome onto a transcript branch. Threaded from the ACTUAL
     * decision rather than re-derived, so the log can't drift from the logic (the property the
     * whole probe rests on).
     */
    private fun branchFor(advance: IdentityAdvanceDetector.Advance): YtProbe.Branch = when (advance) {
        IdentityAdvanceDetector.Advance.COUNTED -> YtProbe.Branch.IDENTITY_COUNTED
        IdentityAdvanceDetector.Advance.UNREADABLE -> YtProbe.Branch.IDENTITY_UNREADABLE
        IdentityAdvanceDetector.Advance.UNCHANGED -> YtProbe.Branch.IDENTITY_UNCHANGED
        IdentityAdvanceDetector.Advance.FLOORED -> YtProbe.Branch.IDENTITY_FLOORED
    }

    private fun reasonFor(advance: IdentityAdvanceDetector.Advance): String = when (advance) {
        IdentityAdvanceDetector.Advance.COUNTED -> "identity differs from the last counted one"
        IdentityAdvanceDetector.Advance.UNREADABLE -> "no @handle and no identityTitleHints node — ignored"
        IdentityAdvanceDetector.Advance.UNCHANGED -> "same identity as last counted (item re-rendering)"
        IdentityAdvanceDetector.Advance.FLOORED -> "identity changed inside minAdvanceIntervalMs — retried next read"
    }

    /**
     * Advance detection, gated on package, a container-class match ([PlatformSpec.matchesContainer],
     * simple-name so legacy support-lib widgets hit — D27) AND, per the platform's [GatingMode],
     * the doom-surface marker on the scrolled node's ancestry (the authoritative, cheap signal
     * we already hold).
     *
     * Gating (D24): PASSTHROUGH/SHADOW count regardless of the marker match (SHADOW logs what
     * enforcement WOULD do so a candidate can be confirmed on a device); ENFORCED counts ONLY
     * on a match (a wrong guess undercounts, never counts a home feed).
     *
     * The per-event marker match is ALSO the overlay's surface signal ([onSurfaceEvent]) — the
     * same signal counting uses — so the bubble/block can't disagree with counting and never
     * relies on a false-positive-prone whole-window scan (D28).
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    private fun onScrolled(event: AccessibilityEvent) {
        // Which tracked platform is this from? Bail on anything unregistered.
        val spec = PlatformRegistry.forPackage(event.packageName) ?: return

        val direction = scrollDirection(event)
        val className = event.className?.toString().orEmpty()
        val source = event.source

        // Is the scrolled view a tracked container, and is it on the doom surface (marker
        // match on the scrolled node's ancestry)?
        val isContainer = spec.matchesContainer(className)
        val markerMatched = isContainer && SurfaceMatcher.matchesSurface(source, spec)

        var counted = false
        // Which branch this event took, for the YT capture probe. Mirrors `reason` but as a
        // stable enum so the transcript reports the REAL decision, never a re-derived guess.
        var branch: YtProbe.Branch
        var entryCredited = false
        val reason: String
        if (!isContainer) {
            branch = YtProbe.Branch.REJECTED_CONTAINER
            reason = "$className is not a tracked container"
        } else {
            val now = System.currentTimeMillis()

            // A matching-surface scroll drives the overlay + entry-count (per-event, no window
            // scan). Non-matching container scrolls (feed/profile/DMs) do NOT — so they can't
            // keep the bubble alive or credit an entry.
            //
            // creditEntry is OFF for an IDENTITY_CHANGE platform: its identity path already
            // counts the item the user landed on, so crediting here too would double it — and
            // on YouTube these scrolls are the DEAD deltaY=0 events, which under D29 were the
            // only thing that ever incremented the count (D34).
            if (markerMatched) {
                entryCredited = onSurfaceEvent(spec.platform, now, creditEntry = !spec.usesIdentityAdvance)
            }

            val countable = when (spec.gating) {
                GatingMode.PASSTHROUGH, GatingMode.SHADOW -> true
                GatingMode.ENFORCED -> markerMatched
            }
            if (!countable) {
                branch = YtProbe.Branch.REJECTED_SURFACE
                reason = "ENFORCED off-surface (marker unmatched)"
            } else {
                val detector = detectors.getOrPut(spec.platform) { SwipeDetector(spec.minAdvanceIntervalMs) }
                // Debounce the fling burst into a single forward advance.
                if (detector.onScroll(direction, now)) {
                    repository.record(
                        spec,
                        now,
                        sourcePackage = event.packageName?.toString() ?: spec.packageName,
                    )
                    counted = true
                    branch = YtProbe.Branch.COUNTED
                    reason = if (spec.gating == GatingMode.SHADOW && !markerMatched) {
                        "counted (SHADOW: marker UNMATCHED — would be IGNORED once ENFORCED)"
                    } else {
                        "counted (advance)"
                    }
                } else {
                    branch = YtProbe.Branch.DEBOUNCED_QUIET_GAP
                    reason = "debounced ($direction inside quiet-gap)"
                }
            }
        }

        // The single compact transcript line for this scroll (DEBUG only). It reads the
        // scrolled node's ancestry itself for the id-chain + verdict, so emit BEFORE recycling.
        if (DEBUG) {
            SurfaceDiagnostics.logScroll(spec, event, source, direction, counted, reason)
            // YT capture probe (supersedes the old YTFIELDS dump — it prints those fields plus the
            // event type, eventTime and the branch above). EVERY YouTube scroll is probed, not just
            // marker-matched ones: a marker-matched event can never BE rejected-container or
            // rejected-surface, so filtering here would make two of the four branches unobservable.
            // The line carries marker=MATCH/NO_MATCH — grep `marker=MATCH` for the matched subset.
            if (spec.platform == Platform.YOUTUBE) {
                YtProbe.log(
                    kind = YtProbe.Kind.SCROLLED,
                    event = event,
                    source = source,
                    spec = spec,
                    marker = markerMatched,
                    branch = branch,
                    why = reason,
                    entryCredited = entryCredited,
                )
            }
        }
        source?.recycle()
    }

    /**
     * A matching-surface scroll just happened on [platform]. Two effects:
     *  1. OVERLAY: mark on-surface (shows the bubble / block) and (re)arm the hysteresis timer
     *     so the overlay stays up across the gap between swipes (D28).
     *  2. ENTRY-COUNT (D29): credit the reel the user LANDED on, which fires no scroll event of
     *     its own. Counted once per surface session — the session ends only on leaving the app
     *     ([clearSurface]), and a [ENTRY_GUARD_MS] guard means a quick flicker out-and-back
     *     doesn't double-count. Net effect: total = (landing reel) + (swipes), off-by-one
     *     UP from the old behaviour, well within the ±2/50 bar.
     *
     * @param creditEntry whether effect 2 applies. FALSE for an [AdvanceStrategy.IDENTITY_CHANGE]
     *   platform, whose identity path inherently counts the landed-on item (its identity differs
     *   from "nothing seen yet") — taking both would double it. Effect 1 always applies. See D34.
     * @return true if this call credited the landing reel. Returned (not just done silently) so
     *   the YT capture probe can attribute the extra Room increment — otherwise `ytCounted` would
     *   move by one with no visible branch explaining it.
     */
    private fun onSurfaceEvent(platform: Platform, now: Long, creditEntry: Boolean): Boolean {
        setDoomSurface(platform)
        surfaceHandler.removeCallbacks(hideSurfaceRunnable)
        surfaceHandler.postDelayed(hideSurfaceRunnable, SURFACE_HYSTERESIS_MS)

        if (creditEntry && enteredPlatform != platform && now - lastEntryAtMs > ENTRY_GUARD_MS) {
            enteredPlatform = platform
            lastEntryAtMs = now
            repository.record(PlatformRegistry.specFor(platform), now)
            return true
        }
        return false
    }

    /**
     * Foreground/window changed. This is now ONLY a "left the tracked app" signal — the
     * window-state-changed for the destination app is delivered here because the service is
     * scoped to all packages. We deliberately do NOT scan the window tree to decide "on
     * surface": the YouTube home feed embeds a Shorts shelf, so a tree-wide marker scan
     * false-positives on the feed (D28). Entering the reel surface is detected per-event from
     * scroll ancestry ([onSurfaceEvent]) instead; leaving it WITHIN the app is handled by the
     * hysteresis timer expiring (no more matching scrolls).
     */
    private fun onForegroundChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg.isNullOrEmpty()) return
        if (pkg == packageName) return          // our overlay/UI is not a foreground change

        // Left every tracked app → drop the overlay AND end the entry-count session now.
        // A tracked destination is left to the per-event surface signal (a stale window scan
        // here would false-positive on a feed), so we do nothing for it.
        if (PlatformRegistry.forPackage(pkg) == null) clearSurface()
    }

    /** Update the doom-surface state and drive the overlay, but only on a real change. */
    private fun setDoomSurface(platform: Platform?) {
        if (platform == doomPlatform) return
        doomPlatform = platform
        if (platform != null) overlay.onSurface(platform) else overlay.offSurface()
    }

    /**
     * Left the tracked app entirely: cancel hysteresis, hide the overlay, and END the
     * entry-count session so the next genuine entry credits its landing reel again. Kept
     * separate from the hysteresis [hideSurfaceRunnable], which drops only the overlay.
     *
     * The identity detectors are reset here for the same reason and NOT on hysteresis expiry:
     * an IDENTITY_CHANGE platform expresses "credit the landing item" as "the first identity of
     * a session always differs from nothing", so forgetting it mid-app would re-count the Short
     * still on screen. Leaving the app is the one moment where re-counting is correct.
     */
    private fun clearSurface() {
        surfaceHandler.removeCallbacks(hideSurfaceRunnable)
        enteredPlatform = null
        identityDetectors.values.forEach { it.reset() }
        setDoomSurface(null)
    }

    /**
     * Vertical scroll direction for a scroll event.
     *
     * `getScrollDeltaY()` is the reliable signal but only exists on API 28+. Since
     * min SDK is 26 we guard it and fall back to comparing scrollY against the max
     * scroll extent on 26/27 (best-effort — see DECISIONS).
     *   delta > 0 -> content moved up, user swiped to the NEXT reel (DOWN)
     *   delta < 0 -> user swiped back to the PREVIOUS reel (UP)
     */
    private fun scrollDirection(event: AccessibilityEvent): ScrollDirection {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val deltaY = event.scrollDeltaY
            // -1 is the framework's "no delta reported" sentinel; ignore it.
            if (deltaY != -1) {
                return when {
                    deltaY > 0 -> ScrollDirection.DOWN
                    deltaY < 0 -> ScrollDirection.UP
                    else -> ScrollDirection.SAME
                }
            }
        }
        return when {
            event.scrollY <= 0 -> ScrollDirection.UP
            event.maxScrollY > 0 && event.scrollY >= event.maxScrollY -> ScrollDirection.DOWN
            else -> ScrollDirection.SAME
        }
    }

    /**
     * Required override. Fired when the system interrupts our feedback (we provide
     * none), so there is nothing to tear down here.
     */
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    /** Tear the bubble down if the service is unbound/disabled. */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        surfaceHandler.removeCallbacks(hideSurfaceRunnable)
        overlay.destroy()
        BubbleProbe.attach(null)     // never hold a torn-down controller (and its window) alive
        unregisterLabelReceiver()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        surfaceHandler.removeCallbacks(hideSurfaceRunnable)
        overlay.destroy()
        BubbleProbe.attach(null)
        unregisterLabelReceiver()
        super.onDestroy()
    }

    /**
     * Register the DEBUG-only tour-label receiver. Exported (RECEIVER_EXPORTED) because the
     * stamp arrives from the adb shell UID. No-op in release builds — never registered, so no
     * externally-reachable receiver ships.
     */
    private fun registerLabelReceiver() {
        if (!DEBUG || labelReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            labelReceiver,
            IntentFilter(ACTION_DIAG_LABEL).apply {
                addAction(ACTION_BLOCK_FAIL)
                addAction(ACTION_BUBBLE_PROBE)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        labelReceiverRegistered = true
    }

    private fun unregisterLabelReceiver() {
        if (!labelReceiverRegistered) return
        unregisterReceiver(labelReceiver)
        labelReceiverRegistered = false
    }

    private companion object {
        /** Single tag so you can filter everything with: `adb logcat -s ScrollKiller`. */
        const val TAG = "ScrollKiller"

        /** Broadcast action for the surface-tour label stamp (DEBUG only). */
        const val ACTION_DIAG_LABEL = "com.scrollkiller.DIAG_LABEL"

        /** String extra on [ACTION_DIAG_LABEL] carrying the human label for the log banner. */
        const val EXTRA_LABEL = "label"

        /**
         * Broadcast action that arms the DEBUG block-failure injector (D71), so the trap the
         * always-exitable invariant exists to prevent can be reproduced deliberately instead of
         * waited for. See [BlockFailureInjector] for the modes and the adb one-liners.
         */
        const val ACTION_BLOCK_FAIL = "com.scrollkiller.BLOCK_FAIL"

        /**
         * Broadcast action for the DEBUG bubble probe (D86) — force the mascot state, fire a
         * reveal, or dump what the bubble believes about itself. See [BubbleProbe] for the
         * one-liners and, more importantly, for why the alternative is scrolling to 150 by hand.
         */
        const val ACTION_BUBBLE_PROBE = "com.scrollkiller.BUBBLE"

        /** String extra on [ACTION_BUBBLE_PROBE]: `healthy`, `cracking`, `fried`, or `off`. */
        const val EXTRA_STATE = "state"

        /** String extra on [ACTION_BUBBLE_PROBE]: `fade`, `rise`, `pop`, `sweep`, or `auto`. */
        const val EXTRA_REVEAL = "reveal"

        /** Any value on [ACTION_BUBBLE_PROBE] triggers the state dump. */
        const val EXTRA_REPORT = "report"

        /** String extra on [ACTION_BLOCK_FAIL]: `no_attach`, `throw`, or `off`. */
        const val EXTRA_MODE = "mode"

        /** Label prefix that starts a [YtProbe] capture (zeroes counters, prints the table). */
        const val YTPROBE_LABEL_PREFIX = "YTPROBE"

        /**
         * How long the overlay stays "on surface" after the last matching-surface scroll, so
         * the bubble doesn't flicker off in the gap between swipes (D28). Short by design —
         * long enough to bridge a normal inter-swipe pause, short enough that leaving the reel
         * surface hides the bubble promptly.
         */
        const val SURFACE_HYSTERESIS_MS = 3_000L

        /**
         * Minimum gap before a fresh surface entry re-credits its landing reel (D29). Guards
         * against a quick flicker out-and-back (e.g. an IME/dialog from another package)
         * double-counting the entry.
         */
        const val ENTRY_GUARD_MS = 3_000L

        /**
         * Rate limit on [onContentChanged]'s identity evaluation — the guard that makes counting
         * off `TYPE_WINDOW_CONTENT_CHANGED` affordable in a release build (D34).
         *
         * That event fires many times a second during Shorts playback and each evaluation costs
         * an ancestor walk plus a bounded subtree scan. Checked BEFORE `event.source` is touched,
         * so a flood costs one subtraction per event. 250ms (~4Hz) is an order of magnitude
         * denser than one Short per ~2s, so no advance can slip between samples; it is NOT a
         * correctness mechanism (that is [IdentityAdvanceDetector]'s identity comparison), which
         * is why it can be this coarse.
         */
        const val IDENTITY_SCAN_MIN_MS = 250L

        /**
         * Master toggle for the verbose surface-tour logging (the compact `DIAG` lines +
         * per-label tree snapshot in [SurfaceDiagnostics], and the label receiver). Tied to
         * [BuildConfig.DEBUG] so release builds NEVER log the view hierarchy (privacy + Play).
         * To force it during a debug session, temporarily set this to `true` — but do not ship
         * it that way.
         */
        val DEBUG = BuildConfig.DEBUG
    }
}
