package com.scrollkiller

import com.scrollkiller.data.PendingCounts
import com.scrollkiller.data.TodayMerge
import com.scrollkiller.data.db.DailyCountEntity
import com.scrollkiller.service.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The D39 reconciliation rule: what the user is shown while a detected advance is still on its
 * way to the database.
 *
 * The optimistic path is only safe if this merge holds three properties, and none of them are
 * visible by watching a bubble go up:
 *  1. it never shows LESS than the persisted truth (the count can't flicker backwards),
 *  2. it converges — once a write lands, the merged number equals the persisted one, so the
 *     in-memory map can never drift into being a second, permanently-wrong counter,
 *  3. it lets a count legitimately go DOWN (a data clear, a day rollover) instead of pinning
 *     the old total in memory forever.
 */
class TodayMergeTest {

    private val today = "2026-07-25"
    private val yesterday = "2026-07-24"

    private fun rows(vararg pairs: Pair<Platform, Int>) =
        pairs.map { (platform, count) -> DailyCountEntity(today, platform.id, count) }

    private fun pending(date: String = today, vararg pairs: Pair<Platform, Int>) =
        PendingCounts(date, pairs.toMap())

    // --- the case the whole change exists for ---------------------------------------------

    @Test
    fun `an advance detected but not yet written is already visible`() {
        // Room still has 11; the 12th swipe was detected microseconds ago.
        val merged = TodayMerge.merge(
            today,
            rows(Platform.INSTAGRAM to 11),
            pending(today, Platform.INSTAGRAM to 12),
        )
        assertEquals(12, merged.total)
        assertEquals(12, merged.countFor(Platform.INSTAGRAM))
    }

    @Test
    fun `the first advance on a platform with no row yet still shows`() {
        val merged = TodayMerge.merge(today, rows(), pending(today, Platform.YOUTUBE to 1))
        assertEquals(1, merged.total)
        assertEquals(1, merged.countFor(Platform.YOUTUBE))
    }

    // --- property 1: monotonic ---------------------------------------------------------------

    @Test
    fun `a stale Room emission cannot pull the count backwards`() {
        // Room emits the row for advance 12 while 13 and 14 are already detected. Reading Room
        // alone here would show the number DROP from 14 to 12 mid-scroll.
        val merged = TodayMerge.merge(
            today,
            rows(Platform.INSTAGRAM to 12),
            pending(today, Platform.INSTAGRAM to 14),
        )
        assertEquals(14, merged.total)
    }

    @Test
    fun `pending is never ADDED to the persisted count`() {
        // The bug this rules out: treating pending as a delta would show 11 + 12 = 23.
        val merged = TodayMerge.merge(
            today,
            rows(Platform.INSTAGRAM to 11),
            pending(today, Platform.INSTAGRAM to 12),
        )
        assertEquals(12, merged.total)
    }

    // --- property 2: converges ---------------------------------------------------------------

    @Test
    fun `once the write lands the merge is a no-op`() {
        val settled = TodayMerge.merge(
            today,
            rows(Platform.INSTAGRAM to 12),
            pending(today, Platform.INSTAGRAM to 12),
        )
        val roomOnly = TodayMerge.merge(today, rows(Platform.INSTAGRAM to 12), PendingCounts.NONE)
        assertEquals(roomOnly, settled)
    }

    @Test
    fun `platforms mix independently`() {
        val merged = TodayMerge.merge(
            today,
            rows(Platform.INSTAGRAM to 30, Platform.YOUTUBE to 9),
            pending(today, Platform.YOUTUBE to 10),   // IG settled, YT one ahead
        )
        assertEquals(40, merged.total)
        assertEquals(30, merged.countFor(Platform.INSTAGRAM))
        assertEquals(10, merged.countFor(Platform.YOUTUBE))
    }

    // --- property 3: counts can still go down -------------------------------------------------

    @Test
    fun `a cleared day reads zero once pending is reset`() {
        // Settings -> Clear data empties the table AND resets pending; both halves must be gone
        // or the merge would resurrect the total the user just deleted.
        val merged = TodayMerge.merge(today, rows(), PendingCounts.NONE)
        assertEquals(0, merged.total)
        assertEquals(emptyMap<Platform, Int>(), merged.perPlatform)
    }

    @Test
    fun `yesterday's pending counts do not leak into today`() {
        // A process alive across midnight: the pending map still holds yesterday's running total.
        val merged = TodayMerge.merge(
            today,
            rows(Platform.INSTAGRAM to 2),
            pending(yesterday, Platform.INSTAGRAM to 240),
        )
        assertEquals(2, merged.total)
    }

    // --- shape ---------------------------------------------------------------------------------

    @Test
    fun `an unknown platform id in the table is dropped, not guessed at`() {
        val merged = TodayMerge.merge(
            today,
            listOf(DailyCountEntity(today, "vine", 99), DailyCountEntity(today, Platform.INSTAGRAM.id, 3)),
            PendingCounts.NONE,
        )
        assertEquals(3, merged.total)
        assertEquals(setOf(Platform.INSTAGRAM), merged.perPlatform.keys)
    }

    @Test
    fun `an empty day is empty`() {
        assertEquals(0, TodayMerge.merge(today, rows(), PendingCounts.NONE).total)
    }
}
