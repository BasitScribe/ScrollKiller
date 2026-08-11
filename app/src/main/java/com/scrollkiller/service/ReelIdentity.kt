package com.scrollkiller.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts a stable PER-ITEM identity from the node tree of an
 * [AdvanceStrategy.IDENTITY_CHANGE] platform, so [IdentityAdvanceDetector] can count an
 * advance when it changes. Companion to [SurfaceMatcher] — that answers "are we on the doom
 * surface", this answers "which item are we looking at".
 *
 * ## Why it is deliberately STRICT (D34)
 * The Shorts player fires ~40 `TYPE_WINDOW_CONTENT_CHANGED` events per Short. Almost all of
 * them carry text: rolling subtitles, `like this video along with 79K other people`,
 * `Auto-dubbed`. If any of that became "the identity", it would change constantly WITHOUT the
 * user swiping — producing exactly the idle-overcount the identity check exists to prevent.
 *
 * So an identity is only ever taken from provably per-item sources:
 *  1. a CHANNEL HANDLE (`@SagarsKitchen`) — the capture showed this on the player container's
 *     `contentDescription`, changing exactly once per advance; and
 *  2. the text of a node whose id is in [PlatformSpec.identityTitleHints] — an explicit
 *     ALLOWLIST, never "whatever text we found".
 * Anything else yields `null`, which the detector treats as "no information" and ignores.
 * A wrong guess here therefore UNDERcounts, never overcounts.
 *
 * ⚑ **BOTH are collected, not one-or-the-other.** This used to be handle-first-else-title, and
 * that is exactly how a channel's own Shorts tab came to count ONE item per session: every Short
 * there shares a handle, the title was never consulted because a handle existed, and so every
 * item after the first scored `UNCHANGED`. D34's capture had recorded the title alongside the
 * handle all along; the extraction was throwing it away. They now travel together as an
 * [ItemIdentity], which is what lets "same creator, different Short" be expressed at all.
 *
 * Only the HANDLE TOKEN is kept, not the whole string: a description like
 * `@SomeChannel · 1.2M subscribers` would otherwise change identity every time the subscriber
 * count ticked, re-introducing the flap through the back door.
 *
 * ## Cost
 * Every walk is bounded ([MAX_ANCESTORS], [MAX_SCAN_NODES], [MAX_DEPTH]) and recycles the
 * nodes it allocates; nodes passed IN are owned by the caller. This runs in RELEASE, on the
 * accessibility main thread, so the caller ALSO rate-limits how often it is invoked — see
 * `ReelScrollAccessibilityService.IDENTITY_SCAN_MIN_MS`. Bounded work at a bounded rate.
 *
 * Privacy: this is the one place shipping detection reads node text, and it reads it to
 * derive an opaque per-item key that is compared to the previous one and then discarded. No
 * identity is ever stored ([com.scrollkiller.data.db.ScrollEvent] records counts and package
 * names only) and none is ever logged outside DEBUG. Invariant #2 (no content leaves the
 * device) is untouched.
 */
object ReelIdentity {

    /** Depth cap on the upward walk looking for the anchor container. */
    private const val MAX_ANCESTORS = 30

    /** Node-count cap on the downward scan from the anchor. */
    private const val MAX_SCAN_NODES = 40

    /** Depth cap on the downward scan from the anchor. */
    private const val MAX_DEPTH = 5

    /** Stop collecting once we have this many text candidates — the handle is always early. */
    private const val MAX_CANDIDATES = 24

    /** Longest title fallback we keep, so a pathological caption can't hold a big string. */
    private const val MAX_TITLE_LENGTH = 80

    /**
     * A channel handle at the START of a string. Anchored on purpose: it must match
     * `@SagarsKitchen` and `@SomeChannel · 1.2M subscribers` (capturing only `@SomeChannel`),
     * but NOT a subtitle that happens to mention someone mid-sentence.
     */
    private val HANDLE = Regex("^@[A-Za-z0-9._-]{2,30}")

    /** Collapses runs of whitespace so a re-wrapped title isn't read as a different one. */
    private val WHITESPACE = Regex("\\s+")

    /** One text string found in the tree, tagged with the short id of the node carrying it. */
    data class Candidate(val shortId: String?, val text: String)

