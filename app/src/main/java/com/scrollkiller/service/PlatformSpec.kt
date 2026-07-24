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
 * How aggressively a platform's [PlatformSpec.surfaceMarkers] gate counting. This
 * exists so we can ENCODE candidate (device-unverified) markers without regressing a
 * platform whose detection already works, and without a wrong guess silently zeroing
 * out all counting. See D24.
 *
 * - [PASSTHROUGH]: surface markers are ignored for counting; anything matching the
 *   [PlatformSpec.containerHints] on the tracked package counts. This is the Phase-1
 *   behaviour (Instagram shipped here with empty markers).
 * - [SHADOW]: markers are EVALUATED and LOGGED (`SCROLL_DETECTED` / `SCROLL_IGNORED`),
 *   so we can validate a candidate against a real device without risk — but counting
 *   still happens regardless of the match. Use for a platform whose detection is
 *   already calibrated (Instagram) while we confirm the Reels-only discriminator.
 * - [ENFORCED]: counting happens ONLY when a marker matches. Use for a NEW platform
 *   whose markers are still guesses: a wrong guess then fails toward *undercount*
 *   (never matches → nothing counts) rather than counting the whole app's feed.
 *   REQUIRES non-empty [PlatformSpec.surfaceMarkers] (an ENFORCED spec with empty
 *   markers would pass through and count everything — asserted in tests).
 */
enum class GatingMode { PASSTHROUGH, SHADOW, ENFORCED }

/**
 * Everything the service needs to detect one platform. Adding a platform is data,
 * not code: inspect its short-video view tree, then append a [PlatformSpec] to
 * [PlatformRegistry.enabled].
 *
 * @param containerHints SIMPLE class names (no package) of the scrolled short-video
 *   container, e.g. "RecyclerView", "ViewPager", "ViewPager2". Matched via
 *   [matchesContainer] on the simple name so a legacy support-library widget matches an
 *   AndroidX hint of the same name — YouTube Shorts scrolls an
 *   `android.support.v7.widget.RecyclerView`, which must still hit the `RecyclerView`
 *   hint (D27). Storing fully-qualified names here silently broke YouTube (see D27).
 * @param minAdvanceIntervalMs quiet-gap for the debounce — the minimum time with
 *   no DOWN event before the next DOWN is treated as a new advance.
 * @param surfaceMarkers `viewIdResourceName` substrings that identify the *doom
 *   surface* (the Reels/Shorts viewer), as opposed to the feed/search/profile/DMs
 *   which share the same package. A node is "on the surface" if any node in its
 *   ancestor chain has a `viewIdResourceName` containing one of these. How they gate
 *   counting is controlled by [gating].
 * @param gating how [surfaceMarkers] gate counting for this platform (see [GatingMode]).
 * @param blockEnabled whether the full-screen block screen may fire for this platform.
 *   SEPARATE from [gating] on purpose: a platform can count on its surface long before
 *   we trust its markers enough to blackout the screen at the limit. Ships FALSE for
 *   every platform — the block stays dormant until a device surface tour verifies the
 *   markers and this is flipped per-platform (preserves D19's "never the feed"). See D24.
 * @param unitNoun what one advance is called for this platform ("reel", "short", …).
 *   Stored on each raw [com.scrollkiller.data.db.ScrollEvent] and shown in the UI.
 * @param displayName human label for the Apps dashboard. Kept here (not resolved via
 *   PackageManager) so we need no QUERY_ALL_PACKAGES permission — the tracked set is a
 *   small known list, so a Play-sensitive package query would be gratuitous.
 * @param dailyLimit advances-per-day before the block screen escalates (when
 *   [blockEnabled]). Default 100; a user setting will override this later.
 */
data class PlatformSpec(
    val platform: Platform,
    val packageName: String,
    val containerHints: List<String>,
    val minAdvanceIntervalMs: Long,
    val surfaceMarkers: List<String> = emptyList(),
    val gating: GatingMode = GatingMode.PASSTHROUGH,
    val blockEnabled: Boolean = false,
    val unitNoun: String = "reel",
    val displayName: String = "",
    val dailyLimit: Int = 100,
    val strategy: DetectionStrategy = DetectionStrategy.TIME_DEBOUNCE,
) {
    /** True when this platform drops counts off-surface (a wrong marker undercounts). */
    val enforcesSurface: Boolean get() = gating == GatingMode.ENFORCED

    /**
     * Does [className] name one of this platform's scroll containers? Compared on the SIMPLE
     * class name (after the last '.') so a legacy support-library widget matches an AndroidX
     * hint of the same name. Fully-qualified `endsWith` matching (the old approach) silently
     * failed YouTube Shorts, whose container is `android.support.v7.widget.RecyclerView` — it
     * never `endsWith("androidx.recyclerview.widget.RecyclerView")`. See D27.
     */
    fun matchesContainer(className: CharSequence?): Boolean {
        val simple = className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            ?: return false
        return containerHints.any { it == simple }
    }
}

