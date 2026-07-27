package com.scrollkiller.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.scrollkiller.brain.BrainState
import com.scrollkiller.challenge.ChallengeController
import com.scrollkiller.challenge.ChallengeRegistry
import com.scrollkiller.data.CountLatency
import com.scrollkiller.data.CountRepository
import com.scrollkiller.data.SettingsPrefs
import com.scrollkiller.data.TodaySummary
import com.scrollkiller.guilt.GuiltCadence
import com.scrollkiller.guilt.GuiltLines
import com.scrollkiller.permission.BlockUnavailableNotifier
import com.scrollkiller.permission.PermissionHealthReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.time.LocalDate
import kotlin.math.abs

/**
 * Coordinates the on-surface overlay for one doom surface at a time: the passive
 * live-counter bubble (mascot + count) and, once the platform's daily limit is crossed,
 * the full-screen [BlockScreenController]. Both are drawn via SYSTEM_ALERT_WINDOW.
 *
 * Kept OUT of [ReelScrollAccessibilityService] on purpose (CLAUDE.md: the service
 * only emits events, no God-classes). The service decides which doom surface we're on
 * ([onSurface]/[offSurface]) and delegates all WindowManager work here.
 *
 * ## What the bubble shows (D35, D37)
 * COMPACT: the mascot and the GRAND TOTAL across every tracked platform, and nothing else —
 * not the count for the app you happen to be in (30 reels then 10 shorts is one 40-item
 * doomscrolling day, and showing "10" on YouTube made the bubble read as a per-app
 * scoreboard), and no per-app text either.
 *
 * EXPANDED: tapping the bubble reveals a breakdown panel in place — today's total + estimated
 * time, then one proportional bar per platform with a count today. Tapping again collapses it.
 * A tap does NOT open the app any more (it used to launch Home); the number you wanted to see
 * is now reachable without leaving the video, which is the whole point of an overlay.
 *
 * Both come from one [CountRepository.observeTodaySummary] Flow, which is also what Home's
 * total reads — so the surfaces can no longer disagree, and the bars cannot disagree with the
 * total above them ([BubbleBreakdown] derives the shares from that same total). The brain state
 * follows the TOTAL for the same reason.
 *
 * The block, in contrast, still keys off the CURRENT platform's count against that platform's
 * limit ([TodaySummary.countFor]) — limits are per-platform (the user's, from
 * [SettingsPrefs.dailyLimit], defaulting to [PlatformSpec.dailyLimit]), and whether they should
 * become one shared budget is a product decision, not a rendering one.
 *
 * ## The block is live on Instagram (D49)
 * It was dormant from D19 until now because [PlatformSpec.blocksAtLimit] was false everywhere.
 * Instagram alone is true today. Note that the block does NOT respect the bubble's Settings
 * toggle: switching the passive counter off is a statement about a pill over your video, not a
 * withdrawal of the daily limit you set. The overlay PERMISSION still gates both, so a user who
 * refuses it gets neither — detection and the in-app counter are unaffected either way.
 *
 * Design constraints (this lives inside someone's doomscroll session — it can't jank
 * Instagram or eat battery):
 *  - One source of truth: reads the SAME Room rows Home uses — a projection, never a second
 *    counter.
 *  - Cheap: views update on count *change* only ([distinctUntilChanged]); no animation
 *    loop, no polling.
 *  - No work off-surface: the Flow is collected only between [onSurface] and
 *    [offSurface].
 *  - No bubble churn: the bubble is added ONCE ([ensureAttached]) and never add/removed per
 *    app-switch or idle-timeout (D17/D29). Its ROOT is ALSO never toggled [View.GONE]: a GONE
 *    root view makes WindowManagerService hide the window and free its surface
 *    (BLASTBufferQueue), so a GONE/VISIBLE cycle on the hysteresis timer churned a
 *    construct/destruct surface every ~3–5s (D30). Instead the root stays [View.VISIBLE] for
 *    the service's lifetime and "hidden" means alpha 0 +
 *    [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] ([setBubbleShown]) — both keep the window
 *    visible, so the surface persists. Expanding the panel is a CHILD's visibility inside that
 *    same window (see [BubbleView]), which resizes the surface rather than freeing it. The block
 *    is add/removed on demand (rare, and a focusable window must not linger — see
 *    [BlockScreenController]).
 *  - Surface-driven visibility (D29, supersedes D21): the bubble is VISIBLE the whole time
 *    we're on the reel surface (the service's hysteresis keeps us "on surface" across the
 *    gap between swipes) and GONE once [offSurface] fires — no per-scroll show + idle-hide.
 *  - Optional: if the overlay permission isn't granted, everything is a silent no-op so
 *    detection is completely unaffected.
 *
 * Threading: all methods are called from the AccessibilityService's main thread, and
 * the Flow is collected on [Dispatchers.Main], so every WindowManager/TextView touch
 * stays on the UI thread.
 */
