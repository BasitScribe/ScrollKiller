package com.scrollkiller

import com.scrollkiller.stats.Milestone
import com.scrollkiller.stats.MilestoneId
import com.scrollkiller.stats.Milestones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Milestone derivation (D83).
 *
 * The property worth guarding hardest is in [a fresh install earns nothing]: the walk starts at the
 * FIRST DAY WITH DATA, not at some epoch. Starting earlier would hand every new user a
 * thousand-day streak of days before they installed the app, which is the one way this feature
 * could be actively dishonest rather than merely wrong.
 */
class MilestonesTest {

    private val limit = 100
    private val today: LocalDate = LocalDate.of(2026, 7, 29)

    /** `totals[i]` is the day `n-1-i` days before today; the last entry is today. */
    private fun days(vararg totals: Int): Map<LocalDate, Int> {
        val start = today.minusDays((totals.size - 1).toLong())
        return totals.withIndex().associate { (i, t) -> start.plusDays(i.toLong()) to t }
    }

    private fun of(totals: Map<LocalDate, Int>) = Milestones.of(totals, limit, today)

    private fun List<Milestone>.byId(id: MilestoneId) = single { it.id == id }

    @Test
    fun `a fresh install earns nothing`() {
        // No rows at all. If the walk began at an epoch instead of the first day with data, every
        // absent day would count as "under" and this would report a streak of centuries.
        val milestones = of(emptyMap())
        assertEquals(MilestoneId.entries.size, milestones.size)
        assertTrue(milestones.none { it.achieved })
        assertTrue(milestones.all { it.progress == 0 })
    }

    @Test
    fun `one day under earns the first milestone and nothing else`() {
        val milestones = of(days(10))
        assertTrue(milestones.byId(MilestoneId.FIRST_DAY_UNDER).achieved)
        assertFalse(milestones.byId(MilestoneId.STREAK_3).achieved)
        assertEquals(1, Milestones.earnedCount(milestones))
    }

    @Test
    fun `a day AT the limit is not under, matching BlockPolicy`() {
        // The block fires at count >= limit, so the day you hit exactly 100 is the day you were
        // blocked. Awarding a milestone for it would contradict what the user experienced.
        assertFalse(of(days(limit)).byId(MilestoneId.FIRST_DAY_UNDER).achieved)
        assertTrue(of(days(limit - 1)).byId(MilestoneId.FIRST_DAY_UNDER).achieved)
    }

    @Test
    fun `streak milestones unlock in order`() {
        val milestones = of(days(1, 1, 1))
        assertTrue(milestones.byId(MilestoneId.STREAK_3).achieved)
        assertFalse(milestones.byId(MilestoneId.STREAK_7).achieved)
        assertEquals("progress toward the next one is shown, not a failure", 3, milestones.byId(MilestoneId.STREAK_7).progress)
    }

    @Test
    fun `a broken streak keeps the best run, so an earned milestone is not taken away`() {
        // Seven good days, then a blowout. The "quiet week" was genuinely achieved and must stay.
        val milestones = of(days(1, 1, 1, 1, 1, 1, 1, 999))
        assertTrue(milestones.byId(MilestoneId.STREAK_7).achieved)
        assertFalse(milestones.byId(MilestoneId.STREAK_14).achieved)
    }

    @Test
    fun `cumulative milestones count non-consecutive days`() {
        // Ten good days broken up by bad ones still earns "ten good days" — that is the difference
        // between the cumulative family and the streak family.
        val pattern = IntArray(20) { if (it % 2 == 0) 1 else 999 }
        val milestones = of(days(*pattern))
        assertTrue(milestones.byId(MilestoneId.TOTAL_10).achieved)
        assertFalse("only 10 good days, not 50", milestones.byId(MilestoneId.TOTAL_50).achieved)
        assertFalse("never three in a row", milestones.byId(MilestoneId.STREAK_3).achieved)
    }

    @Test
    fun `a gap inside the history counts as under, same rule as streaks`() {
        // A lazily-created row means zero scrolls. Days 2 and 3 are absent entirely.
        val sparse = mapOf(
            today.minusDays(3) to 1,
            today to 1,
        )
        val milestones = Milestones.of(sparse, limit, today)
        assertTrue("four days spanned, all under", milestones.byId(MilestoneId.STREAK_3).achieved)
    }

    @Test
    fun `progress never exceeds its target`() {
        val milestones = of(days(*IntArray(200) { 1 }))
        milestones.forEach {
            assertTrue("${it.id} progress ${it.progress} > target ${it.target}", it.progress <= it.target)
        }
    }

    @Test
    fun `everything is achievable and everything is achieved with enough good days`() {
        val milestones = of(days(*IntArray(60) { 1 }))
        assertEquals(MilestoneId.entries.size, Milestones.earnedCount(milestones))
    }

    @Test
    fun `future-dated rows cannot earn anything`() {
        // A clock change or a restored backup can leave a row ahead of today. It must not award a
        // milestone the user has not lived.
        val futureOnly = mapOf(today.plusDays(5) to 1)
        assertTrue(Milestones.of(futureOnly, limit, today).none { it.achieved })
    }

    @Test
    fun `milestones are a VIEW, so clearing history clears them (D81)`() {
        // Nothing is stored, so there is no badge to survive a wipe. That is deliberate: a
        // persisted award could become arithmetically false when Phase 3 moves the day boundary.
        val earned = of(days(*IntArray(30) { 1 }))
        assertTrue(Milestones.earnedCount(earned) > 0)
        assertEquals(0, Milestones.earnedCount(of(emptyMap())))
    }

    @Test
    fun `raising the limit can retroactively earn a milestone, and that is correct`() {
        // The milestone means "you stayed under YOUR limit". If the user raises it, past days that
        // were over are now under, and the derived answer changes with them. A stored badge could
        // not express that; this is the upside of deriving.
        val history = days(50, 50, 50)
        assertFalse(Milestones.of(history, limit = 40, today = today).byId(MilestoneId.STREAK_3).achieved)
        assertTrue(Milestones.of(history, limit = 60, today = today).byId(MilestoneId.STREAK_3).achieved)
    }
}
