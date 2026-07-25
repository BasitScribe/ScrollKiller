package com.scrollkiller

import com.scrollkiller.service.IdentityAdvanceDetector
import com.scrollkiller.service.IdentityAdvanceDetector.Advance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two acceptance criteria for YouTube Shorts (D34), proven off-device:
 *  - 15 swipes must count 15;
 *  - 30 seconds idle on one Short must count ZERO, despite the ~40 content-change events per
 *    Short that idling produces.
 * Everything else here guards the edges that make those two hold.
 */
class IdentityAdvanceDetectorTest {

    /** YouTube's floor (PlatformRegistry ships 500ms for Shorts). */
    private val floorMs = 500L

    private fun detector() = IdentityAdvanceDetector(floorMs)

    @Test
    fun `15 swipes count 15`() {
        val detector = detector()
        var counted = 0
        // One distinct Short per swipe, a realistic ~2s apart.
        repeat(15) { i ->
            if (detector.onIdentity("@channel$i", i * 2_000L) == Advance.COUNTED) counted++
        }
        assertEquals(15, counted)
    }

    @Test
    fun `the landing Short counts without a separate entry credit (D29 via identity)`() {
        // The FIRST identity of a session is an advance: it's the Short the user landed on.
        // This is exactly why the service suppresses D29's entry credit for identity platforms
        // — if both fired, the landing Short would count twice.
        assertEquals(Advance.COUNTED, detector().onIdentity("@SagarsKitchen", 0L))
    }

    @Test
    fun `40 content changes on one Short count once - the idle test`() {
        val detector = detector()
        var counted = 0
        // Idling on a Short: the same identity re-read over and over as subtitles tick and the
        // like count re-renders. Spread over 30s, well past the floor, so ONLY the identity
        // comparison can be what stops these counting.
        repeat(40) { i ->
            if (detector.onIdentity("@SagarsKitchen", i * 750L) == Advance.COUNTED) counted++
        }
        assertEquals("the landing Short, and nothing else", 1, counted)
    }

    @Test
    fun `an unreadable frame is ignored and does not lose the last identity`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity("@PakWheels", 0L))

        // A frame where nothing per-Short could be extracted. It must not count...
        assertEquals(Advance.UNREADABLE, detector.onIdentity(null, 1_000L))
        // ...and crucially must not CLEAR the stored identity: if it did, the very next read of
        // the same Short would look brand new and idling would count forever.
        assertEquals(Advance.UNCHANGED, detector.onIdentity("@PakWheels", 2_000L))
    }

    @Test
    fun `two identities inside the floor collapse to one count`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity("@one", 0L))
        assertEquals(Advance.FLOORED, detector.onIdentity("@two", 100L))
        assertEquals(Advance.FLOORED, detector.onIdentity("@three", 400L))
    }

    @Test
    fun `a floored identity is retried, not swallowed`() {
        val detector = detector()
        detector.onIdentity("@one", 0L)
        // The new Short arrives too fast and is rejected...
        assertEquals(Advance.FLOORED, detector.onIdentity("@two", 200L))
        // ...but it was NOT stored, so one of the ~40 following content changes counts it once
        // the floor has passed. A genuinely fast swipe is delayed, never dropped.
        assertEquals(Advance.COUNTED, detector.onIdentity("@two", 700L))
    }

    @Test
    fun `going back to the previous Short counts as an advance`() {
        // Deliberate, and different from DELTA_Y_FORWARD's forward-only rule: identity carries
        // no direction, so swiping back up is indistinguishable from swiping on. Both are
        // "another Short consumed", which is what the number is meant to measure.
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity("@a", 0L))
        assertEquals(Advance.COUNTED, detector.onIdentity("@b", 2_000L))
        assertEquals(Advance.COUNTED, detector.onIdentity("@a", 4_000L))
    }

    @Test
    fun `reset re-counts the Short you land on when you come back`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity("@a", 0L))
        assertEquals(Advance.UNCHANGED, detector.onIdentity("@a", 1_000L))

        // Left the app entirely and came back to the same Short — that IS a fresh entry.
        detector.reset()
        assertEquals(Advance.COUNTED, detector.onIdentity("@a", 2_000L))
    }

    @Test
    fun `reset clears the floor too, so a fast re-entry is not swallowed`() {
        val detector = detector()
        detector.onIdentity("@a", 0L)
        detector.reset()
        // Re-entry 100ms later is inside the floor, but the floor is about consecutive advances
        // within a session — a new session must not inherit it.
        assertEquals(Advance.COUNTED, detector.onIdentity("@b", 100L))
    }
}
