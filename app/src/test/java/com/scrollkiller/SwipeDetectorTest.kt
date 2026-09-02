package com.scrollkiller

import com.scrollkiller.service.ScrollDirection
import com.scrollkiller.service.SwipeDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the debounce logic — the accuracy-critical piece behind the
 * Phase-1 exit bar (±2 over 50 swipes). Pure JVM, no device needed.
 *
 * Fixture uses the real Instagram interval (200ms) and the timings we actually
 * observed in logcat: a fling burst is ~110ms-spaced DOWN events over ~640ms.
 */
class SwipeDetectorTest {

    private val intervalMs = 200L

    /** Feed a list of (direction, timestamp) events; return how many counted. */
    private fun countAdvances(events: List<Pair<ScrollDirection, Long>>): Int {
        val detector = SwipeDetector(intervalMs)
        return events.count { (dir, ts) -> detector.onScroll(dir, ts) }
    }

    @Test
    fun `single fling burst collapses to one advance`() {
        // 8 DOWN events 100ms apart — one physical swipe.
        val burst = (0 until 8).map { ScrollDirection.DOWN to (it * 100L) }
        assertEquals(1, countAdvances(burst))
    }

    @Test
    fun `observed 640ms burst collapses to one advance`() {
        // Regression against real logcat: 7 DOWN events ~110ms apart spanning 640ms.
        val ts = listOf(0L, 105, 210, 315, 425, 536, 640)
        val burst = ts.map { ScrollDirection.DOWN to it }
        assertEquals(1, countAdvances(burst))
    }

    @Test
    fun `deliberate swipes with pauses each count`() {
        // Five swipes ~1s apart — each is a fresh advance.
        val swipes = (0 until 5).map { ScrollDirection.DOWN to (it * 1000L) }
        assertEquals(5, countAdvances(swipes))
    }

    @Test
    fun `two bursts separated by a quiet gap count twice`() {
        val firstBurst = (0 until 6).map { ScrollDirection.DOWN to (it * 100L) }   // ends at 500
        val secondBurst = (0 until 6).map { ScrollDirection.DOWN to (1000L + it * 100L) } // starts at 1000
        assertEquals(2, countAdvances(firstBurst + secondBurst))
    }

    @Test
    fun `up and same directions never count`() {
        val events = listOf(
            ScrollDirection.UP to 0L,
            ScrollDirection.SAME to 500L,
            ScrollDirection.UP to 1000L,
            ScrollDirection.SAME to 2000L,
        )
        assertEquals(0, countAdvances(events))
    }

    @Test
    fun `up events between downs do not reset the debounce`() {
        // UP/SAME are ignored entirely, so interleaving them must not create extra
        // counts within a single fling.
        val events = listOf(
            ScrollDirection.DOWN to 0L,     // counts
            ScrollDirection.UP to 50L,      // ignored
            ScrollDirection.DOWN to 100L,   // within 200ms of last DOWN -> no count
            ScrollDirection.SAME to 150L,   // ignored
            ScrollDirection.DOWN to 200L,   // still within 200ms of last DOWN(100) -> no count
        )
        assertEquals(1, countAdvances(events))
    }

    @Test
    fun `first ever down always counts even at timestamp zero`() {
        assertEquals(1, countAdvances(listOf(ScrollDirection.DOWN to 0L)))
    }

    @Test
    fun `an EVENT_PULSE treats any scroll as an advance, including SAME`() {
        // TikTok/Snapchat Shorts-style pagers often report deltaY=0 (SAME), the same dead
        // direction that made YouTube count nothing under DELTA_Y_FORWARD. EVENT_PULSE still
        // has to collapse a fling burst — it just does not care about direction.
        val detector = SwipeDetector(intervalMs)
        assertEquals(true, detector.onPulse(0L))
        assertEquals(false, detector.onPulse(100L))
        assertEquals(false, detector.onPulse(180L))
        assertEquals(true, detector.onPulse(1_000L))
    }
}
