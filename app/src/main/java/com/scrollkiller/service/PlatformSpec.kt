package com.scrollkiller.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * The short-video platforms ScrollKiller knows how to detect.
 *
 * [id] is the wire/storage identifier and MUST match the `daily_counts.platform`
 * enum in docs/SCHEMA.md exactly — emitted events, Room rows, and server sync all
 * key off this string, so Room/sync stay platform-agnostic.
 */
enum class Platform(val id: String) {
    INSTAGRAM("instagram"),
    YOUTUBE("youtube"),
    SNAPCHAT("snapchat"),
    FACEBOOK("facebook"),
    TIKTOK("tiktok"),
}

/**
 * How a platform's "reel advanced" signal is derived from raw scroll events.
 *
 * - [TIME_DEBOUNCE]: collapse a fling's event burst into one advance using a
 *   quiet-gap timer (see [SwipeDetector]). This is all we use today.
 * - INDEX_CHANGE is intentionally NOT defined yet; add it in Phase 2 only if a
 *   platform turns out to expose a stable per-item index worth keying off.
 */
enum class DetectionStrategy { TIME_DEBOUNCE }

/**
 * Everything the service needs to detect one platform. Adding a platform is data,
 * not code: inspect its reel view tree, then append a [PlatformSpec] to
 * [PlatformRegistry.enabled] (and widen the config XML `packageNames` allowlist).
 *
 * @param containerHints class-name suffixes of the scrolled reel container. We
 *   match on suffix so A/B'd builds that repackage the same widget still hit.
 * @param minAdvanceIntervalMs quiet-gap for the debounce — the minimum time with
 *   no DOWN event before the next DOWN is treated as a new reel advance.
 * @param surfaceMarkers `viewIdResourceName` substrings that identify the *doom
 *   surface* (the Reels viewer), as opposed to the feed/stories/profile/DMs which
 *   share the same package. A node is "on the surface" if any node in its ancestor
 *   chain has a `viewIdResourceName` containing one of these. Counting AND bubble
 *   visibility both gate on this (see [SurfaceMatcher]). EMPTY = not yet gating:
 *   the matcher passes through so behaviour is unchanged until markers are chosen
 *   from a device surface tour (see [SurfaceDiagnostics]).
 * @param dailyLimit reels-per-day before the block screen escalates. Default 100 for
 *   now; a user setting will override this later. Kept here so the limit lives in one
 *   place and can differ per platform.
 */
data class PlatformSpec(
    val platform: Platform,
    val packageName: String,
    val containerHints: List<String>,
    val minAdvanceIntervalMs: Long,
    val surfaceMarkers: List<String> = emptyList(),
    val dailyLimit: Int = 100,
    val strategy: DetectionStrategy = DetectionStrategy.TIME_DEBOUNCE,
)

/**
 * The single source of truth for which platforms are active.
 *
 * Only Instagram is enabled today. YouTube Shorts / Snapchat / etc. specs land in
 * Phase 2 after their view trees are inspected — the enum entries already exist so
 * their [Platform.id]s are stable.
 *
 * NOTE: the service is no longer scoped by a `packageNames` allowlist in
 * res/xml/accessibility_service_config.xml (removed so window-state-changed events
 * for the app the user switches TO are delivered, which is how the overlay bubble
 * hides on leaving a tracked app — see D16). [forPackage] is therefore the SOLE
 * gate: events from untracked apps return null here and are dropped. Adding a
 * platform is still just appending a [PlatformSpec] to [enabled].
 */
object PlatformRegistry {

    private val instagram = PlatformSpec(
        platform = Platform.INSTAGRAM,
        packageName = "com.instagram.android",
        // The reel pager. Field-observed on the shipping IG build (2026-07): the
        // real advance signal (non-zero scrollDeltaY) comes from the SUPPORT-LIBRARY
        // ViewPager v1 — `androidx.viewpager.widget.ViewPager`. The inner
        // RecyclerView fires alongside but always reports deltaY=0 (settle noise), so
        // keying on it alone yields SAME and never counts. ViewPager2 is kept for
        // builds that use it. NOTE: RecyclerView is retained provisionally — verify
        // the comments-open edge case doesn't overcount (a comment list is also a
        // RecyclerView); drop it if it does. See DECISIONS.
        containerHints = listOf(
            "androidx.viewpager.widget.ViewPager",
            "androidx.viewpager2.widget.ViewPager2",
            "androidx.recyclerview.widget.RecyclerView",
        ),
        // Starting value; calibrated against the 50-swipe exit test. Just above the
        // ~110ms intra-fling event cadence so a fling's burst collapses to one advance.
        minAdvanceIntervalMs = 200L,
        // TODO(surface tour): populate from the DEBUG SurfaceDiagnostics log — the
        // Reels-viewer resource-id (expected something like `clips_viewer_view_pager`)
        // that the feed/stories/profile don't have. EMPTY until evidence: the gate is
        // a pass-through so detection/bubble behave exactly as before in the meantime.
        surfaceMarkers = emptyList(),
    )

