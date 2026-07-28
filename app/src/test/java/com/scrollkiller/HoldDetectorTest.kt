package com.scrollkiller

import com.scrollkiller.challenge.HoldDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold timer's edges, off-device.
 *
 * The load-bearing one is [breaking resets to zero, it does not pause] and its flip-flop sibling: if
 * a break merely paused, a 30-second face-down hold would be satisfiable as six five-second flips
 * with a peek at Instagram between each — which is not a break from scrolling, it is scrolling with
 * extra steps. That property is the whole anti-cheat and the thing most likely to be lost by a
 * later "be nicer to the user" change.
 */
class HoldDetectorTest {

    @Test
    fun `the first sample of a hold scores zero`() {
        val detector = HoldDetector()
        // No time has yet passed WITH the condition true, so there is nothing to credit. Same
        // "first observation baselines and fires nothing" rule as ChallengeProgress and GuiltFiring.
        assertEquals(0, detector.onSample(conditionMet = true, timestampMs = 1_000))
        assertTrue(detector.isHolding)
    }

    @Test
    fun `elapsed whole seconds accumulate while held`() {
        val detector = HoldDetector()
        detector.onSample(true, 1_000)
        assertEquals(0, detector.onSample(true, 1_400))
        assertEquals(1, detector.onSample(true, 2_000))
        assertEquals(5, detector.onSample(true, 6_000))
        assertEquals(30, detector.onSample(true, 31_000))
    }

    @Test
    fun `seconds truncate rather than round`() {
        val detector = HoldDetector()
        detector.onSample(true, 0)
        // At 29.9s held the user has NOT earned 30. Rounding up would complete the challenge a
        // tenth of a second early, which is the one direction that must never happen.
        assertEquals(29, detector.onSample(true, 29_900))
        assertEquals(30, detector.onSample(true, 30_000))
    }

    @Test
    fun `breaking resets to zero, it does not pause`() {
        val detector = HoldDetector()
        detector.onSample(true, 0)
        assertEquals(20, detector.onSample(true, 20_000))

        // Flipped up.
        assertEquals("a break must report zero, not the banked 20", 0, detector.onSample(false, 20_500))
        assertFalse(detector.isHolding)

        // Flipped back down: the new hold starts from scratch, NOT from 20.
        assertEquals(0, detector.onSample(true, 21_000))
        assertEquals("the new hold restarts from zero", 2, detector.onSample(true, 23_000))
    }

    @Test
    fun `flipping back and forth banks nothing`() {
        val detector = HoldDetector()
        var now = 0L
        // Five five-second holds with a break between each. Under a pausing timer this would total
        // 25 seconds and all but complete a 30-second challenge. It must total nothing.
        repeat(5) {
            detector.onSample(true, now)
            val held = detector.onSample(true, now + 5_000)
            assertEquals("each hold is independent", 5, held)
            now += 5_000
            assertEquals(0, detector.onSample(false, now))
            now += 500
        }
        // And the next hold still starts at zero.
        detector.onSample(true, now)
        assertEquals(0, detector.onSample(true, now))
    }

    @Test
    fun `repeated not-held samples stay at zero without churning`() {
        val detector = HoldDetector()
        // The source samples ~5x/second whether or not the user is holding. An idle challenge must
        // simply keep reporting 0 — this is what stops the break-buzz firing every 200ms.
        repeat(20) { i ->
            assertEquals(0, detector.onSample(false, i * 200L))
        }
        assertFalse(detector.isHolding)
    }

    @Test
    fun `a clock that goes backwards cannot produce negative progress`() {
        val detector = HoldDetector()
        detector.onSample(true, 10_000)
        // Should not happen with elapsedRealtime, but a negative here would become negative progress
        // and a ring drawn in reverse over someone else's app.
        assertEquals(0, detector.onSample(true, 9_000))
    }

    @Test
    fun `reset forgets the hold in flight`() {
        val detector = HoldDetector()
        detector.onSample(true, 0)
        assertEquals(10, detector.onSample(true, 10_000))

        detector.reset()
        assertFalse(detector.isHolding)

        // The next attempt starts from zero even though the condition never went false — teardown is
        // not the same event as the user letting go, and both must clear the hold.
        detector.onSample(true, 11_000)
        assertEquals(1, detector.onSample(true, 12_000))
    }

    @Test
    fun `a hold sustained across the target keeps counting past it`() {
        val detector = HoldDetector()
        detector.onSample(true, 0)
        // The detector does not know the target; clamping is ChallengeProgress's job. It must not
        // stop timing, or a source could not tell "still holding" from "stopped".
        assertEquals(45, detector.onSample(true, 45_000))
    }
}
