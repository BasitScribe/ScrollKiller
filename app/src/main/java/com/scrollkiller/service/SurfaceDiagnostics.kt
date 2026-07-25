package com.scrollkiller.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.scrollkiller.BuildConfig

/**
 * DEBUG-only accessibility diagnostics used to find a *surface discriminator* — the
 * resource-id that distinguishes a platform's doom surface (Instagram Reels, YouTube
 * Shorts, …) from the feed, stories, profile, DMs, etc. (see [PlatformSpec.surfaceMarkers]).
 *
 * THE TOUR TRANSCRIPT CONTRACT (2026-07-24): every relevant event produces exactly ONE
 * compact line, all tagged `DIAG`, so `adb logcat -s ScrollKiller` is a clean, greppable
 * transcript with no tree-dump flood between the interesting lines:
 *   - [logScroll]  — one line per `TYPE_VIEW_SCROLLED` from a tracked app.
 *   - [logWindow]  — one line per `TYPE_WINDOW_STATE_CHANGED` (activity/fragment swap).
 *   - [logLabel]   — a banner the tour operator stamps between steps (via an adb broadcast),
 *                    followed by ONE bounded snapshot of the current window tree so a surface
 *                    that never scrolls (e.g. an empty grid) still yields its resource-ids.
 *
 * WHY THIS EXISTS (and must not be deleted again): we periodically need to read the live
 * view tree of a shipping third-party build to calibrate detection (D11/D15/D17/D25).
 * Deleting these each time means re-writing them each time. They live here permanently,
 * gated behind [BuildConfig.DEBUG] so release builds compile them out entirely — zero cost
 * and zero privacy surface in production.
 *
 * Privacy: we log only structural metadata — class names and `viewIdResourceName`
 * (developer-assigned view ids, never user content/text). Same data the detector already
 * reads (invariant: no captions/text ever leaves the node inspection).
 */
@Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle() is correct on API 26–32; a no-op on 33+.
internal object SurfaceDiagnostics {

    /** Safety cap so a pathological tree can't spin the ancestor walk. */
    private const val MAX_ANCESTORS = 30

    /** Depth cap on the per-label downward tree snapshot ([logLabel]) — "don't spam". */
    private const val MAX_DEPTH = 6

    /** Total-node budget across one tree snapshot, so a huge tree can't flood logcat. */
    private const val MAX_NODES = 120

    /** Same tag as the detector so one filter catches everything. */
    private const val TAG = "ScrollKiller"

    /**
     * One compact line for a `TYPE_VIEW_SCROLLED` event. Carries everything the derive step
     * (Step 3) needs to pick a marker: package, scroll direction, the scrolled view's class +
     * id, the SurfaceMatcher verdict (which marker matched, or NO_MATCH), the gating mode,
     * whether this scroll actually counted, why, and the full ancestor id-chain (where the
     * reel/short container id lives — see D15).
     *
     * [source] is owned by the CALLER and NOT recycled here (the caller recycles it once,
     * after this returns). The parent nodes we allocate while walking are recycled as we go.
     */
    fun logScroll(
        spec: PlatformSpec,
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo?,
        direction: ScrollDirection,
        counted: Boolean,
        reason: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val ancestry = walkAncestry(source, spec)
        Log.d(
            TAG,
            "DIAG SCROLL pkg=${event.packageName} dir=$direction " +
                "src=${shortClass(event.className)} srcId=${shortId(source?.viewIdResourceName)} " +
                "verdict=${verdict(spec, ancestry.matchedMarker)} gating=${spec.gating} " +
                "counted=$counted reason=\"$reason\" ancestors=[${ancestry.chain}]",
        )
    }

    /**
     * The YT field dump (`DIAG YTFIELDS`) that used to live here MOVED to [YtProbe], which prints
     * a superset of it (event type, eventTime, the branch actually taken, and the running
     * seen/counted counters) for all three YouTube event types rather than scrolls only. Keeping
     * both would be two probes of the same thing, free to drift apart.
     */

    /**
     * One compact line for a `TYPE_WINDOW_STATE_CHANGED` event (the activity/fragment swap
     * that carries you between surfaces). Reports whether the app is tracked, its gating,
     * the active-window root id, the window-level surface verdict (a bounded root scan), and
     * the source node's ancestor id-chain. [root] is owned by the caller; [event]'s source
     * IS recycled here (the caller doesn't use it after dispatch).
     */
    fun logWindow(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        if (!BuildConfig.DEBUG) return
        val spec = PlatformRegistry.forPackage(event.packageName)
        val source = event.source
        val ancestry = walkAncestry(source, spec)
        source?.recycle()

        val windowVerdict = when {
            spec == null -> "UNTRACKED"
            spec.surfaceMarkers.isEmpty() -> "PASSTHROUGH"
            SurfaceMatcher.windowMatchesSurface(root, spec) -> "ON_SURFACE"
            else -> "OFF_SURFACE"
        }
        Log.d(
            TAG,
            "DIAG WINDOW pkg=${event.packageName} activity=${shortClass(event.className)} " +
                "tracked=${spec != null} gating=${spec?.gating ?: "—"} " +
                "rootId=${shortId(root?.viewIdResourceName)} verdict=$windowVerdict " +
                "srcId=${shortId(source?.viewIdResourceName)} ancestors=[${ancestry.chain}]",
        )
    }