    /** Platforms detected today. */
    val enabled: List<PlatformSpec> = listOf(instagram)

    /** Spec whose package produced this event, or null if it's not a tracked app. */
    fun forPackage(packageName: CharSequence?): PlatformSpec? {
        val pkg = packageName?.toString() ?: return null
        return enabled.firstOrNull { it.packageName == pkg }
    }

    /** Spec for a known [Platform]. Every enabled platform has exactly one spec. */
    fun specFor(platform: Platform): PlatformSpec =
        enabled.first { it.platform == platform }

    /** Tracked packages. No longer mirrored in the config XML (see NOTE above); kept
     *  for diagnostics and any future re-scoping. */
    val packageNames: List<String> get() = enabled.map { it.packageName }
}

/**
 * Decides whether a node tree is currently showing a platform's *doom surface*
 * (its Reels viewer) using [PlatformSpec.surfaceMarkers]. This is the single
 * predicate behind "user is doomscrolling right now" — both the counting gate and
 * the bubble read it, so they can never disagree.
 *
 * Pass-through when a spec has no markers yet (pre-evidence): returns `true` so the
 * app keeps its prior behaviour until the surface tour fills the markers in.
 *
 * All walks are bounded and recycle the nodes they allocate (correct on API 26–32;
 * `recycle()` is a harmless no-op on 33+). Nodes passed IN are owned by the caller.
 */
object SurfaceMatcher {

    /** Depth cap on the ancestor walk from a scrolled node. */
    private const val MAX_ANCESTORS = 30

    /** Node-count cap on the window scan so a huge tree can't stall the main thread. */
    private const val MAX_SCAN_NODES = 400

    /**
     * Is [node] (a scrolled view) inside the doom surface? Checks the node itself and
     * every ancestor for a `viewIdResourceName` containing any marker. The scrolled
     * node's ancestry is the cheap, authoritative signal — we already hold the node.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun matchesSurface(node: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean {
        if (spec.surfaceMarkers.isEmpty()) return true   // not gating yet
        if (node == null) return false

        if (idMatches(node.viewIdResourceName, spec)) return true

        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTORS) {
            if (idMatches(current.viewIdResourceName, spec)) {
                current.recycle()
                return true
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        return false
    }

    /**
     * Does the active-window [root] tree contain the doom surface anywhere? Used on
     * window-STATE changes (rare — activity/fragment transitions) to catch entering
     * the Reels viewer without scrolling. Bounded DFS; NOT run per content-change.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun windowMatchesSurface(root: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean {
        if (spec.surfaceMarkers.isEmpty()) return true   // not gating yet
        if (root == null) return false

        // Iterative DFS over freshly-allocated child nodes; recycle each after use.
        // The root belongs to the caller, so we never recycle it here.
        var scanned = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        if (idMatches(root.viewIdResourceName, spec)) return true
        pushChildren(root, stack)
        while (stack.isNotEmpty() && scanned < MAX_SCAN_NODES) {
            val n = stack.removeLast()
            scanned++
            if (idMatches(n.viewIdResourceName, spec)) {
                n.recycle()
                stack.forEach { it.recycle() }
                return true
            }
            pushChildren(n, stack)
            n.recycle()
        }
        stack.forEach { it.recycle() }
        return false
    }

    private fun pushChildren(node: AccessibilityNodeInfo, stack: ArrayDeque<AccessibilityNodeInfo>) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { stack.addLast(it) }
        }
    }

    private fun idMatches(id: CharSequence?, spec: PlatformSpec): Boolean {
        val value = id?.toString() ?: return false
        return spec.surfaceMarkers.any { value.contains(it) }
    }
}
