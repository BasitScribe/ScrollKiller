package com.scrollkiller

import com.scrollkiller.stats.SessionRoller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session splitting and the seconds derived from it (D80).
 *
 * The property that matters most is the one in [gaps between sittings are never counted]: a day's
 * time is the sum of its sittings, NOT first-to-last across the day. Getting that wrong would count
 * the hours between a morning scroll and an evening one as scrolling, which is the failure mode
 * that would make the whole Insights screen dishonest.
 */
class SessionRollerTest {

    private val min = 60_000L
    private val tail = SessionRoller.TAIL_SECONDS.toLong()

    private fun at(vararg minutes: Long) = minutes.map { it * min }

    /* --- splitting ----------------------------------------------------------------------- */

    @Test
    fun `no events is no sessions and no time`() {
        assertEquals(emptyList<List<Long>>(), SessionRoller.sessions(emptyList()))
        assertEquals(0L, SessionRoller.secondsFor(emptyList()))
    }

    @Test
    fun `scrolls closer together than the gap are one sitting`() {
        val sessions = SessionRoller.sessions(at(0, 1, 2, 3))
        assertEquals(1, sessions.size)
        assertEquals(4, sessions.single().size)
    }

    @Test
    fun `silence longer than the gap starts a new sitting`() {
        // 0..2 then a 30-minute gap then 32..33.
        val sessions = SessionRoller.sessions(at(0, 1, 2, 32, 33))
        assertEquals(2, sessions.size)
        assertEquals(at(0, 1, 2), sessions[0])
        assertEquals(at(32, 33), sessions[1])
    }

    @Test
    fun `a gap exactly equal to the threshold does NOT split`() {
        // The boundary belongs to the same session: the split is on `>` gap, not `>=`. Stated as a
        // test because a later refactor could flip it without any visible symptom.
        val gap = SessionRoller.SESSION_GAP_MS
        assertEquals(1, SessionRoller.sessions(listOf(0L, gap)).size)
        assertEquals(2, SessionRoller.sessions(listOf(0L, gap + 1)).size)
    }

    @Test
    fun `unsorted input is handled, because event order is not guaranteed`() {
        val sessions = SessionRoller.sessions(at(33, 1, 32, 0, 2))
        assertEquals(2, sessions.size)
        assertEquals(at(0, 1, 2), sessions[0])
        assertEquals(at(32, 33), sessions[1])
    }

    /* --- seconds ------------------------------------------------------------------------- */

    @Test
    fun `one lone scroll still earns the tail, never zero`() {
        // Someone opened Reels, watched one, left. Recording that as no time at all would be worse
        // than the flat estimate this is meant to improve on.
        assertEquals(tail, SessionRoller.secondsFor(listOf(1_000L)))
    }

    @Test
    fun `a sitting is its span plus the item still on screen when it ended`() {
        // 0 -> 3 minutes is 180s of measured span; the last item adds the tail.
        assertEquals(180 + tail, SessionRoller.secondsFor(at(0, 1, 2, 3)))
    }

    @Test
    fun `gaps between sittings are never counted`() {
        // THE load-bearing property. Two 2-minute sittings eight hours apart is four minutes of
        // scrolling, not eight hours. First-to-last across the day would report 482 minutes.
        val morning = at(0, 2)
        val evening = at(480, 482)
        val seconds = SessionRoller.secondsFor(morning + evening)

        assertEquals((120 + tail) * 2, seconds)
        assertTrue("must be minutes, not the eight-hour span", seconds < 10 * 60)
    }

    @Test
    fun `duplicate timestamps collapse to a zero-span sitting and still earn one tail`() {
        assertEquals(tail, SessionRoller.secondsFor(listOf(5_000L, 5_000L, 5_000L)))
    }

    @Test
    fun `seconds are never negative for any input ordering`() {
        listOf(
            at(5, 4, 3, 2, 1),
            at(100, 0, 50),
            listOf(Long.MAX_VALUE / 2, 0L),
        ).forEach { input ->
            assertTrue("negative for $input", SessionRoller.secondsFor(input) >= 0)
        }
    }

    @Test
    fun `splitting more aggressively can only reduce or hold the total, never inflate it`() {
        // The asymmetry the gap constant is chosen on (see SessionRoller's doc): over-splitting
        // costs a little accuracy, under-splitting corrupts the headline number. A smaller gap must
        // therefore never produce MORE time — except for the tails it adds, which is bounded.
        val events = at(0, 1, 2, 10, 11, 20, 21)
        val loose = SessionRoller.secondsFor(events, gapMs = 30 * min)
        val tight = SessionRoller.secondsFor(events, gapMs = 2 * min)
        val extraTails = 2 * tail // three sittings instead of one
        assertTrue("tight=$tight loose=$loose", tight <= loose + extraTails)
    }
}