class OverlayController(
    private val context: Context,
    private val repository: CountRepository,
) {

    /** The full-screen block; shown only when the limit is crossed on the surface. */
    private val block: BlockScreenController by lazy {
        BlockScreenController(
            context,
            onExit = ::onExit,
            onSnooze = ::onSnooze,
            onStartChallenge = ::onStartChallenge,
            onCancelChallenge = ::onCancelChallenge,
        )
    }

    /** The physical unlock (D50). Owns the sensor; knows nothing about what completing is worth. */
    private val challenge: ChallengeController by lazy { ChallengeController(context) }

    /** Tells the user, out of app, when the block cannot fire (D51). The nudge, not the net. */
    private val notifier: BlockUnavailableNotifier by lazy { BlockUnavailableNotifier(context) }

    /**
     * Is the full-screen block up right now?
     *
     * Read by [ReelScrollAccessibilityService]'s surface hysteresis, which must NOT drop the
     * surface while this is true: the block covers Instagram, so no further scroll can arrive to
     * re-arm the timer, and the 3-second hysteresis would otherwise tear the block down and hand
     * the reels back three seconds after raising it (D49).
     */
    val isBlocking: Boolean get() = block.isShowing

    /** The doom surface we're currently on (null = off-surface). */
    private var currentPlatform: Platform? = null

    /** Last summary seen, so [onSnooze] and [collapseNudge] can re-render without a new emission. */
    private var lastSummary = TodaySummary.EMPTY

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * The bubble view. Attached to the window ONCE per service lifetime and then kept
     * there (toggled VISIBLE/GONE) until [destroy]; null only before the first attach.
     * We do NOT add/remove per app-switch — that churned the overlay window (VRI
     * construct/destroy cycles) and re-fired the system "displaying over other apps"
     * notification on every switch. See D17.
     */
    private var bubble: BubbleView? = null

    /** Live layout params so drag can mutate x/y and re-apply them. */
    private var layoutParams: WindowManager.LayoutParams? = null

    /**
     * Where the user has dragged the COMPACT pill, in absolute screen pixels (the window's
     * gravity is TOP|START, so these are `params.x/y` directly). Held separately from
     * `params.x/y` because those are whatever the CURRENT shape needs — while the panel is
     * expanded they hold the centred position — and collapsing has to put the pill back where
     * the user left it, not where the panel happened to be centred (D38).
     */
    private var compactX = 0
    private var compactY = 0

    /** Whether the bubble is currently shown (alpha 1 + touchable). Tracked so [setBubbleShown]
     *  is idempotent and doesn't relayout the window on a no-op toggle. */
    private var bubbleShown = false

    /** Scope for the count-Flow collection; alive only while on the doom surface. */
    private var collectScope: CoroutineScope? = null

    /** True while the bubble is showing a guilt line instead of the count. */
    private var nudging = false

    /** Times the nudge back down to the count. One pending message at most. */
    private val nudgeHandler = Handler(Looper.getMainLooper())

    /**
     * Add the bubble to the window exactly once, starting hidden (alpha 0 +
     * FLAG_NOT_TOUCHABLE — the root stays [View.VISIBLE] so the surface is created once and
     * kept; see [setBubbleShown]). Idempotent, and a no-op if the overlay permission isn't
     * granted — in that case we retry lazily from [onSurface] (the user may grant it later).
     * Detection never depends on this succeeding.
     */
    fun ensureAttached() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.d(TAG, "Overlay permission not granted; bubble not attached yet")
            return
        }

        val view = createBubbleView().apply { alpha = 0f }
        val params = createLayoutParams()   // starts with FLAG_NOT_TOUCHABLE (hidden)
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // e.g. permission revoked between the check and the add; fail soft.
            Log.w(TAG, "addView failed; bubble not attached", e)
            return
        }
        bubble = view
        layoutParams = params
        compactX = params.x
        compactY = params.y
        bubbleShown = false   // matches the alpha-0 + NOT_TOUCHABLE hidden state we just attached
        // Pull the default spawn point inside the insets before it is ever shown — on a device
        // with a tall status bar or a cutout the hard-coded 16/120dp could otherwise start the
        // pill underneath system chrome. Posted because the view has no measured size until it
        // has been through one layout pass.
        view.post { applyPlacement(remeasure = true) }
    }

    /**
     * The user is now on [platform]'s doom surface. Start observing that platform's
     * count and render either the bubble or the block per [BlockPolicy]. Called once
     * per surface entry (the service transition-gates it).
     */
    fun onSurface(platform: Platform) {
        currentPlatform = platform
        ensureAttached()               // lazy first-attach if permission came later
        warnEarlyIfBlockIsDead(platform)
        startCollecting(platform)
    }

    /**
     * The user just walked into a reel surface and we already know the block cannot fire — say so
     * NOW rather than at the limit (D51).
     *
     * On the incident this exists for, the user reached 108 reels before noticing anything was
     * wrong. This fires on the first scroll instead, which is the whole difference between an app
     * that failed and an app that failed loudly.
     *
     * ONCE PER DAY, keyed on the device-local date and persisted, because [onSurface] runs on
     * every surface entry and a warning on each one would be a notification every time the user
     * opens Instagram — which is how people learn to swipe our warnings away. The day key is
     * persisted rather than held here because the system restarts this service far more often
     * than a day rolls over.
     *
     * Only fires for a platform that COULD block: a BETA platform was never going to raise a block
     * anyway, so a missing overlay permission changes nothing about it and warning would be a lie.
     */
    private fun warnEarlyIfBlockIsDead(platform: Platform) {
        if (!PlatformRegistry.specFor(platform).blocksAtLimit) return
        if (PermissionHealthReader.of(context).canBlock) return

        val today = LocalDate.now().toString()
        if (SettingsPrefs.blockWarnedOn(context) == today) return
        SettingsPrefs.setBlockWarnedOn(context, today)
        Log.w(TAG, "entered $platform with the block dead (no overlay permission); warning once today")
        notifier.warnEarly()
    }

    /**
     * The user left the doom surface: stop work and hide both overlays.
     *
     * Unconditional, and it must stay that way — this is the path that runs when the user leaves
     * the tracked app, and invariant 6 says the block tears down when they do. The service is
     * responsible for not calling it while a block is up on a surface the user is still on (see
     * [isBlocking]); it is NOT this method's job to second-guess, because a guard here would be a
     * way for the block to survive leaving Instagram.
     */
    fun offSurface() {
        collectScope?.cancel()
        collectScope = null
        currentPlatform = null
        cancelNudge()
        // NOTE: the firing cadence is deliberately NOT reset here. It lives in GuiltFiring, is
        // keyed on the day's count, and persists across surface exits — which is what makes
        // walking back into Reels at an unchanged count silent (D46). Resetting per entry would
        // either re-baseline (and lose a pending fire) or fire on every arrival.
        bubble?.let {
            // Collapse the panel on the way out, so walking back into Reels shows the compact
            // pill rather than a panel the user left open twenty minutes ago.
            setExpanded(it, false)
            setBubbleShown(it, false)
        }
        hideBlock()
    }

    /** Full teardown for service destroy/unbind: stop work and remove the windows. */
    fun destroy() {
        collectScope?.cancel()
        collectScope = null
        nudgeHandler.removeCallbacksAndMessages(null)
        nudging = false
        GuiltLines.endBlockEpisode()
        challenge.stop()          // the service is going away; the sensor must not outlive it
        block.destroy()
        bubble?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
        bubble = null
        layoutParams = null
        bubbleShown = false
    }

    /** Collect today's totals and (re)render on change only. */
    private fun startCollecting(platform: Platform) {
        collectScope?.cancel()   // defensive: never leak a prior platform's collector on a direct switch
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        collectScope = scope
        repository.observeTodaySummary()
            .distinctUntilChanged()
            .onEach { summary -> render(platform, summary) }
            .launchIn(scope)
    }

    /**
     * Decide and show bubble vs block for the current count. Visibility is SURFACE-DRIVEN
     * (D29, supersedes D21's per-scroll idle-dismiss): the bubble is VISIBLE the whole time
     * we're on the reel surface — the service's hysteresis keeps us "on surface" across the
     * gap between swipes, and [offSurface] hides it on leaving. The block only fires when
     * [PlatformSpec.blocksAtLimit] is true — which needs BOTH the block switched on for the
     * platform and a count we trust ([Maturity.STABLE]). Instagram is the only platform where
     * both hold today (D49); every other one is BETA and structurally cannot block.
     *
     * The LIMIT is the user's ([SettingsPrefs.dailyLimit]), not the spec's — the spec's value is
     * only the default that pref falls back to. Read per render rather than cached because the
     * user can move the slider while the overlay is alive, and a limit that takes effect "next
     * time you open Instagram" is a setting that looks broken.
     */
    private fun render(platform: Platform, summary: TodaySummary) {
        CountLatency.emitted()
        lastSummary = summary
        val spec = PlatformRegistry.specFor(platform)
        // The BLOCK still asks a per-platform question (this platform's count vs ITS limit),
        // even though the bubble displays the total — see the class doc.
        val forLimit = summary.countFor(platform)
        val limit = SettingsPrefs.dailyLimit(context, platform)
        val overlay = BlockPolicy.overlayFor(
            count = forLimit,
            limit = limit,
            gatingActive = spec.blocksAtLimit,
            graceUntilMs = SettingsPrefs.graceUntilMs(context, platform),
            nowMs = System.currentTimeMillis(),
        )
        when (overlay) {
            SurfaceOverlay.BLOCK -> {
                cancelNudge()
                bubble?.let {
                    setExpanded(it, false)
                    setBubbleShown(it, false)
                }
                // THE D51 FIX. This used to call block.show() and let it no-op when the overlay
                // permission was missing — a Log.d and nothing else. That is right for the bubble
                // (cosmetic, optional since D16) and was catastrophically wrong here: the app
                // counted to 108, decided to block a hundred times, was refused by AppOps every
                // time, and told nobody. The block is the product; being unable to draw it is an
                // INCIDENT, not a shrug.
                if (!PermissionHealthReader.of(context).canBlock) {
                    Log.w(
                        TAG,
                        "BLOCK PREVENTED at $forLimit/$limit on $platform: overlay permission " +
                            "missing (AppOps will refuse SYSTEM_ALERT_WINDOW). Warning the user.",
                    )
                    notifier.warnBlockPrevented()
                    return
                }
                // Permission is fine — clear any warning left from when it wasn't, so a fixed
                // problem does not keep announcing itself.
                notifier.cancel()
                // blockLine draws ONCE per episode and re-renders its tokens on every emission,
                // so the sentence holds while `{count}` stays live — and an unbroken block does
                // not eat the tier's 7-day pool one line per emission (D49).
                block.show(platform, GuiltLines.blockLine(context, summary.total))
            }
            SurfaceOverlay.BUBBLE -> {
                hideBlock()
                val view = bubble ?: return          // no overlay permission — nothing to show
                if (!SettingsPrefs.isBubbleEnabled(context)) {
                    cancelNudge()
                    setExpanded(view, false)
                    setBubbleShown(view, false)       // user turned the bubble off
                    return
                }
                // Cadence FIRST, then render. GuiltLines.fire() repins on a fire, so asking it
                // before renderCount is what makes the panel and the compact line show the same
                // sentence on the same frame — the reverse order renders the previous line and
                // then immediately replaces it.
                val fired = GuiltLines.fire(context, summary.total)
                renderCount(view, summary)
                setBubbleShown(view, true)            // visible while on the reel surface
                if (fired != null) showNudge(view, summary.total, fired)
                // One post per emission, doing two things that both need the NEXT layout pass:
                //  - re-place, because the count gaining a digit (9→10, 99→100) widens the pill,
                //    and a pill parked flush right would otherwise grow off the edge (D38). The
                //    equality guard inside makes the overwhelmingly common no-move case free.
                //  - close the latency trace, which is what makes the log's `layout=` figure the
                //    time from "the collector got the number" to "the view carries it" (D39).
                view.post {
                    applyPlacement(remeasure = true)
                    CountLatency.rendered()
                }
            }
        }
    }

    /**
     * Put a just-fired [line] on the bubble, then collapse back to the count after
     * [GuiltCadence.DISPLAY_MS].
     *
     * No decision here any more — WHEN to fire is [GuiltFiring]'s, and this is called only when
     * it has already said yes (D46). Lines now REPEAT on a cadence that tightens with the count
     * (every 10 scrolls at 100, every scroll at 800+) rather than firing once per tier crossing,
     * so this runs many times a day at a high count and must stay cheap: it is text on an
     * already-attached view plus one re-placement, exactly like a digit-count change.
     *
     * The bubble does not choose its own line — [line] came from the shared pin, which is what
     * Home's header and the panel below are also reading at this instant.
     */
    private fun showNudge(view: BubbleView, count: Int, line: String) {
        nudging = true
        nudgeHandler.removeCallbacksAndMessages(null)
        // Collapse the breakdown panel first if it happens to be open: the line needs the full
        // width to read, and a wrapped guilt line stacked on top of five bars is a mess. The
        // stats are one tap away again the moment the line collapses.
        setExpanded(view, false)
        // Constrain the width so a full line wraps to a couple of readable lines instead of
        // stretching the overlay across the screen. This RESIZES the bubble's existing surface
        // — it does not free and re-create it, so D30's no-churn property holds (the surface
        // already resizes on digit-count changes). The resize is also why the render below is
        // followed by a re-placement: a 240dp-wide line on a pill parked at the right edge is
        // the widest the bubble ever gets, so it is the shape most likely to overhang (D38).
        view.setHeadlineMaxWidth(dp(NUDGE_MAX_WIDTH_DP))
        // The mascot stays put and has ALREADY escalated via renderCount, so the nudge reads as
        // the mascot saying the line. Headline text is what changes here; the panel behind it
        // carries the same line and is filled by renderCount either way.
        view.render(lastSummary, BrainState.forCount(count), headlineText = line, guiltLine = line)
        view.post { applyPlacement(remeasure = true) }
        nudgeHandler.postDelayed({ collapseNudge() }, GuiltCadence.DISPLAY_MS)
    }

    /** Collapse a showing nudge back to the count. Safe to call when not nudging. */
    private fun collapseNudge() {
        if (!nudging) return
        nudging = false
        bubble?.let { view ->
            view.setHeadlineMaxWidth(Int.MAX_VALUE)
            renderCount(view, lastSummary)
            view.post { applyPlacement(remeasure = true) }   // the pill shrinks back; re-clamp
        }
    }

    /** Cancel any pending/showing nudge — leaving the surface, blocking, or bubble turned off. */
    private fun cancelNudge() {
        nudgeHandler.removeCallbacksAndMessages(null)
        collapseNudge()
    }

    /**
     * Show/hide the bubble WITHOUT churning its surface (D30). We never toggle [View.GONE] on
     * the window's root view: WindowManagerService frees a GONE window's surface
     * (BLASTBufferQueue) and re-allocates it on VISIBLE, which on the hysteresis cycle produced
     * a construct/destruct surface every ~3–5s. Instead the root stays [View.VISIBLE] for the
     * service's lifetime and we hide it by:
     *  - fading [View.setAlpha] to 0 (draw-time only — no relayout to zero size, so the surface
     *    is not freed), and
     *  - adding [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] so the now-transparent overlay
     *    passes touches through to the app underneath instead of eating taps in its corner.
     * Both are relayouts that keep the window visible, so the surface persists across the toggle.
     * Idempotent: a no-op toggle skips the [WindowManager.updateViewLayout] relayout entirely.
     */
    private fun setBubbleShown(view: BubbleView, shown: Boolean) {
        if (shown == bubbleShown) return
        bubbleShown = shown
        view.alpha = if (shown) 1f else 0f
        val params = layoutParams ?: return
        params.flags = if (shown) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed toggling bubble visibility", e)
        }
    }

    /**
     * Take the block down and release its line. The ONE way the block is dismissed, so a
     * dismissal can never forget to end the episode and hand the next block a stale sentence.
     */
    private fun hideBlock() {
        GuiltLines.endBlockEpisode()
        // Releases the step sensor. Routed through here rather than sprinkled across the exit,
        // snooze and completion paths for the same reason endBlockEpisode is: a dismissal that
        // forgets leaves a sensor registered by a background service, which is a battery
        // complaint nobody ever traces back to us (D50).
        challenge.stop()
        block.hide()
    }

    /**
     * The user chose to earn their way out. Swap the block window's content to the challenge and
     * start the sensor.
     *
     * If the sensor refuses to start we stay on the block rather than showing a ring that can
     * never move — a user standing in their kitchen walking at a frozen 0/20 concludes the app is
     * broken, and they are not wrong. The button is already hidden unless [MotionStatus] says the
     * device can do this, so reaching the false branch means something changed underneath us
     * (a permission revoked while the block was up).
     */
    private fun onStartChallenge() {
        val spec = ChallengeRegistry.default ?: return
        val started = challenge.start(
            spec = spec,
            onProgress = { block.renderChallengeProgress(challenge.currentProgress, spec.target) },
            onComplete = ::onChallengeComplete,
        )
        if (!started) {
            Log.w(TAG, "challenge sensor unavailable; staying on the block")
            return
        }
        block.showChallenge(spec)
    }

    /** Backed out. Return to the block and release the sensor; progress is dropped, not banked. */
    private fun onCancelChallenge() {
        challenge.stop()
        block.showBlockPanel()
    }

    /**
     * Challenge completed — grant the EARNED reprieve.
     *
     * Deliberately a different, larger constant than [onSnooze]'s: granting the same as the free
     * tap would make the challenge strictly dominated and the feature dead on arrival. Same
     * persistence path as the tap, so everything D49 established about a reprieve — it survives
     * leaving Instagram, it survives a service restart, it re-blocks on the next reel after it
     * lapses, and no timer is involved — holds here unchanged.
     */
    private fun onChallengeComplete() {
        val platform = currentPlatform ?: return
        SettingsPrefs.setGraceUntilMs(
            context,
            platform,
            System.currentTimeMillis() + BlockLimits.CHALLENGE_GRACE_MS,
        )
        hideBlock()
        render(platform, lastSummary)
    }

    /**
     * Exit: dismiss the block and send the user to the launcher (leave the app).
     *
     * ## Why a launcher INTENT and not `performGlobalAction(GLOBAL_ACTION_HOME)` (D49)
     * The accessibility service could navigate the user out directly, and it would be fewer
     * lines. It is deliberately not done: driving another app's navigation through the
     * accessibility API is a materially stronger Play "misuse of the Accessibility API" signal
     * than starting the launcher, for an outcome the user cannot tell apart. Do not "simplify"
     * this into a global action.
     *
     * Dismissal happens BEFORE the intent and does not depend on it succeeding — invariant 6:
     * if the launcher cannot be started (a locked-down device, a weird OEM), the user must still
     * be looking at Instagram rather than at an overlay that would not go away.
     */
    private fun onExit() {
        hideBlock()
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "launcher intent failed", e)
        }
    }

    /**
     * "5 more minutes": grant a timed reprieve and take the block down.
     *
     * The deadline is PERSISTED (see [SettingsPrefs.graceUntilMs]) rather than held here, so the
     * promise survives leaving Instagram and survives the service being restarted — a reprieve
     * that a process death silently revokes is a promise broken at the worst possible moment.
     *
     * Nothing schedules the re-block. There is no timer: the grace is a deadline the render path
     * already compares against on every count emission, so the next reel AFTER it expires blocks
     * and no reel before it does. A timer would be a second source of truth for the same instant,
     * and would have to be cancelled correctly on every exit path.
     */
    private fun onSnooze() {
        val platform = currentPlatform ?: return
        SettingsPrefs.setGraceUntilMs(
            context,
            platform,
            System.currentTimeMillis() + BlockLimits.GRACE_MS,
        )
        hideBlock()
        render(platform, lastSummary)
    }

    /**
     * Push today's totals into the bubble: mascot + GRAND TOTAL compact, and the panel's total,
     * time and bars behind it. The tint comes from [BrainState] for the total (same source, same
     * number as Home). Called only on a distinct change, so this is cheap.
     *
     * The panel is filled even while it is collapsed — it costs a few text sets against a GONE
     * subtree and no layout, and it means expanding shows the current numbers instead of one
     * stale frame. See [BubbleView.render].
     *
     * This is a TEXT (and, on a state flip, ImageView-drawable) change on the already-attached
     * view, i.e. at most a RESIZE of the existing surface, exactly like the digit-count changes
     * and the nudge's maxWidth already do. It is NOT a window add/remove or a root VISIBLE/GONE
     * toggle, so D30's no-surface-churn property holds — do not "optimise" this into
     * re-attaching the view.
     *
     * While a nudge is showing, the compact TEXT is left alone (`headlineText = null`) — a count
     * change landing mid-nudge would otherwise wipe the guilt line off the screen a few hundred
     * ms after it appeared, which is exactly when it's most likely (the flip happens *because*
     * the count moved, and the user is mid-scroll). The tint, mascot and panel still update, so
     * everything is already correct when [collapseNudge] restores the number.
     */
    private fun renderCount(view: BubbleView, summary: TodaySummary) {
        val state = BrainState.forCount(summary.total)
        view.render(
            summary,
            state,
            headlineText = if (nudging) null else summary.total.toString(),
            // The panel's line, on every emission. Cheap: GuiltLines.current is pinned and only
            // redraws on a tier/day/pack/locale change, so this is a field read almost always.
            guiltLine = GuiltLines.current(context, summary.total),
        )
    }

    /**
     * Put the window where its CURRENT shape belongs, fully inside the usable screen area
     * (D38). The one place `params.x/y` is written outside the drag itself.
     *
     * ## The gravity/x/y logic
     * The window keeps `Gravity.TOP or Gravity.START` in every state, so `params.x/y` are the
     * window's top-left in absolute screen pixels and the arithmetic never has to change sign
     * or origin between shapes. It also keeps [WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS]
     * — that flag is what lets the compact pill sit flush against a real screen edge — which
     * means the system will NOT pull the window back on screen as it grows, and containment is
     * ours to do. That is precisely the bug this fixes: expanding a pill parked at the right
     * edge grew the panel straight off the display.
     *
     * So:
     *  - the usable rect comes from [OverlayMetrics.usableBounds] (display minus status bar,
     *    nav bar and cutout — never cached, since rotation and multi-window both move it),
     *  - the window's size comes from measuring the view against that rect (the window is
     *    WRAP_CONTENT, so its size is only knowable by measuring, and it differs by roughly 5×
     *    in height between the two shapes),
     *  - and [OverlayPlacement] turns those into x/y: COMPACT clamps the drag position into the
     *    rect; EXPANDED centres horizontally and keeps the pill's y, clamped so the bottom edge
     *    stays on screen.
     *
     * Skips the [WindowManager.updateViewLayout] entirely when x/y are already right, so a
     * no-op call (a drag that didn't move, a config change that didn't resize) costs nothing
     * and cannot churn the window.
     *
     * @param remeasure measure the view before placing it. TRUE whenever the view's size may
     *   have just changed — expanding, collapsing, rotating — because the laid-out `width`/
     *   `height` still describe the OLD shape at that moment. FALSE during a drag, where the
     *   shape is settled and re-measuring on every touch move would be pure waste.
     */
    private fun applyPlacement(remeasure: Boolean) {
        val view = bubble ?: return
        val params = layoutParams ?: return
        val bounds = OverlayMetrics.usableBounds(context, windowManager)
        if (bounds.width <= 0 || bounds.height <= 0) return   // no display info; leave as-is

        if (remeasure) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.AT_MOST),
            )
        }
        val width = if (remeasure) view.measuredWidth else view.width
        val height = if (remeasure) view.measuredHeight else view.height
        if (width <= 0 || height <= 0) return                 // not laid out yet; a later pass places it

        val placement = if (view.isExpanded) {
            OverlayPlacement.expanded(bounds, width, height, anchorY = compactY)
        } else {
            OverlayPlacement.compact(bounds, width, height, compactX, compactY)
        }
        if (params.x == placement.x && params.y == placement.y) return

        params.x = placement.x
        params.y = placement.y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed placing the overlay", e)
        }
    }

    /**
     * Re-place after a configuration change — rotation above all, but also a foldable opening
     * and multi-window resize. All of them move the usable rect out from under the window, and
     * with FLAG_LAYOUT_NO_LIMITS nothing else will notice: a pill parked at the bottom of a
     * portrait screen is simply off the bottom of the landscape one.
     *
     * Posted rather than applied inline because the view's own re-layout for the new
     * configuration hasn't run yet when this fires, so measuring here would measure against
     * stale metrics.
     */
    private fun onConfigurationChanged() {
        bubble?.post { applyPlacement(remeasure = true) }
    }

    private fun createBubbleView(): BubbleView {
        return BubbleView(context).apply {
            renderCount(this, TodaySummary.EMPTY) // initial: healthy mascot, 0, green tint
            setOnTouchListener(DragTapListener())
            onDisplayConfigChanged = ::onConfigurationChanged
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION") // TYPE_PHONE only on <26; we're min-SDK 26 so use overlay type.
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Starts hidden: NOT_TOUCHABLE (with alpha 0 in ensureAttached) so the freshly
            // attached, invisible bubble passes touches through until the first [setBubbleShown].
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // TOP|START for every state, compact and expanded alike (D38): it makes x/y the
            // window's absolute top-left, so [OverlayPlacement] does one kind of arithmetic
            // instead of switching origin when the panel opens. CENTER_HORIZONTAL would express
            // the expanded state more directly but would make x mean "offset from centre" there
            // and "offset from the left edge" when compact — two meanings for one field that
            // the drag handler also writes.
            gravity = Gravity.TOP or Gravity.START
            // A spawn point, not a final position: [ensureAttached] clamps it into the insets
            // as soon as the view has a measured size.
            x = dp(16)
            y = dp(120)
        }
    }

    /**
     * A tap toggles the breakdown panel open/closed, IN PLACE (D37). It used to launch
     * ScrollKiller Home, which was the wrong move for an overlay: the user tapped to see their
     * split and got yanked out of the video to do it. Expanding costs one child-visibility
     * change inside the window that is already attached — no new window, no Activity.
     *
     * Refuses to expand into an empty panel: with no counts today there are no bars and the
     * panel would open on a single "0 in ~0.0 min" line. Collapsing is always allowed.
     */
    private fun toggleExpanded(view: BubbleView) {
        val expand = !view.isExpanded
        if (expand && !view.hasBreakdown(lastSummary)) return
        setExpanded(view, expand)
    }

    /**
     * Change shape and immediately re-place the window for it (D38). Always go through this
     * rather than calling [BubbleView.setExpanded] directly: the panel is ~5× the pill's
     * height, so a shape change without a re-placement is exactly how the panel ended up
     * hanging off the screen edge.
     *
     * The follow-up [View.post] is a correction pass, not a second placement. The synchronous
     * [applyPlacement] measures the view ourselves so the panel lands in the right place on the
     * FIRST frame it is visible (measuring after the fact would show one frame in the wrong
     * position, then jump). If that prediction differs from what the real layout pass produces
     * — a font scale we didn't account for, a count that just gained a digit — this catches it.
     * It cannot loop: [applyPlacement] returns without touching the window when x/y already
     * match, which is the normal case.
     */
    private fun setExpanded(view: BubbleView, expanded: Boolean) {
        if (expanded == view.isExpanded) return
        view.setExpanded(expanded)
        applyPlacement(remeasure = true)
        view.post { applyPlacement(remeasure = true) }
    }

    /**
     * Combined drag + tap handler. A gesture that moves less than [touchSlop] counts
     * as a tap ([toggleExpanded]); anything more drags the bubble by updating the window
     * position. Uses raw screen coords so drag tracks the finger regardless of the
     * bubble's current position.
     *
     * A drag COLLAPSES an open panel first. Dragging is a gesture for repositioning the pill,
     * and the alternative — dragging the expanded panel — has no good answer: the panel's
     * position is centred by definition (D38), so either the drag fights the centring or the
     * panel stops being where the user learned to expect it. Collapsing costs one child
     * visibility toggle inside the already-attached window, so it is not window churn.
     */
    private inner class DragTapListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false

        @SuppressLint("ClickableViewAccessibility") // this IS an accessibility-adjacent overlay; tap handled in ACTION_UP
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            layoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    // Anchored to the COMPACT position, not params.x/y: if the panel is open,
                    // params holds the centred panel position and the pill would jump to it.
                    startX = compactX
                    startY = compactY
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragged && abs(dx) < touchSlop && abs(dy) < touchSlop) return true
                    // The gesture just became a drag. Collapse first (see the class-level note),
                    // and remember it, because the very next placement must NOT trust the
                    // laid-out size: the panel has only just gone GONE, so `view.width/height`
                    // still describe the expanded shape for one more layout pass, and clamping
                    // the pill against the panel's dimensions would yank it inward for a frame.
                    val justCollapsed = !dragged && bubble?.isExpanded == true
                    if (!dragged) bubble?.let { setExpanded(it, false) }
                    dragged = true
                    compactX = startX + dx.toInt()
                    compactY = startY + dy.toInt()
                    // Clamped, so the pill can be parked flush against an edge but not past it.
                    // remeasure only on that first post-collapse move; for the rest of the drag
                    // the shape is settled and re-measuring per touch event is pure waste.
                    applyPlacement(remeasure = justCollapsed)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        // Keep the drag position CLAMPED, not raw: the finger can travel past the
                        // edge, and storing the overshoot would make the next expand anchor its
                        // panel off-screen and the next drag start with a dead zone.
                        layoutParams?.let { compactX = it.x; compactY = it.y }
                    } else {
                        bubble?.let { toggleExpanded(it) }
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ScrollKiller"

        /** Width cap while nudging, so a line wraps instead of spanning the screen. */
        const val NUDGE_MAX_WIDTH_DP = 240
    }
}
