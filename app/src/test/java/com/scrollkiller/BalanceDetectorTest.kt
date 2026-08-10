package com.scrollkiller

import com.scrollkiller.challenge.BalanceDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The balance detector (D84).
 *
 * Two guards, tested separately because they fail differently: LEVEL rejects a phone that is merely
 * being held, and TREMOR rejects a phone that is merely level. Neither alone is a challenge — a
 * tilted hand and a flat table both have to be refused.
 *
 * The tremor floor is documented as the softest anti-cheat in the suite, so these tests pin where it
 * currently sits rather than claiming it is unbeatable. If a device run shows a real table clearing
 * it, `MIN_TREMOR` is the constant to move and these numbers move with it.
 */
class BalanceDetectorTest {

    private val g = 9.8

    /**
     * Feed [samples] readings of a phone tilted [degrees] from flat, with [tremor] m/s² of hand
     * movement, and return whether it is judged balanced at the end.
     */
    private fun run(
        degrees: Double,
        tremor: Double,
        samples: Int = 120,
        screenUp: Boolean = true,
    ): Boolean {
        val detector = BalanceDetector()
        val radians = Math.toRadians(degrees)
        val lateral = g * sin(radians)
        val vertical = g * cos(radians) * (if (screenUp) 1 else -1)
        var balanced = false
        repeat(samples) { i ->
            // Alternating sign so the deviation estimate sees real movement rather than a drift the
            // smoothing would simply follow.
            val wobble = if (i % 2 == 0) tremor else -tremor
            balanced = detector.onSample(lateral + wobble, 0.0, vertical + wobble)
        }
        return balanced
    }

    @Test
    fun `flat on a table is refused`() {
        // THE anti-cheat, such as it is. A table is more level than any hand, so level alone would
        // make this challenge a matter of putting the phone down. Sensor noise on a still surface is
        // an order of magnitude below a hand tremor.
        assertFalse("a phone resting on a table must not balance", run(degrees = 0.0, tremor = 0.005))
    }

    @Test
    fun `held flat and steady is accepted`() {
        assertTrue("a steady palm must balance", run(degrees = 2.0, tremor = 0.25))
    }

    @Test
    fun `held but tilted is refused`() {
        // The other half. A phone held casually in the hand at 30° is alive but not level, and
        // accepting it would make the challenge "hold your phone", which is what the user was
        // already doing.
        assertFalse("a tilted hand must not balance", run(degrees = 30.0, tremor = 0.25))
    }

    @Test
    fun `level and alive but upside down is refused`() {
        // Z sign only. A phone held level and face-DOWN is the face-down hold, not this one, and
        // letting it pass would make two rows of the chooser the same challenge.
        assertFalse(
            "screen must face up",
            run(degrees = 2.0, tremor = 0.25, screenUp = false),
        )
    }

    @Test
    fun `the level tolerance is about fifteen degrees either way`() {
        // Measured LATERALLY rather than from Z, so the same tolerance holds on a device whose
        // resting magnitude is not 9.8 — see the class doc. Ten degrees in, twenty out.
        assertTrue(run(degrees = 10.0, tremor = 0.25))
        assertFalse(run(degrees = 20.0, tremor = 0.25))
    }

    @Test
    fun `the tolerance holds on a badly calibrated sensor`() {
        // The reason for measuring laterally. A device reading 10.4 at rest must get the same ANGLE
        // of tolerance, not a tighter or looser one — a fixed Z floor would have silently changed it.
        val detector = BalanceDetector()
        val heavy = 10.4
        val radians = Math.toRadians(10.0)
        var balanced = false
        repeat(120) { i ->
            val wobble = if (i % 2 == 0) 0.25 else -0.25
            balanced = detector.onSample(
                heavy * sin(radians) + wobble,
                0.0,
                heavy * cos(radians) + wobble,
            )
        }
        assertTrue("10° must still be level on a 10.4 g sensor", balanced)
    }

    @Test
    fun `nothing balances before the filters have warmed up`() {
        // The tremor estimate is meaningless until it has samples, and reporting true early would
        // let HoldDetector start banking seconds against a number that has not been measured.
        val detector = BalanceDetector()
        var balanced = false
        repeat(BalanceDetector.WARMUP_SAMPLES) { i ->
            val wobble = if (i % 2 == 0) 0.25 else -0.25
            balanced = detector.onSample(wobble, 0.0, g + wobble)
        }
        assertFalse("must not report balanced while warming up", balanced)
    }

    @Test
    fun `reset forgets the tremor history`() {
        // Without this, an attempt that followed a lively one would inherit its tremor estimate and
        // could bank seconds from a phone now sitting on a table.
        val detector = BalanceDetector()
        repeat(120) { i ->
            val wobble = if (i % 2 == 0) 0.4 else -0.4
            detector.onSample(wobble, 0.0, g + wobble)
        }
        assertTrue(detector.currentTremor > BalanceDetector.MIN_TREMOR)

        detector.reset()
        assertTrue("tremor must be forgotten", detector.currentTremor == 0.0)
        assertFalse("and the next sample must warm up again", detector.onSample(0.0, 0.0, g))
    }
}
