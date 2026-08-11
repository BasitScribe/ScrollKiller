package com.scrollkiller.service

/**
 * What one item ([AdvanceStrategy.IDENTITY_CHANGE]) is, as far as we can tell from its node tree:
 * a channel [handle], a [title], or both. Either may be absent on any given frame.
 *
 * ## Why this is a pair and not a string (the D34 correction)
 * It used to be ONE string, and the rule was "the handle, or failing that a title". That reads
 * fine and it lost the ability to tell two Shorts apart whenever the same creator appeared twice
 * in a row — the handle was identical, so the second Short was scored `UNCHANGED` and never
 * counted. On a channel's own Shorts tab, where every item shares one handle, an entire session
 * counted **one**.
 *
 * The evidence to do better was already in D34's capture, which recorded the handle "plus the
 * video title". Handle-first-else-title threw the title away on every frame that had a handle,
 * which is nearly all of them. Keeping both fields is what makes "same creator, different Short"
 * expressible at all.
 *
 * ## Absent is not "different" — see [differsFrom]
 * A field this frame did not carry is missing information, never evidence of a new item. That
 * distinction is the whole reason for the pair: YouTube renders the handle and the title at
 * slightly different times, so a naive `"@A|" != "@A|Fixing my bike"` would score the moment the
 * title arrived as an advance and count every Short twice.
 */
data class ItemIdentity(val handle: String? = null, val title: String? = null) {

    /** True when the tree yielded nothing usable — the caller must treat it as no information. */
    val isEmpty: Boolean get() = handle == null && title == null

    /**
     * Is this provably a DIFFERENT item from [other]?
     *
     * Only fields present on BOTH sides are compared, and any one of them differing is enough.
     * Anything else — a field on one side only, no comparable fields at all — is "cannot tell",
     * which resolves to *not* an advance. That keeps the failure direction the same as everywhere
     * else in detection (D24/D27): when we do not know, we UNDERcount.
     */
    fun differsFrom(other: ItemIdentity): Boolean {
        if (handle != null && other.handle != null && handle != other.handle) return true
        if (title != null && other.title != null && title != other.title) return true
        return false
    }

    /**
     * This identity, filled in with anything [later] knew that it did not.
     *
     * Called when [later] was judged to be the SAME item, and it is what makes a late-rendering
     * field usable rather than merely harmless: the first frame of a Short may carry only the
     * handle, the third may add the title. Without merging, the stored identity stays
     * title-less forever and the next Short by that same creator is again indistinguishable —
     * the original bug, one frame later. Merging upgrades what we know about the item we are
     * already on, so the NEXT item has something to differ from.
     *
     * Never overwrites a field we already had, so a title that blanks out mid-play cannot erase
     * the one we counted on.
     */
    fun mergedWith(later: ItemIdentity): ItemIdentity =
        ItemIdentity(handle = handle ?: later.handle, title = title ?: later.title)

    /** Compact DEBUG rendering for the [YtProbe] transcript. Never logged in release. */
    fun describe(): String = "handle=${handle ?: "-"} title=${title ?: "-"}"
}
