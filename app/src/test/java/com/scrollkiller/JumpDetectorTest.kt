package com.scrollkiller

import com.scrollkiller.challenge.JumpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The jump detector's edges, off-device.
 *
 * The one that matters most is [shaking the phone never counts]: if a landing spike alone were
 * enough, "jump 10 times" could be cleared from the sofa and the whole mechanic would be theatre.
 * The free-fall→landing SEQUENCE is what makes that impossible, and it is the property most likely
 * to be lost by a well-meant "simplification" of the detector.
 */
class JumpDetectorTest {

    private val g = JumpDetector.STANDARD_GRAVITY

    /** Feed `count` resting samples, which is what baselining consumes. */
    private fun JumpDetector.baseline(atMs: Long = 0L) {
        repeat(JumpDetector.BASELINE_SAMPLES) { i ->
            assertFalse(
                "baselining must never count a jump",
                onSample(g, atMs + i),
            )
        }
        assertTrue("should be baselined after BASELINE_SAMPLES", isBaselined)
    }

    /** One complete jump: free fall, then a landing spike, at [atMs]. */
    private fun JumpDetector.jump(atMs: Long): Boolean {
        onSample(g * 0.1, atMs)               // airborne
        return onSample(g * 3.0, atMs + 200)  // landing
    }

    @Test
    fun `a free fall then a landing counts one jump`() {
        val detector = JumpDetector()
        detector.baseline()
        assertTrue(detector.jump(1_000))
    }

    @Test
    fun `shaking the phone never counts - a landing spike alone is not a jump`() {
        val detector = JumpDetector()
        detector.baseline()
        // Spike after spike after spike, never preceded by free fall. This is what shaking the
        // device at the ring looks like, and it must score exactly zero.
        var counted = 0
        repeat(20) { i ->
            if (detector.onSample(g * 4.0, 1_000L + i * 500)) counted++
        }
        assertEquals("shaking must not count", 0, counted)
    }

    @Test
    fun `the landing ringing after one jump does not count again`() {
        val detector = JumpDetector()
        detector.baseline()
        assertTrue(detector.jump(1_000))
        // Impact oscillation: more spikes within the refractory window. A jump counted four times
        // is what this guard exists for.
        var extra = 0
        listOf(30L, 80L, 150L, 300L).forEach { offset ->
            if (detector.onSample(g * 2.5, 1_200 + offset)) extra++
        }
        assertEquals("one jump must count once", 0, extra)
    }

    @Test
    fun `a bounce that re-arms inside the refractory window still counts once`() {
        val detector = JumpDetector()
        detector.baseline()
        assertTrue(detector.jump(1_000))   // counted at 1_200

        // The harder case: the impact throws the phone enough to read as free fall again, so the
        // arm check would pass and only REFRACTORY_MS stops the double count. This is the guard the
        // previous test cannot reach.
        detector.onSample(g * 0.2, 1_250)
        assertFalse(
            "a re-armed bounce inside the refractory window must not count",
            detector.onSample(g * 3.0, 1_300),
        )
    }

    @Test
    fun `consecutive real jumps past the refractory window both count`() {
        val detector = JumpDetector()
        detector.baseline()
        assertTrue(detector.jump(1_000))
        assertTrue("a second real jump after the window must count", detector.jump(2_000))
    }

    @Test
    fun `a stale free fall does not bless a much later bump`() {
        val detector = JumpDetector()
        detector.baseline()
        detector.onSample(g * 0.1, 1_000)   // airborne...
        // ...and the spike arrives well past ARM_EXPIRY_MS. That was the phone being set down, then
        // knocked much later — two unrelated events.
        val late = 1_000 + JumpDetector.ARM_EXPIRY_MS + 500
        assertFalse(detector.onSample(g * 3.0, late))
    }

    @Test
    fun `ordinary handling in the dead band counts nothing`() {
        val detector = JumpDetector()
        detector.baseline()
        var counted = 0
        // Between FREE_FALL_FRACTION and LANDING_FRACTION nothing is happening: walking, tilting,
        // putting the phone in a pocket.
        listOf(0.7, 0.9, 1.0, 1.1, 1.3, 1.5).forEachIndexed { i, factor ->
            if (detector.onSample(g * factor, 1_000L + i * 100)) counted++
        }
        assertEquals(0, counted)
    }

    @Test
    fun `an implausible baseline is discarded for standard gravity`() {
        val detector = JumpDetector()
        // The user starts jumping the instant the challenge appears, so every baseline sample is
        // garbage. The average lands outside PLAUSIBLE_GRAVITY_RANGE and must be thrown away.
        repeat(JumpDetector.BASELINE_SAMPLES) { i -> detector.onSample(40.0, i.toLong()) }
        assertEquals(JumpDetector.STANDARD_GRAVITY, detector.restingMagnitude, 0.001)
    }

    @Test
    fun `a badly calibrated sensor's own resting value is used`() {
        val detector = JumpDetector()
        // 10.2 at rest is ordinary for consumer hardware and inside the plausible range, so it is
        // kept — thresholds are fractions of THIS device's gravity, not of 9.81.
        repeat(JumpDetector.BASELINE_SAMPLES) { i -> detector.onSample(10.2, i.toLong()) }
        assertEquals(10.2, detector.restingMagnitude, 0.001)
    }

    @Test
    fun `nothing counts before baselining completes`() {
        val detector = JumpDetector()
        var counted = 0
        // A jump landing mid-baseline. It cannot count, because there is no resting magnitude yet to
        // compare against — the same "first observation scores nothing" rule the rest of the app uses.
        repeat(JumpDetector.BASELINE_SAMPLES - 1) { i ->
            if (detector.onSample(if (i % 2 == 0) g * 0.1 else g * 3.0, i.toLong())) counted++
        }
        assertEquals(0, counted)
        assertFalse(detector.isBaselined)
    }

    @Test
    fun `reset returns the detector to un-baselined and unarmed`() {
        val detector = JumpDetector()
        detector.baseline()
        assertTrue(detector.jump(1_000))

        detector.reset()
        assertFalse(detector.isBaselined)
        assertEquals(0.0, detector.restingMagnitude, 0.001)

        // And the refractory window is forgotten too: a fresh attempt's first jump must count even
        // though it lands sooner after the previous one than REFRACTORY_MS.
        detector.baseline(atMs = 1_300)
        assertTrue(detector.jump(1_350))
    }
}