    /**
     * The identity of the item [source]'s event belongs to, or null when nothing provably
     * per-item was found (the caller must then IGNORE the event, not treat it as a change).
     *
     * [source] is owned by the CALLER and is not recycled here.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun identityOf(source: AccessibilityNodeInfo?, spec: PlatformSpec): ItemIdentity? {
        if (source == null || spec.identityAnchors.isEmpty()) return null
        val anchor = findAnchor(source, spec) ?: return null
        return try {
            pick(collectCandidates(anchor), spec)
        } finally {
            // findAnchor returns either `source` itself (caller's — leave it) or a parent it
            // allocated (ours to release).
            if (anchor !== source) anchor.recycle()
        }
    }

    /**
     * Pure decision: what is this item's identity? Android-free so the rule that separates
     * "@SagarsKitchen" from "Auto-dubbed" is unit-tested against the literal strings from the
     * device capture rather than reasoned about.
     *
     * ⚑ Collects BOTH fields rather than returning the first one found. The handle is still the
     * proven, load-bearing signal and the title ids are still an unverified allowlist — but a
     * title that is only read when no handle exists is a title that is never read, since the
     * handle is present on virtually every frame. Two Shorts by one creator were therefore
     * indistinguishable. The title's lower confidence is handled by
     * [ItemIdentity.differsFrom], which only ever compares fields present on both sides, so an
     * absent or unrecognised title costs nothing and cannot manufacture an advance.
     *
     * Returns null when neither field was found — "no information", which the detector ignores.
     */
    fun pick(candidates: List<Candidate>, spec: PlatformSpec): ItemIdentity? {
        val handle = candidates.firstNotNullOfOrNull { handleIn(it.text) }
        val title = if (spec.identityTitleHints.isEmpty()) null else {
            candidates.firstNotNullOfOrNull { candidate ->
                val id = candidate.shortId ?: return@firstNotNullOfOrNull null
                if (spec.identityTitleHints.none { id.contains(it) }) null
                else normalizeTitle(candidate.text)
            }
        }
        return if (handle == null && title == null) null else ItemIdentity(handle, title)
    }

    /** The leading `@handle` token of [text], or null. See [HANDLE] for why it's anchored. */
    private fun handleIn(text: String): String? = HANDLE.find(text.trim())?.value

    /**
     * A title-hint candidate as an identity field: whitespace-collapsed and length-capped, so
     * trivial re-wrapping or a huge caption doesn't read as a different item.
     *
     * The old `t:` prefix is gone. It existed so a title could never be mistaken for a handle
     * back when both shared one string field; now they are separate fields and
     * [ItemIdentity.differsFrom] never compares one against the other, so the separation is
     * structural. A prefix carrying a guarantee the type already makes is just a string somebody
     * will eventually strip while tidying up.
     */
    private fun normalizeTitle(text: String): String? {
        val collapsed = text.trim().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return null
        return collapsed.take(MAX_TITLE_LENGTH)
    }

    // --- node walks ------------------------------------------------------------------------

    /**
     * [source] itself, or its nearest ancestor, whose `viewIdResourceName` contains an
     * [PlatformSpec.identityAnchors] entry. Walking UP is what makes this stable: whichever of
     * the ~40 per-Short events fired — a subtitle node, a like-count node — they all resolve to
     * the SAME anchor, so they all read the same identity and collapse to zero counts.
     *
     * Returns [source] unchanged (caller's node) or a freshly allocated parent (caller must
     * recycle); every other node allocated during the walk is recycled here.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    private fun findAnchor(source: AccessibilityNodeInfo, spec: PlatformSpec): AccessibilityNodeInfo? {
        if (isAnchor(source.viewIdResourceName, spec)) return source

        var current = source.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTORS) {
            if (isAnchor(current.viewIdResourceName, spec)) return current   // caller recycles
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        current?.recycle()   // depth cap hit with a node still in hand
        return null
    }

    private fun isAnchor(id: CharSequence?, spec: PlatformSpec): Boolean {
        val value = id?.toString() ?: return false
        return spec.identityAnchors.any { value.contains(it) }
    }

    /** Bounded DFS from [anchor] collecting text/description candidates, anchor's own first. */
    private fun collectCandidates(anchor: AccessibilityNodeInfo): List<Candidate> {
        val out = ArrayList<Candidate>(MAX_CANDIDATES)
        collect(anchor, intArrayOf(MAX_SCAN_NODES), depth = 0, out = out)
        return out
    }

    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    private fun collect(
        node: AccessibilityNodeInfo,
        budget: IntArray,
        depth: Int,
        out: MutableList<Candidate>,
    ) {
        if (budget[0] <= 0 || depth > MAX_DEPTH || out.size >= MAX_CANDIDATES) return
        budget[0]--

        val shortId = node.viewIdResourceName?.toString()?.substringAfterLast('/')
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?.let { out.add(Candidate(shortId, it)) }
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?.let { out.add(Candidate(shortId, it)) }

        for (i in 0 until node.childCount) {
            if (budget[0] <= 0 || out.size >= MAX_CANDIDATES) break
            val child = node.getChild(i) ?: continue
            collect(child, budget, depth + 1, out)
            child.recycle()
        }
    }
}
