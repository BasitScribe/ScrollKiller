package com.scrollkiller.service

import android.os.Build
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

    /** Depth/node budget for the EVENT_PULSE identity probe, and how many texts to collect. */
    private const val MAX_IDENTITY_NODES = 60
    private const val MAX_IDENTITY_TEXTS = 4

    /**
     * EVENT_PULSE VERIFICATION DUMP (DEBUG only). For a scroll we've ALREADY matched to a
     * platform's doom surface, dump the `AccessibilityEvent` fields the compact [logScroll] line
     * drops. Original goal (find a direction/position discriminator) is CLOSED: YouTube Shorts
     * reports every field empty/sentinel on every `reel_recycler` event (from=-1 to=-1
     * itemCount=-1 scroll/maxScroll=0 deltaX/Y=0, no src text) — YT exposes NO direction in
     * `TYPE_VIEW_SCROLLED`. So YT can't use IG's DELTA_Y_FORWARD; the fallback is EVENT_PULSE
     * (count one advance per marker-matched scroll event, time-debounced) since YT fires ~1 event
     * per swipe, not IG's 8–10-event burst.
     *
     * This dump now VERIFIES the pulse assumption before we ship it: it prints `identity=` — up to
     * [MAX_IDENTITY_TEXTS] non-blank text/contentDescription strings from the scrolled subtree (a
     * candidate per-short identity: channel handle / caption). Reading the log across a
     * swipe→idle→tap capture answers the two questions that decide the strategy:
     *   1. Do `YTFIELDS` lines appear while NOT swiping (video looping, like/comment taps)? If yes,
     *      EVENT_PULSE alone would overcount idle playback.
     *   2. Does `identity=` CHANGE between swipes but stay CONSTANT during idle? If yes, a
     *      content-change cross-check (count only when identity changes) fixes the overcount.
     *
     * `getScrollDelta{X,Y}()` are API 28+; guarded (`n/a` on 26/27). We print node text ONLY here:
     * a temporary, developer-run, DEBUG-compiled-out discovery tool on the operator's own device —
     * it never ships (release strips it) and the release detector still reads structural metadata
     * only. Do NOT wire these text fields into shipping detection logic verbatim; if the content
     * cross-check ships, it keys on a specific verified viewId, not this broad text scan.
     * [source] is owned by the caller and NOT recycled here.
     */
    fun logScrollFields(event: AccessibilityEvent, source: AccessibilityNodeInfo?) {
        if (!BuildConfig.DEBUG) return
        val delta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "deltaX=${event.scrollDeltaX} deltaY=${event.scrollDeltaY}"
        } else {
            "deltaX=n/a deltaY=n/a"
        }
        Log.d(
            TAG,
            "DIAG YTFIELDS from=${event.fromIndex} to=${event.toIndex} itemCount=${event.itemCount} " +
                "scrollX=${event.scrollX} scrollY=${event.scrollY} " +
                "maxScrollX=${event.maxScrollX} maxScrollY=${event.maxScrollY} $delta " +
                "srcDesc=\"${trim(source?.contentDescription)}\" srcText=\"${trim(source?.text)}\" " +
                "identity=\"${collectSurfaceText(source)}\"",
        )
    }

    /**
     * Bounded DFS from [source] collecting up to [MAX_IDENTITY_TEXTS] non-blank text/description
     * strings — a candidate per-short identity for the EVENT_PULSE-vs-content-change decision.
     * Children we allocate are recycled; [source] is the caller's and is NOT recycled here.
     */
    private fun collectSurfaceText(source: AccessibilityNodeInfo?): String {
        if (source == null) return "—"
        val out = ArrayList<String>(MAX_IDENTITY_TEXTS)
        collectText(source, intArrayOf(MAX_IDENTITY_NODES), depth = 0, out = out)
        return if (out.isEmpty()) "—" else out.joinToString(" | ")
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        budget: IntArray,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (budget[0] <= 0 || depth > MAX_DEPTH || out.size >= MAX_IDENTITY_TEXTS) return
        budget[0]--
        val label = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString()?.takeIf { it.isNotBlank() }
        if (label != null) out.add(trim(label))
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0 || out.size >= MAX_IDENTITY_TEXTS) break
            val child = node.getChild(i) ?: continue
            collectText(child, budget, depth + 1, out)
            child.recycle()
        }
    }

    /** Truncate a CharSequence for the discovery dump so a long caption can't flood one line. */
    private fun trim(cs: CharSequence?): String {
        val s = cs?.toString() ?: return "—"
        return if (s.length > 40) s.take(40) + "…" else s
    }

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