/**
 * The single source of truth for which platforms are active.
 *
 * Instagram is fully calibrated (Phase 1). YouTube Shorts / TikTok / Snapchat are
 * SCAFFOLDED here in Phase 2: their specs are wired end-to-end but ship [GatingMode.ENFORCED]
 * with *candidate* (device-unverified) markers, so before the on-device tour confirms
 * them they can only ever UNDERcount — never count a home feed. Fill/replace the marker
 * strings from a labeled SurfaceDiagnostics tour, then flip [PlatformSpec.blockEnabled].
 *
 * The service is NOT scoped by a `packageNames` allowlist in
 * res/xml/accessibility_service_config.xml (removed so window-state-changed events for
 * the app the user switches TO are delivered — how the overlay bubble hides on leaving
 * a tracked app; see D16). [forPackage] is therefore the SOLE gate: events from
 * untracked apps return null here and are dropped.
 */
object PlatformRegistry {

    private val instagram = PlatformSpec(
        platform = Platform.INSTAGRAM,
        packageName = "com.instagram.android",
        // The reel pager. Field-observed on the shipping IG build: the real advance signal
        // (non-zero scrollDeltaY) comes from the ViewPager v1 (`ViewPager`, simple name). The
        // inner RecyclerView fires alongside but always reports deltaY=0 (settle noise). See D15.
        containerHints = listOf("ViewPager", "ViewPager2", "RecyclerView"),
        minAdvanceIntervalMs = 200L,
        // VERIFIED marker (surface tour, 2026-07-24 — D26). Reels emit
        // srcId=clips_viewer_view_pager → MATCH(clips_viewer); every sibling surface is
        // NO_MATCH: home feed (swipeable_tab_view_pager), profile grid (clips_grid_recyclerview),
        // DMs (sticky_header_list / bottom_sheet_container), Stories (reel_viewer_* — internally
        // "reels", which is exactly why we key on "clips_viewer" ONLY, not "reel_viewer"). The
        // discriminator is proven and non-colliding, so IG is ENFORCED: it counts ONLY on the
        // reel surface. blockEnabled stays false until a limit/challenge session enables it.
        surfaceMarkers = listOf("clips_viewer"),
        gating = GatingMode.ENFORCED,
        blockEnabled = false,
        unitNoun = "reel",
        displayName = "Instagram Reels",
    )

    private val youtube = PlatformSpec(
        platform = Platform.YOUTUBE,
        packageName = "com.google.android.youtube",
        // Simple names (D27). YouTube Shorts scrolls the LEGACY support-library RecyclerView
        // (event class `android.support.v7.widget.RecyclerView`) — the old fully-qualified
        // `endsWith` hint never matched it, so Shorts counted ZERO (D27). Simple-name matching
        // fixes that. (Whether Shorts emits a usable scroll DIRECTION is a separate question —
        // re-tour to confirm; if it only ever emits deltaY=0/SAME, YT needs its own advance
        // signal. See D27.)
        containerHints = listOf("ViewPager2", "RecyclerView"),
        minAdvanceIntervalMs = 200L,
        // VERIFIED marker (surface tour, 2026-07-24 — D26): the full-screen Shorts player
        // recycler emits srcId=reel_recycler → MATCH(reel_recycler). Narrowed to this ONE
        // proven id: the unverified `shorts_*` guesses were dropped because the home feed
        // carries a Shorts SHELF whose ids could false-match under ENFORCED (D28). Kept
        // ENFORCED so a miss undercounts, never counts the home/subscriptions feed.
        surfaceMarkers = listOf("reel_recycler"),
        gating = GatingMode.ENFORCED,
        blockEnabled = false,
        unitNoun = "short",
        displayName = "YouTube Shorts",
    )

    private val tiktok = PlatformSpec(
        platform = Platform.TIKTOK,
        // Global TikTok. NOTE: the spec's "com.ss.android.ugc.tiktok" is not a real
        // package — global is com.zhiliaoapp.musically; regional variants are
        // com.ss.android.ugc.trill / com.ss.android.ugc.aweme (add specs if targeting them).
        packageName = "com.zhiliaoapp.musically",
        containerHints = listOf("ViewPager2", "RecyclerView"),
        minAdvanceIntervalMs = 200L,
        // NOT TOURED (D28). TikTok ids are heavily obfuscated and these markers are pure
        // guesses. Left in SHADOW rather than shipping a guessed ENFORCED marker: SHADOW
        // evaluates + LOGS the marker check on a real device (so a future tour can confirm the
        // discriminator) while counting still happens, so we don't silently zero TikTok on a
        // wrong guess. TRADEOFF: SHADOW counts app-wide container scrolls (TikTok is ~all FYP,
        // so this is largely fine); blockEnabled stays false so nothing is enforced on the user.
        surfaceMarkers = listOf("feed_container", "feed_recyclerview", "feed_pager"),
        gating = GatingMode.SHADOW,
        blockEnabled = false,
        unitNoun = "short",
        displayName = "TikTok",
    )

