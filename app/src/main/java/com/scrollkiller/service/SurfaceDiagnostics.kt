package com.scrollkiller.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.scrollkiller.BuildConfig

/**
 * DEBUG-only accessibility node-tree dumping used to find a *surface discriminator*
 * — the resource-id that distinguishes Instagram's Reels viewer from the feed,
 * stories, profile, etc. (see [PlatformSpec.surfaceMarkers]).
 *
 * WHY THIS EXISTS (and must not be deleted again): we periodically need to read the
 * live view tree of a shipping third-party build to calibrate detection (D11/D15).
 * Deleting these each time means re-writing them each time. They now live here,
 * permanently, gated behind [BuildConfig.DEBUG] so release builds compile them out
 * entirely — zero cost and zero privacy surface in production.
 *
 * Privacy: we log only structural metadata — class names and `viewIdResourceName`
 * (developer-assigned view ids, never user content/text). Same data the detector
 * already reads (invariant: no captions/text ever leaves the node inspection).
 *
 * Filter the tour with: `adb logcat -s ScrollKiller` — every line is prefixed `DIAG`.
 */
@Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle() is correct on API 26–32; a no-op on 33+.
internal object SurfaceDiagnostics {

    /** Safety cap so a pathological tree can't spin the ancestor walk. */
    private const val MAX_ANCESTORS = 30

    /**
     * Dump a SCROLLED event's source node and its full ancestor chain. This is the
     * primary tool: the reel container almost certainly sits under an ancestor whose
     * `viewIdResourceName` names the surface (e.g. `..:id/clips_viewer_view_pager`).
     */
    fun logScrolled(event: AccessibilityEvent) {
        if (!BuildConfig.DEBUG) return
        val source = event.source
        Log.d(
            TAG,
            "DIAG SCROLLED pkg=${event.packageName} class=${event.className} " +
                "srcId=${source?.viewIdResourceName}",
        )
        walkAncestors(source)
        source?.recycle()
    }

    /**
     * Dump a WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED event: the window/root
     * class plus any resource-id on the root and the event source. Entering the
     * Reels surface within IG shows up here as a root/content change.
     */
    fun logWindowChange(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        if (!BuildConfig.DEBUG) return
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
            else -> "WINDOW_?"
        }
        val source = event.source
        Log.d(
            TAG,
            "DIAG $kind pkg=${event.packageName} evtClass=${event.className} " +
                "rootClass=${root?.className} rootId=${root?.viewIdResourceName} " +
                "srcId=${source?.viewIdResourceName}",
        )
        source?.recycle()
    }

    /**
     * Walk from [start] up the parent chain, logging each node's id + class. Each
     * `.parent` allocates a node we own, so recycle as we go (correct on API 26–32;
     * a harmless no-op on 33+).
     */
    private fun walkAncestors(start: AccessibilityNodeInfo?) {
        var node = start?.parent
        var depth = 0
        while (node != null && depth < MAX_ANCESTORS) {
            Log.d(TAG, "DIAG   ancestor[$depth] id=${node.viewIdResourceName} class=${node.className}")
            val parent = node.parent
            node.recycle()
            node = parent
            depth++
        }
    }

    /** Same tag as the detector so one filter catches everything. */
    private const val TAG = "ScrollKiller"
}
