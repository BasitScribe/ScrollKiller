package com.scrollkiller

import com.scrollkiller.data.TodaySummary
import com.scrollkiller.service.BubbleBreakdown
import com.scrollkiller.service.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the bubble's expanded breakdown bars (D37). Pure, so it is testable
 * off-device — which is the point of keeping [BubbleBreakdown] Android-free.
 */
class BubbleBreakdownTest {

    private fun summary(vararg counts: Pair<Platform, Int>) =
        TodaySummary(total = counts.sumOf { it.second }, perPlatform = counts.toMap())

    @Test
    fun `bars are proportional to share of the total`() {
        val bars = BubbleBreakdown.bars(
            summary(Platform.INSTAGRAM to 30, Platform.YOUTUBE to 10),
        )

        assertEquals(2, bars.size)
        assertEquals(0.75f, bars[0].share, 0.0001f)
        assertEquals(0.25f, bars[1].share, 0.0001f)
    }

    @Test
    fun `shares sum to one so the bars account for the whole total`() {
        val bars = BubbleBreakdown.bars(
            summary(Platform.INSTAGRAM to 7, Platform.YOUTUBE to 11, Platform.SNAPCHAT to 3),
        )

        assertEquals(1f, bars.sumOf { it.share.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `longest bar first`() {
        val bars = BubbleBreakdown.bars(
            summary(Platform.INSTAGRAM to 5, Platform.YOUTUBE to 40, Platform.TIKTOK to 20),
        )

        assertEquals(listOf(Platform.YOUTUBE, Platform.TIKTOK, Platform.INSTAGRAM), bars.map { it.platform })
    }

    @Test
    fun `platforms with no activity today get no row`() {
        val bars = BubbleBreakdown.bars(summary(Platform.INSTAGRAM to 12))

        assertEquals(listOf(Platform.INSTAGRAM), bars.map { it.platform })
        assertEquals(1f, bars.single().share, 0.0001f)
    }

    @Test
    fun `a zero row is dropped, not drawn as an empty bar`() {
        // daily_counts rows outlive their counts (a cleared day leaves a 0), and TodaySummary
        // passes whatever Room returns straight through — so 0 has to be filtered HERE.
        val bars = BubbleBreakdown.bars(
            summary(Platform.INSTAGRAM to 8, Platform.SNAPCHAT to 0, Platform.TIKTOK to 0),
        )

        assertEquals(listOf(Platform.INSTAGRAM), bars.map { it.platform })
    }

    @Test
    fun `nothing counted today means no bars at all`() {
        assertTrue(BubbleBreakdown.bars(TodaySummary.EMPTY).isEmpty())
        assertTrue(BubbleBreakdown.bars(summary(Platform.INSTAGRAM to 0)).isEmpty())
    }

    @Test
    fun `every bar has a positive count so no row can render as a zero-width bar`() {
        val bars = BubbleBreakdown.bars(
            summary(
                Platform.INSTAGRAM to 3,
                Platform.YOUTUBE to 0,
                Platform.SNAPCHAT to 1,
                Platform.FACEBOOK to 0,
                Platform.TIKTOK to 6,
            ),
        )

        assertTrue(bars.all { it.count > 0 && it.share > 0f })
    }

    @Test
    fun `ties keep a stable order across identical emissions`() {
        // Two platforms on the same count must not swap places between renders, or the panel
        // visibly reshuffles while the numbers stand still.
        val tied = summary(Platform.INSTAGRAM to 10, Platform.YOUTUBE to 10)

        assertEquals(
            BubbleBreakdown.bars(tied).map { it.platform },
            BubbleBreakdown.bars(tied).map { it.platform },
        )
    }

    @Test
    fun `bars never outnumber the platforms so the panel cannot grow unbounded`() {
        val all = summary(*Platform.entries.map { it to 4 }.toTypedArray())

        assertEquals(Platform.entries.size, BubbleBreakdown.bars(all).size)
    }
}