    private val snapchat = PlatformSpec(
        platform = Platform.SNAPCHAT,
        packageName = "com.snapchat.android",
        containerHints = listOf("ViewPager2", "RecyclerView"),
        minAdvanceIntervalMs = 200L,
        // NOT TOURED (D28). "spotlight" is a plausible but unverified guess. Left in SHADOW
        // (see TikTok note). KNOWN TRADEOFF: unlike TikTok, Snapchat is NOT mostly-Spotlight —
        // SHADOW will also count Chat/Stories/Map container scrolls as "snaps" (overcount)
        // until a device tour confirms the Spotlight discriminator and this flips to ENFORCED.
        // Acceptable for now: blockEnabled is false, so nothing is enforced on the user.
        surfaceMarkers = listOf("spotlight"),
        gating = GatingMode.SHADOW,
        blockEnabled = false,
        unitNoun = "snap",
        displayName = "Snapchat Spotlight",
    )

    /** Platforms detected today. Instagram is calibrated; the rest are scaffolded (D24). */
    val enabled: List<PlatformSpec> = listOf(instagram, youtube, tiktok, snapchat)

    /** Spec whose package produced this event, or null if it's not a tracked app. */
    fun forPackage(packageName: CharSequence?): PlatformSpec? {
        val pkg = packageName?.toString() ?: return null
        return enabled.firstOrNull { it.packageName == pkg }
    }

    /**
     * The tracked [Platform] this event's package belongs to, or null if untracked.
     *
     * This is the spec's `detectPlatform(node, packageName)` in the codebase's own
     * vocabulary: package identity is the authoritative, cheap platform signal. [node]
     * is accepted for parity with the spec and future content heuristics (e.g. if a
     * package hosts more than one detectable surface) — unused today.
     */
    @Suppress("UNUSED_PARAMETER")
    fun detectPlatform(node: AccessibilityNodeInfo?, packageName: CharSequence?): Platform? =
        forPackage(packageName)?.platform

    /** Spec for a known [Platform]. Every enabled platform has exactly one spec. */
    fun specFor(platform: Platform): PlatformSpec =
        enabled.first { it.platform == platform }

    /** Tracked packages. Kept for diagnostics and any future re-scoping. */
    val packageNames: List<String> get() = enabled.map { it.packageName }
}

/**
 * Decides whether a node tree is currently showing a platform's *doom surface*
 * (its Reels/Shorts viewer) using [PlatformSpec.surfaceMarkers]. This is the single
 * predicate behind "user is doomscrolling right now"; how its result gates counting
 * depends on [PlatformSpec.gating] (the service applies the mode, not this object).
 *
 * Pass-through when a spec has no markers yet (pre-evidence): returns `true` so the
 * app keeps its prior behaviour until markers are filled in.
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
        if (spec.surfaceMarkers.isEmpty()) return true   // no markers → not gating yet
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
     * the viewer without scrolling. Bounded DFS; NOT run per content-change.
     *
     * This is the spec's `isSurfaceCountable(rootNode, platform)`.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun windowMatchesSurface(root: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean {
        if (spec.surfaceMarkers.isEmpty()) return true   // no markers → not gating yet
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

    /** Alias matching the Phase-2 spec's name; delegates to [windowMatchesSurface]. */
    fun isSurfaceCountable(root: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean =
        windowMatchesSurface(root, spec)

    /** Alias for the spec's `findResourceIdInHierarchy`: does [node]'s ancestry contain a marker? */
    fun findResourceIdInHierarchy(node: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean =
        matchesSurface(node, spec)

    /**
     * The actual `viewIdResourceName` (self or nearest ancestor) that matched a marker, or
     * null. Same ancestry walk as [matchesSurface] — this is purely for the DEBUG decision
     * log ("which container triggered it"), so we can name the id we keyed on.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun matchedMarker(node: AccessibilityNodeInfo?, spec: PlatformSpec): String? {
        if (node == null || spec.surfaceMarkers.isEmpty()) return null
        firstMatchingId(node.viewIdResourceName, spec)?.let { return it }

        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTORS) {
            val hit = firstMatchingId(current.viewIdResourceName, spec)
            val parent = current.parent
            current.recycle()
            if (hit != null) return hit
            current = parent
            depth++
        }
        return null
    }

    /**
     * Pure string-level predicate: the first id in [ids] that contains one of [spec]'s
     * markers, or null. Android-free so the surface-discrimination logic (Reels vs Home vs
     * Stories, Shorts vs feed) can be unit-tested off-device with representative id lists.
     */
    fun matchedMarkerId(ids: List<String>, spec: PlatformSpec): String? =
        ids.firstOrNull { id -> spec.surfaceMarkers.any { id.contains(it) } }

    private fun firstMatchingId(id: CharSequence?, spec: PlatformSpec): String? {
        val value = id?.toString() ?: return null
        return if (spec.surfaceMarkers.any { value.contains(it) }) value else null
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