    /**
     * Stamp the transcript with a tour [label] so surfaces are unambiguously separated, then
     * dump ONE bounded snapshot of the current window tree (the [spec]'s markers are flagged
     * inline). This is what makes a non-scrolling surface (an empty profile grid, a DM list)
     * still reveal its resource-ids. Triggered by the debug broadcast receiver in the service.
     * [root] is owned by the caller.
     */
    fun logLabel(label: String?, root: AccessibilityNodeInfo?, spec: PlatformSpec?) {
        if (!BuildConfig.DEBUG) return
        val name = label?.takeIf { it.isNotBlank() } ?: "(unlabeled)"
        Log.d(TAG, "DIAG ===================== LABEL: $name (pkg=${root?.packageName ?: "?"}) =====================")
        if (root == null) {
            Log.d(TAG, "DIAG   (no active-window root to snapshot)")
            return
        }
        dumpTree(root, depth = 0, budget = intArrayOf(MAX_NODES), spec = spec)
    }

    // --- internals ---------------------------------------------------------------------

    /** Result of one upward walk: the joined id-chain and the first marker it matched. */
    private class Ancestry(val chain: String, val matchedMarker: String?)

    /**
     * Walk from [source] up the parent chain (bounded), collecting each node's short id into a
     * `a > b > c` chain and noting the first `viewIdResourceName` that contains a [spec] marker
     * (the same substring rule [SurfaceMatcher] uses). [source] is NOT recycled here; every
     * parent we allocate is recycled as we ascend.
     */
    private fun walkAncestry(source: AccessibilityNodeInfo?, spec: PlatformSpec?): Ancestry {
        if (source == null) return Ancestry("—", null)
        val chain = StringBuilder(shortId(source.viewIdResourceName))
        var matched = matchMarker(source.viewIdResourceName, spec)

        var node = source.parent
        var depth = 0
        while (node != null && depth < MAX_ANCESTORS) {
            chain.append(" > ").append(shortId(node.viewIdResourceName))
            if (matched == null) matched = matchMarker(node.viewIdResourceName, spec)
            val parent = node.parent
            node.recycle()
            node = parent
            depth++
        }
        return Ancestry(chain.toString(), matched)
    }

    /** The first marker substring in [id], or null. Mirrors [SurfaceMatcher]'s contains() rule. */
    private fun matchMarker(id: CharSequence?, spec: PlatformSpec?): String? {
        val value = id?.toString() ?: return null
        if (spec == null) return null
        return spec.surfaceMarkers.firstOrNull { value.contains(it) }
    }

    private fun verdict(spec: PlatformSpec, matchedMarker: String?): String = when {
        spec.surfaceMarkers.isEmpty() -> "PASSTHROUGH(no markers)"
        matchedMarker != null -> "MATCH($matchedMarker)"
        else -> "NO_MATCH"
    }

    /**
     * Recursive downward snapshot for [logLabel]. [node]/ancestors are owned by the caller and
     * NOT recycled here; every child we allocate via [AccessibilityNodeInfo.getChild] is
     * recycled after we descend into it. [budget] is a single-cell counter shared across the walk.
     */
    private fun dumpTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray,
        spec: PlatformSpec?,
    ) {
        if (depth > MAX_DEPTH || budget[0] <= 0) return
        budget[0]--

        val id = node.viewIdResourceName
        val flag = if (matchMarker(id, spec) != null) "  ← MATCHES surfaceMarker" else ""
        val indent = "  ".repeat(depth)
        Log.d(TAG, "DIAG   $indent[$depth] ${shortClass(node.className)} id=${shortId(id)}$flag")

        for (i in 0 until node.childCount) {
            if (budget[0] <= 0) break
            val child = node.getChild(i) ?: continue
            dumpTree(child, depth + 1, budget, spec)
            child.recycle()
        }
    }

    private fun shortClass(className: CharSequence?): String =
        className?.toString()?.substringAfterLast('.') ?: "?"

    private fun shortId(id: CharSequence?): String =
        id?.toString()?.substringAfterLast('/') ?: "—"
}
