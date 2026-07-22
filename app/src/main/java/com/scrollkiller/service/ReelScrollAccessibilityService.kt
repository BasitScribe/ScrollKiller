package com.scrollkiller.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.scrollkiller.ScrollKillerApp
import com.scrollkiller.data.CountRepository

/**
 * On-device detector for short-video reel scrolling.
 *
 * Per the ScrollKiller architecture the device is the source of truth: this
 * service ONLY observes accessibility events, debounces them into discrete "reel
 * advanced" signals, and hands them to the [CountRepository]. It must never become
 * a God-class — no Room, no networking, no per-platform detection logic lives here.
 * Which apps to watch and how to debounce them is data in [PlatformRegistry]; the
 * collapse logic is the pure [SwipeDetector]. This class just wires them to events.
 *
 * Privacy invariant: we read positional/direction metadata only. No screen text,
 * captions, or content is ever touched. Window-state-changed events (used to gate
 * the overlay bubble) carry only the foreground package/class name, never content.
 *
 * Logcat (filter with `adb logcat -s ScrollKiller`):
 *   D/ScrollKiller: REEL_ADVANCE platform=instagram
 */
class ReelScrollAccessibilityService : AccessibilityService() {

    /** One detector per platform so each keeps its own quiet-gap timer. */
    private val detectors = mutableMapOf<Platform, SwipeDetector>()

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
     * The single "user is doomscrolling right now" state — the tracked [Platform] whose
     * doom surface ([SurfaceMatcher]) is foreground, or null when off-surface. BOTH the
     * counting gate and the overlay read this, so they can never disagree. Updated from
     * scroll events (node ancestry) and window-STATE changes (root tree scan); never
     * from the content-changed flood (kept cheap). Routed through [setDoomSurface] so
     * the overlay only reacts on a genuine transition. Carrying the platform (not just a
     * Boolean) is what lets the overlay gate the block on that platform's count/limit.
     */
    private var doomPlatform: Platform? = null

    /** Attach the bubble window ONCE, up front. It stays attached (toggled VISIBLE/
     *  GONE) for the service's lifetime — no per-app-switch add/remove churn (D17). */
    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay.ensureAttached()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Rich node-tree diagnostics for the surface tour. Compiled out of release
        // builds (BuildConfig.DEBUG gate inside). See SurfaceDiagnostics.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> SurfaceDiagnostics.logScrolled(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                SurfaceDiagnostics.logWindowChange(event, rootInActiveWindow)
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onForegroundChanged(event)
            // WINDOW_CONTENT_CHANGED intentionally drives NO runtime surface work — it
            // fires constantly; scanning per event would be too costly. Surface state
            // comes from scroll ancestry + window-state scans, which is enough.
        }
    }

    /**
     * Reel-advance detection, now gated on package AND surface. The scrolled node's
     * ancestry is the authoritative surface signal (we already hold the node), so it
     * both gates counting and keeps [onDoomSurface] in sync.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    private fun onScrolled(event: AccessibilityEvent) {
        // Which tracked platform is this from? Bail on anything unregistered.
        val spec = PlatformRegistry.forPackage(event.packageName) ?: return

        // Is the scrolled view the reel container we care about (not some other
        // list in the app, e.g. comments)?
        val className = event.className?.toString().orEmpty()
        if (spec.containerHints.none { className.endsWith(it) }) return

        // Surface gate: are we actually on the doom surface (Reels viewer), not the
        // feed/comments that share the same container widget? Pass-through until the
        // spec's surfaceMarkers are populated from the surface tour.
        val source = event.source
        val onSurface = SurfaceMatcher.matchesSurface(source, spec)
        source?.recycle()
        setDoomSurface(if (onSurface) spec.platform else null)
        if (!onSurface) return

        val direction = scrollDirection(event)
        val detector = detectors.getOrPut(spec.platform) { SwipeDetector(spec.minAdvanceIntervalMs) }

        // Debounce the fling burst into a single forward advance.
        if (detector.onScroll(direction, System.currentTimeMillis())) {
            repository.record(spec.platform)
            Log.d(TAG, "REEL_ADVANCE platform=${spec.platform.id}")
        }
    }

    /**
     * Foreground/window changed. Re-derive the doom surface: for a tracked package we
     * scan the active window's node tree for the surface markers; anything else means
     * we've left tracked content. The service is scoped to all packages precisely so
     * the "left Instagram" window-state-changed (which belongs to the destination app)
     * is delivered here.
     *
     * We deliberately do NOT de-dup on package anymore: entering the Reels viewer WITHIN
     * Instagram fires a same-package window-state-changed, and we need to re-scan for
     * it. [setDoomSurface] transition-gates the actual show/hide, so re-evaluating on
     * every window-state event is idempotent and flicker-free. (In the interim, empty
     * surfaceMarkers short-circuit the scan to `true`, so this stays cheap and collapses
     * to the old package-based show/hide.)
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    private fun onForegroundChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg.isNullOrEmpty()) return
        if (pkg == packageName) return          // our overlay/UI is not a foreground change

        val spec = PlatformRegistry.forPackage(pkg)
        if (spec == null) {
            setDoomSurface(null)                // left the tracked app entirely
            return
        }

        val root = rootInActiveWindow
        val onSurface = SurfaceMatcher.windowMatchesSurface(root, spec)
        root?.recycle()
        setDoomSurface(if (onSurface) spec.platform else null)
    }

    /** Update the doom-surface state and drive the overlay, but only on a real change. */
    private fun setDoomSurface(platform: Platform?) {
        if (platform == doomPlatform) return
        doomPlatform = platform
        if (platform != null) overlay.onSurface(platform) else overlay.offSurface()
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
        overlay.destroy()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlay.destroy()
        super.onDestroy()
    }

    private companion object {
        /** Single tag so you can filter everything with: `adb logcat -s ScrollKiller`. */
        const val TAG = "ScrollKiller"
    }
}
