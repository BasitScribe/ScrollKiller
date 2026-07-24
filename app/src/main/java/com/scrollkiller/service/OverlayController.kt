package com.scrollkiller.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.scrollkiller.MainActivity
import com.scrollkiller.R
import com.scrollkiller.brain.BrainState
import com.scrollkiller.data.CountRepository
import com.scrollkiller.data.SettingsPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs

/**
 * Coordinates the on-surface overlay for one doom surface at a time: the passive
 * live-counter bubble (🧠 + count) and, once the platform's daily limit is crossed,
 * the full-screen [BlockScreenController]. Both are drawn via SYSTEM_ALERT_WINDOW.
 *
 * Kept OUT of [ReelScrollAccessibilityService] on purpose (CLAUDE.md: the service
 * only emits events, no God-classes). The service decides which doom surface we're on
 * ([onSurface]/[offSurface]) and delegates all WindowManager work here.
 *
 * Design constraints (this lives inside someone's doomscroll session — it can't jank
 * Instagram or eat battery):
 *  - One source of truth: reads the SAME Room count Home uses, via
 *    [CountRepository.observeToday] (per-platform projection — not a second counter).
 *  - Cheap: views update on count *change* only ([distinctUntilChanged]); no animation
 *    loop, no polling.
 *  - No work off-surface: the Flow is collected only between [onSurface] and
 *    [offSurface].
 *  - No bubble churn: the bubble is added ONCE ([ensureAttached]) and never add/removed per
 *    app-switch or idle-timeout (D17/D29). It is ALSO never toggled [View.GONE]: a GONE root
 *    view makes WindowManagerService hide the window and free its surface (BLASTBufferQueue),
 *    so a GONE/VISIBLE cycle on the hysteresis timer churned a construct/destruct surface
 *    every ~3–5s (D30). Instead the root stays [View.VISIBLE] for the service's lifetime and
 *    "hidden" means alpha 0 + [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] ([setBubbleShown])
 *    — both keep the window visible, so the surface persists. The block is add/removed on
 *    demand (rare, and a focusable window must not linger — see [BlockScreenController]).
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
        BlockScreenController(context, onExit = ::onExit, onUnlock = ::onUnlock)
    }

    /** The doom surface we're currently on (null = off-surface). */
    private var currentPlatform: Platform? = null

    /** Set by [onUnlock]; suppresses the block until we leave the surface. TEMPORARY —
     *  a successful challenge will drive this next checkbox. Reset in [offSurface]. */
    private var unlocked = false

    /** Last count seen, so [onUnlock] can re-render without waiting for a new emission. */
    private var lastCount = 0

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
    private var bubble: TextView? = null

    /** Live layout params so drag can mutate x/y and re-apply them. */
    private var layoutParams: WindowManager.LayoutParams? = null

    /** Whether the bubble is currently shown (alpha 1 + touchable). Tracked so [setBubbleShown]
     *  is idempotent and doesn't relayout the window on a no-op toggle. */
    private var bubbleShown = false

    /** Scope for the count-Flow collection; alive only while on the doom surface. */
    private var collectScope: CoroutineScope? = null

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
        bubbleShown = false   // matches the alpha-0 + NOT_TOUCHABLE hidden state we just attached
    }

    /**
     * The user is now on [platform]'s doom surface. Start observing that platform's
     * count and render either the bubble or the block per [BlockPolicy]. Called once
     * per surface entry (the service transition-gates it).
     */
    fun onSurface(platform: Platform) {
        currentPlatform = platform
        unlocked = false
        ensureAttached()               // lazy first-attach if permission came later
        startCollecting(platform)
    }

    /** The user left the doom surface: stop work and hide both overlays. */
    fun offSurface() {
        collectScope?.cancel()
        collectScope = null
        currentPlatform = null
        unlocked = false
        bubble?.let { setBubbleShown(it, false) }
        block.hide()
    }

    /** Full teardown for service destroy/unbind: stop work and remove the windows. */
    fun destroy() {
        collectScope?.cancel()
        collectScope = null
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

    /** Collect the platform's count and (re)render on change only. */
    private fun startCollecting(platform: Platform) {
        collectScope?.cancel()   // defensive: never leak a prior platform's collector on a direct switch
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        collectScope = scope
        repository.observeToday(platform)
            .distinctUntilChanged()
            .onEach { count -> render(platform, count) }
            .launchIn(scope)
    }

    /**
     * Decide and show bubble vs block for the current count. Visibility is SURFACE-DRIVEN
     * (D29, supersedes D21's per-scroll idle-dismiss): the bubble is VISIBLE the whole time
     * we're on the reel surface — the service's hysteresis keeps us "on surface" across the
     * gap between swipes, and [offSurface] hides it on leaving. The block only fires when
     * [PlatformSpec.blockEnabled] is set (false for every platform today — never the feed).
     */
    private fun render(platform: Platform, count: Int) {
        lastCount = count
        val spec = PlatformRegistry.specFor(platform)
        when (BlockPolicy.overlayFor(count, spec.dailyLimit, spec.blockEnabled, unlocked)) {
            SurfaceOverlay.BLOCK -> {
                bubble?.let { setBubbleShown(it, false) }
                block.show(platform, guiltLine())
            }
            SurfaceOverlay.BUBBLE -> {
                block.hide()
                val view = bubble ?: return          // no overlay permission — nothing to show
                if (!SettingsPrefs.isBubbleEnabled(context)) {
                    setBubbleShown(view, false)       // user turned the bubble off
                    return
                }
                renderCount(view, count)
                setBubbleShown(view, true)            // visible while on the reel surface
            }
        }
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
    private fun setBubbleShown(view: TextView, shown: Boolean) {
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

    /** Exit: dismiss the block and send the user to the launcher (leave the app). */
    private fun onExit() {
        block.hide()
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
     * Unlock: bypass the block until the user leaves this surface. TEMPORARY stand-in
     * for the challenge flow (next checkbox) — a successful challenge will call this.
     */
    private fun onUnlock() {
        unlocked = true
        currentPlatform?.let { render(it, lastCount) }
    }

    /** One guilt line, chosen at show time. TODO: guilt_pack.json loader replaces this. */
    private fun guiltLine(): String {
        val lines = context.resources.getStringArray(R.array.block_guilt_lines)
        return lines[(lines.indices).random()]
    }

    /**
     * Push a count into the bubble: emoji + number, with the background tinted to the
     * brain state. Both come from [BrainState] (same source as Home). Called only on a
     * distinct count change, so this is cheap and touches no window layout.
     */
    private fun renderCount(view: TextView, count: Int) {
        val state = BrainState.forCount(count)
        view.text = "${state.emoji} $count"
        // mutate() so tinting this instance doesn't affect the shared drawable constant.
        view.background?.mutate()?.setTint(state.accentArgb.toInt())
    }

    private fun createBubbleView(): TextView {
        return TextView(context).apply {
            setBackgroundResource(R.drawable.overlay_bubble_bg)
            val padH = dp(12)
            val padV = dp(8)
            setPadding(padH, padV, padH, padV)
            setTextColor(0xFFFFFFFF.toInt())  // white stays legible over the accent-tinted pill
            renderCount(this, 0)   // initial: healthy 🧠 0, green tint
            // Keep the bubble out of the accessibility tree: it must not generate its
            // own window/content events (that fed a show/hide flicker) and a decorative
            // count needn't be announced to screen readers.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener(DragTapListener())
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
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
    }

    /** Open ScrollKiller Home. Launched from a non-Activity context, so NEW_TASK. */
    private fun openHome() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    /**
     * Combined drag + tap handler. A gesture that moves less than [touchSlop] counts
     * as a tap (open Home); anything more drags the bubble by updating the window
     * position. Uses raw screen coords so drag tracks the finger regardless of the
     * bubble's current position.
     */
    private inner class DragTapListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false

        @SuppressLint("ClickableViewAccessibility") // this IS an accessibility-adjacent overlay; tap handled in ACTION_UP
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragged && abs(dx) < touchSlop && abs(dy) < touchSlop) return true
                    dragged = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateViewLayout failed during drag", e)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) openHome()
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
    }
}
