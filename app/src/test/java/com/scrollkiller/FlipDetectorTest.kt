package com.scrollkiller

import com.scrollkiller.challenge.FlipDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flip detector (D84).
 *
 * Two properties carry this challenge: the first orientation must be FREE (or every attempt starts
 * one flip ahead), and a phone wobbling near flat must count NOTHING (or one slow turn reads as a
 * dozen and "flip 10" completes by putting the phone down carelessly).
 */
class FlipDetectorTest {

    private val up = 9.8
    private val down = -9.8

    /** Feed a sequence of Z readings and return how many flips were counted. */
    private fun count(vararg zs: Double): Int {
        val detector = FlipDetector()
        return zs.count { detector.onSample(it) }
    }

    @Test
    fun `the first settled orientation is free`() {
        // The challenge opens with the phone screen-up in the user's hand, because they just tapped
        // a button on it. Counting that would hand out a flip for doing nothing.
        assertEquals(0, count(up))
        assertEquals(0, count(down))
    }

    @Test
    fun `each full turn counts once`() {
        // up (baseline) → down → up → down = three CHANGES.
        assertEquals(3, count(up, down, up, down))
    }

    @Test
    fun `staying in one orientation counts nothing however long`() {
        val zs = DoubleArray(200) { up }
        assertEquals(0, count(*zs))
    }

    @Test
    fun `a phone wobbling near flat counts nothing`() {
        // THE debounce test. Values inside the dead band are not observations at all, so a phone
        // being set down, carried, or propped against something reports nothing rather than
        // flickering between two verdicts.
        val detector = FlipDetector()
        detector.onSample(up)     // baseline
        var counted = 0
        repeat(200) { i ->
            val z = if (i % 2 == 0) 3.0 else -3.0
            if (detector.onSample(z)) counted++
        }
        assertEquals("wobbling inside the dead band scored $counted flips", 0, counted)
        assertTrue("and the settled orientation must be untouched", detector.settledFaceUp == true)
    }

    @Test
    fun `passing through the dead band does not break a flip in two`() {
        // A real turn passes through every intermediate angle. Those samples must be ignored, not
        // treated as a third state that separates one flip into two.
        assertEquals(1, count(up, 5.0, 0.0, -5.0, down))
        assertEquals(2, count(up, 4.0, down, -2.0, 6.0, up))
    }

    @Test
    fun `returning to the same orientation without settling elsewhere counts nothing`() {
        // Tilt the phone most of the way over and bring it back. It never settled face-down, so
        // nothing happened — which is what stops a half-hearted waggle from scoring.
        assertEquals(0, count(up, 3.0, -6.0, 2.0, up))
    }

    @Test
    fun `ten flips means ten changes, which is five there-and-back cycles`() {
        // Pins the challenge's actual meaning, because "flip 10 times" is ambiguous in English and
        // the prompt copy has to match what the engine counts.
        val detector = FlipDetector()
        var counted = 0
        detector.onSample(up)   // baseline
        repeat(5) {
            if (detector.onSample(down)) counted++
            if (detector.onSample(up)) counted++
        }
        assertEquals(10, counted)
    }

    @Test
    fun `the threshold is symmetric`() {
        // Just inside the band either way is in transit; just outside either way is settled.
        val inside = FlipDetector.SETTLED_Z - 0.1
        val outside = FlipDetector.SETTLED_Z + 0.1
        assertEquals(0, count(up, inside, -inside))
        assertEquals(1, count(outside, -outside))
    }

    @Test
    fun `reset makes the next orientation a baseline again`() {
        val detector = FlipDetector()
        detector.onSample(up)
        assertTrue(detector.onSample(down))
        assertEquals(false, detector.settledFaceUp)

        detector.reset()
        assertNull(detector.settledFaceUp)
        // Post-reset the first reading baselines, so this must NOT count even though it differs
        // from what was settled before the reset. Progress is dropped between attempts, not banked.
        assertFalse(detector.onSample(up))
    }
}
