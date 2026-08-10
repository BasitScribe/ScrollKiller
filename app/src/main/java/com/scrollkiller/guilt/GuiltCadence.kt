package com.scrollkiller.guilt

/**
 * HOW OFTEN a guilt line fires, as a function of today's total count. The companion to
 * [GuiltTier], which decides how HARD it hits — two independent axes, deliberately (D46):
 *
 *  - [GuiltTier] → which POOL the line is drawn from. Unchanged by this file.
 *  - [GuiltCadence] → how many scrolls apart lines are. Unchanged by that one.
 *
 * Keeping them separate is what makes "the app gets meaner" and "the app gets more insistent"
 * tunable apart. They still compound — at 800 reels the lines are the most savage the pack has
 * AND arriving as often as the curve ever delivers them — but the "more insistent" axis now tops
 * out at [FLOOR_SCROLLS] rather than climbing forever. See D48 and [GuiltPoolMath].
 *
 * Pure Kotlin, no Android imports.
 */
object GuiltCadence {

    /**
     * One row of the schedule: from [fromCount] onward, a line is eligible to fire every
     * [everyScrolls] scrolls.
     */
    data class Step(val fromCount: Int, val everyScrolls: Int)

    /**
     * The tightest the cadence is ever allowed to get: one line per this many scrolls, at any
     * count, forever. The schedule's last row sits exactly here and nothing may go below it.
     *
     * ## Why the curve plateaus instead of continuing to tighten (D48)
     * The original schedule kept tightening to every 2 scrolls at 600 and every scroll at 800.
     * [GuiltPoolMath] showed what that actually costs: a 800/day user consumes 196 tier-4 lines
     * a DAY, so the 7-day no-repeat rule (D47) would need ~1,372 written lines in that tier
     * alone. That is not a content backlog, it is a content impossibility — and the failure mode
     * is not a crash but the least-recently-shown fallback quietly repeating itself, which is the
     * thing D47 exists to prevent.
     *
     * The honest fix is to stop asking for content nobody can write. At every 5 scrolls the app
     * is still relentless at the top of the curve — the display gap already caps the *shown* rate
     * at ~11 lines a minute regardless — but the bill it sends the pack is one a realistic pack
     * can pay. This is the lever D47 named and deferred; it is now taken.
     */
    const val FLOOR_SCROLLS = 5

    /**
     * THE table. Editing the product's nagging curve means editing these three lines and
     * nothing else — no call site reads a threshold or an interval directly.
     *
     * Declared ASCENDING because that is how the schedule is discussed and reviewed
     * ("from 100, every 10"); the descending scan [intervalFor] needs is derived below rather
     * than being a second ordering somebody has to keep in step. Same shape as [GuiltTier].
     *
     * Below [SCHEDULE]'s first row there is no repeating cadence at all — see [intervalFor].
     * Above its last row there is no further tightening: 320 is where the curve reaches
     * [FLOOR_SCROLLS] and it stays there. The old 400/500/600/800 rows are GONE rather than
     * clamped, because a row whose interval the floor would override is a row that lies to the
     * next reader about what the app does.
     */
    val SCHEDULE: List<Step> = listOf(
        Step(fromCount = 100, everyScrolls = 10),
        Step(fromCount = 250, everyScrolls = 7),
        Step(fromCount = 320, everyScrolls = FLOOR_SCROLLS),
    )

    /** The schedule hardest-first, so [intervalFor] reads as a descending threshold table. */
    private val descending = SCHEDULE.sortedByDescending { it.fromCount }

    /**
     * How many scrolls apart lines are at [count], or NULL when there is no repeating cadence
     * yet — below the first row of [SCHEDULE], lines fire ONLY on a tier crossing (50 and 70).
     *
     * Never returns less than [FLOOR_SCROLLS], at any count. That is a property of the table
     * rather than a clamp here, and [GuiltCadenceTest] asserts it so a future row cannot
     * reintroduce a bill the pack cannot pay.
     *
     * Null rather than a sentinel like `Int.MAX_VALUE`: "this count does not repeat" is the
     * absence of an interval, and a huge number would silently become a real cadence the day
     * someone's count got large enough.
     */
    fun intervalFor(count: Int): Int? =
        descending.firstOrNull { count >= it.fromCount }?.everyScrolls

    /**
     * How long a fired line stays on the bubble before it collapses back to the count.
     *
     * Lives here rather than in the overlay because it is one half of [MIN_GAP_MS] — split
     * across two files, the two numbers drift and the guarantee below quietly stops holding.
     *
     * ## Why 6.5s and not the original 4s (D83)
     * Five days of real use said the same thing every day: the line collapses back to the count
     * before it has been finished. That is not a taste complaint, it is the feature failing —
     * an unread line is a line that was never shown, and the pack, the tiers, the cadence and
     * the no-repeat rotation all exist to put a line in front of someone who then reads it.
     *
     * Four seconds is fine for "you've scrolled 200" and much too short for the ones that carry
     * the product's voice, which are the longer ones and the ones worth reading. Six and a half
     * covers the pack's longest lines at an unhurried reading pace with room to look up at the
     * bubble first — the line is not the thing the user was looking at when it appeared.
     *
     * Raising it costs frequency and nothing else: [MIN_GAP_MS] is derived from it, so the
     * practical ceiling falls from ~11 lines a minute to ~7.5. That is still relentless at the
     * top of the curve, and seven lines that get read beat eleven that do not.
     */
    const val DISPLAY_MS = 6_500L

    /**
     * The window after a line collapses during which the bubble is a COUNTER and nothing else.
     */
    const val COUNT_VISIBLE_MS = 1_500L

    /**
     * Minimum wall time between two SHOWN lines. The hard ceiling on the cadence.
     *
     * ## Why this is 8s and not the few seconds the schedule implies
     * The schedule governs ELIGIBILITY, in scrolls. This governs DISPLAY, in seconds. They are
     * different units and at the top of the curve they still disagree even after D48 flattened
     * it: at [FLOOR_SCROLLS] a fast scroller is eligible roughly every 1.5s.
     *
     * A gap shorter than [DISPLAY_MS] would mean each line is overwritten by the next before it
     * has finished being read — so at the exact moment the app is trying hardest to be heard,
     * nothing on screen would be legible. Worse, the pill would never collapse, and the bubble
     * would stop being a counter at all. The counter is the product; the lines are commentary on
     * it, and commentary must not evict the thing it is commenting on.
     *
     * So the gap is the full display plus a guaranteed counter-only window: every line gets its
     * whole readable life, and the user always gets the number back before the next one. At the
     * top of the curve that caps the practical rate at ~7.5 lines a minute, which is already
     * relentless — and is why flattening the schedule to [FLOOR_SCROLLS] costs the product far
     * less than it costs the content bill.
     *
     * Derived from its two parts rather than written as a literal, so the guarantee survives
     * anyone re-tuning [DISPLAY_MS] — which D83 then did, taking the display from 4s to 6.5s and
     * this gap from 5.5s to 8s with no edit here and no call site to chase. That is the whole
     * reason it was written this way, and it is the reason to leave it derived.
     */
    const val MIN_GAP_MS = DISPLAY_MS + COUNT_VISIBLE_MS
}
