package com.scrollkiller.service

import com.scrollkiller.data.TodaySummary

/**
 * One platform's row in the bubble's expanded breakdown panel.
 *
 * @param platform the platform this row is for.
 * @param count today's count for it (always > 0 — see [BubbleBreakdown.bars]).
 * @param share [count] as a fraction of today's grand total, in 0f..1f. This is the bar's
 *   length, so the bars sum to the whole track exactly once — which is the point: the panel
 *   is answering "what is today's total made of", not "which app is worst".
 */
data class BreakdownBar(
    val platform: Platform,
    val count: Int,
    val share: Float,
)

/**
 * Turns the overlay's [TodaySummary] into the rows its expanded panel draws. Pure and
 * Android-free so the arithmetic behind the bars is unit-testable off-device, exactly like
 * [BlockPolicy] and [com.scrollkiller.brain.BrainState].
 *
 * ## Why this is derived, not collected
 * The bars and the headline total come from the SAME [TodaySummary] emission (D35's single
 * collector). Deriving the bars here rather than observing per-platform counts separately is
 * what makes it impossible for the panel to show `IG 30 · YT 10` under a headline of `38` —
 * the shares are computed from the very total that is being displayed.
 */
object BubbleBreakdown {

    /**
     * Today's split as bars, longest first.
     *
     * Only platforms with a count ABOVE ZERO appear: a row for an app the user hasn't
     * opened today is noise, and [TodaySummary.perPlatform] can contain a 0 row once a day's
     * counts are cleared (the aggregate row outlives the count). An empty list means there is
     * nothing to break down — the caller shows no bars at all rather than an empty panel.
     *
     * Ties keep [Platform] declaration order so the rows can't shuffle between two emissions
     * of the same numbers; `sortedByDescending` alone is stable, but only because the input
     * is iterated in enum order first — which is why the enum pass is explicit here rather
     * than relying on map iteration order.
     */
    fun bars(summary: TodaySummary): List<BreakdownBar> {
        val total = summary.total
        if (total <= 0) return emptyList()
        return Platform.entries
            .mapNotNull { platform ->
                val count = summary.countFor(platform)
                if (count <= 0) null else BreakdownBar(platform, count, count.toFloat() / total)
            }
            .sortedByDescending { it.count }
    }
}
