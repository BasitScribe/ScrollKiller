package com.scrollkiller

import com.scrollkiller.service.IdentityAdvanceDetector
import com.scrollkiller.service.IdentityAdvanceDetector.Advance
import com.scrollkiller.service.ItemIdentity
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

    /** A frame that yielded a channel handle and no title — what YouTube gives us most often. */
    private fun handle(value: String) = ItemIdentity(handle = value)

    /** A frame that yielded both. */
    private fun short(channel: String, title: String) = ItemIdentity(channel, title)

    @Test
    fun `15 swipes count 15`() {
        val detector = detector()
        var counted = 0
        // One distinct Short per swipe, a realistic ~2s apart.
        repeat(15) { i ->
            if (detector.onIdentity(handle("@channel$i"), i * 2_000L) == Advance.COUNTED) counted++
        }
        assertEquals(15, counted)
    }

    @Test
    fun `the landing Short counts without a separate entry credit (D29 via identity)`() {
        // The FIRST identity of a session is an advance: it's the Short the user landed on.
        // This is exactly why the service suppresses D29's entry credit for identity platforms
        // — if both fired, the landing Short would count twice.
        assertEquals(Advance.COUNTED, detector().onIdentity(handle("@SagarsKitchen"), 0L))
    }

    @Test
    fun `40 content changes on one Short count once - the idle test`() {
        val detector = detector()
        var counted = 0
        // Idling on a Short: the same identity re-read over and over as subtitles tick and the
        // like count re-renders. Spread over 30s, well past the floor, so ONLY the identity
        // comparison can be what stops these counting.
        repeat(40) { i ->
            if (detector.onIdentity(handle("@SagarsKitchen"), i * 750L) == Advance.COUNTED) counted++
        }
        assertEquals("the landing Short, and nothing else", 1, counted)
    }

    @Test
    fun `an unreadable frame is ignored and does not lose the last identity`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@PakWheels"), 0L))

        // A frame where nothing per-Short could be extracted. It must not count...
        assertEquals(Advance.UNREADABLE, detector.onIdentity(null, 1_000L))
        // ...and crucially must not CLEAR the stored identity: if it did, the very next read of
        // the same Short would look brand new and idling would count forever.
        assertEquals(Advance.UNCHANGED, detector.onIdentity(handle("@PakWheels"), 2_000L))
    }

    @Test
    fun `two identities inside the floor collapse to one count`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@one"), 0L))
        assertEquals(Advance.FLOORED, detector.onIdentity(handle("@two"), 100L))
        assertEquals(Advance.FLOORED, detector.onIdentity(handle("@three"), 400L))
    }

    @Test
    fun `a floored identity is retried, not swallowed`() {
        val detector = detector()
        detector.onIdentity(handle("@one"), 0L)
        // The new Short arrives too fast and is rejected...
        assertEquals(Advance.FLOORED, detector.onIdentity(handle("@two"), 200L))
        // ...but it was NOT stored, so one of the ~40 following content changes counts it once
        // the floor has passed. A genuinely fast swipe is delayed, never dropped.
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@two"), 700L))
    }

    @Test
    fun `going back to the previous Short counts as an advance`() {
        // Deliberate, and different from DELTA_Y_FORWARD's forward-only rule: identity carries
        // no direction, so swiping back up is indistinguishable from swiping on. Both are
        // "another Short consumed", which is what the number is meant to measure.
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@a"), 0L))
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@b"), 2_000L))
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@a"), 4_000L))
    }

    @Test
    fun `reset re-counts the Short you land on when you come back`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@a"), 0L))
        assertEquals(Advance.UNCHANGED, detector.onIdentity(handle("@a"), 1_000L))

        // Left the app entirely and came back to the same Short — that IS a fresh entry.
        detector.reset()
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@a"), 2_000L))
    }

    // --- the 2026-08-11 regression: same creator, consecutive Shorts -------------------------
    //
    // Reported from real use as "YouTube is not counting properly". Every test below FAILED
    // before the identity became a pair — the handle was the whole identity, so a repeat
    // creator was indistinguishable from the same Short still playing.

    @Test
    fun `two different Shorts by the SAME creator both count`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity(short("@SagarsKitchen", "Paneer"), 0L))
        // Same channel, next Short. The handle is identical and cannot distinguish these; the
        // title can, and now does.
        assertEquals(
            "a second Short by a creator you just watched is still a Short",
            Advance.COUNTED,
            detector.onIdentity(short("@SagarsKitchen", "Biryani"), 2_000L),
        )
    }

    @Test
    fun `a whole session inside ONE channel counts every Short, not one`() {
        // The worst case, and the one that made the number look broken: open a creator's Shorts
        // tab and swipe. Every item shares a handle. This used to return 1.
        val detector = detector()
        var counted = 0
        repeat(15) { i ->
            if (detector.onIdentity(short("@OneChannel", "short number $i"), i * 2_000L)
                == Advance.COUNTED
            ) {
                counted++
            }
        }
        assertEquals(15, counted)
    }

    @Test
    fun `a title arriving a frame after the handle does NOT count twice`() {
        // The risk introduced by using the title at all: YouTube renders the two at slightly
        // different times, so a naive string concat would score the moment the title appeared as
        // an advance and double every Short. Only fields present on BOTH sides are compared.
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@PakWheels"), 0L))
        assertEquals(
            "the title showing up is new information about the same Short, not a new Short",
            Advance.UNCHANGED,
            detector.onIdentity(short("@PakWheels", "Civic review"), 800L),
        )
    }

    @Test
    fun `the late title is REMEMBERED, so the next Short by that creator still counts`() {
        // Merging on the UNCHANGED path is what makes this work. Without it the stored identity
        // would stay handle-only forever and the bug would return one frame later.
        val detector = detector()
        detector.onIdentity(handle("@PakWheels"), 0L)                      // counted, no title yet
        detector.onIdentity(short("@PakWheels", "Civic review"), 800L)     // same Short, learns it

        assertEquals(
            Advance.COUNTED,
            detector.onIdentity(short("@PakWheels", "Alto review"), 3_000L),
        )
    }

    @Test
    fun `a title that blanks out mid-play cannot erase what we counted on`() {
        val detector = detector()
        assertEquals(Advance.COUNTED, detector.onIdentity(short("@a", "Title"), 0L))
        assertEquals(Advance.UNCHANGED, detector.onIdentity(handle("@a"), 1_000L))
        // Still remembered, so a genuinely new Short by @a is still an advance.
        assertEquals(Advance.COUNTED, detector.onIdentity(short("@a", "Other"), 2_000L))
    }

    @Test
    fun `the idle test still holds with titles in play`() {
        // The property the whole design exists to protect. If the pair had weakened it, the fix
        // would be worse than the bug: 30s of staring at one Short must still count zero.
        val detector = detector()
        var counted = 0
        repeat(40) { i ->
            if (detector.onIdentity(short("@SagarsKitchen", "one video"), i * 750L)
                == Advance.COUNTED
            ) {
                counted++
            }
        }
        assertEquals("the landing Short, and nothing else", 1, counted)
    }

    @Test
    fun `an empty identity is unreadable, not an advance`() {
        // ReelIdentity returns null for "nothing found", but a pair with both fields absent is
        // the same statement and must not be treated as a new item.
        assertEquals(Advance.UNREADABLE, detector().onIdentity(ItemIdentity(), 0L))
    }

    @Test
    fun `reset clears the floor too, so a fast re-entry is not swallowed`() {
        val detector = detector()
        detector.onIdentity(handle("@a"), 0L)
        detector.reset()
        // Re-entry 100ms later is inside the floor, but the floor is about consecutive advances
        // within a session — a new session must not inherit it.
        assertEquals(Advance.COUNTED, detector.onIdentity(handle("@b"), 100L))
    }
}
