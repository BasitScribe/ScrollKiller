package com.scrollkiller

import com.scrollkiller.challenge.ShakeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shake detector (D84).
 *
 * The load-bearing test here is [a lazy wave never counts] — the same job
 * `JumpDetectorTest`'s twenty un-armed spikes do. A shake challenge that can be cleared by waggling
 * the phone an inch is theatre, and the threshold is the only thing standing between the two.
 */
class ShakeDetectorTest {

    private val gravity = 9.8

    /** Feed [count] resting samples so the gravity filter primes and settles. */
    private fun ShakeDetector.warmUp(startMs: Long = 0L, count: Int = 40): Long {
        var t = startMs
        repeat(count) {
            onSample(0.0, 0.0, gravity, t)
            t += SAMPLE_MS
        }
        return t
    }

    @Test
    fun `resting on a table never counts a shake`() {
        val detector = ShakeDetector()
        var t = 0L
        var counted = 0
        repeat(500) {
            // A dead-still phone, plus the sensor noise a real one always carries.
            val noise = if (it % 2 == 0) 0.02 else -0.02
            if (detector.onSample(noise, 0.0, gravity + noise, t)) counted++
            t += SAMPLE_MS
        }
        assertEquals("a phone on a table shook $counted times", 0, counted)
    }

    @Test
    fun `a lazy wave never counts`() {
        // THE anti-cheat. Waving the phone gently side to side is the obvious way to try to clear
        // this from the sofa, and it produces real oscillation — just not enough of it. ~0.2 g of
        // added motion, sustained for ten seconds, must score exactly nothing.
        val detector = ShakeDetector()
        var t = detector.warmUp()
        var counted = 0
        repeat(500) { i ->
            val wave = if ((i / 6) % 2 == 0) 2.0 else -2.0
            if (detector.onSample(wave, 0.0, gravity, t)) counted++
            t += SAMPLE_MS
        }
        assertEquals("a lazy wave scored $counted shakes", 0, counted)
    }

    @Test
    fun `a committed shake counts once per burst`() {
        val detector = ShakeDetector()
        var t = detector.warmUp()
        var counted = 0
        // Ten bursts, each clearly over SHAKE_ON, each separated by a genuine return to rest.
        repeat(10) {
            repeat(4) {
                if (detector.onSample(12.0, 0.0, gravity, t)) counted++
                t += SAMPLE_MS
            }
            repeat(12) {
                if (detector.onSample(0.0, 0.0, gravity, t)) counted++
                t += SAMPLE_MS
            }
        }
        assertEquals("each burst must count exactly once", 10, counted)
    }

    @Test
    fun `hysteresis stops sustained motion counting over and over`() {
        // Motion that stays high but dips between peaks is what a SINGLE threshold turns into a
        // burst of phantom shakes — it would re-trigger on every peak. The latch must hold until
        // the motion has genuinely fallen back below SHAKE_OFF, which this never does.
        //
        // The signal is symmetric so the gravity filter converges on zero and stays there: that
        // isolates the latch, which is the thing under test, from the filter absorbing an offset.
        val detector = ShakeDetector()
        var t = detector.warmUp()
        var counted = 0
        repeat(100) {
            // 7 clears SHAKE_ON; 5 dips but stays well above SHAKE_OFF.
            listOf(7.0, -7.0, 5.0, -5.0).forEach { x ->
                if (detector.onSample(x, 0.0, gravity, t)) counted++
                t += SAMPLE_MS
            }
        }
        assertEquals("motion that never returns to rest may only count once", 1, counted)
    }

    @Test
    fun `a sustained offset is absorbed by the gravity filter, not counted`() {
        // Holding the phone at a steady angle puts a large constant value on an axis. It is not
        // motion and must not score — which is what the gravity estimate is for. Distinct from the
        // test above: there the latch does the work, here the filter does.
        val detector = ShakeDetector()
        var t = detector.warmUp()
        var counted = 0
        repeat(300) {
            if (detector.onSample(8.0, 0.0, gravity, t)) counted++
            t += SAMPLE_MS
        }
        assertEquals(
            "a steady tilt scored $counted shakes — one for the initial change is all it may have",
            1,
            counted,
        )
    }

    @Test
    fun `the refractory window caps the counted rate`() {
        // Isolated from the hysteresis: the signal is symmetric, so gravity stays at zero and the
        // motion genuinely returns to rest between every burst — the latch releases each time and
        // the refractory is the ONLY thing left limiting the count.
        //
        // Bursts arrive every 3 samples (60ms), so without a refractory this scores one per burst.
        // REFRACTORY_MS is 120ms, so it must score roughly half that.
        val detector = ShakeDetector()
        var t = detector.warmUp()
        val startedAt = t
        var counted = 0
        val bursts = 80
        repeat(bursts / 2) {
            listOf(14.0, 0.0, 0.0, -14.0, 0.0, 0.0).forEach { x ->
                if (detector.onSample(x, 0.0, gravity, t)) counted++
                t += SAMPLE_MS
            }
        }
        val elapsed = t - startedAt
        val ceiling = (elapsed / ShakeDetector.REFRACTORY_MS).toInt() + 1
        assertTrue(
            "counted $counted in ${elapsed}ms — faster than one per ${ShakeDetector.REFRACTORY_MS}ms",
            counted <= ceiling,
        )
        assertTrue(
            "counted $counted of $bursts bursts — the refractory is not limiting anything",
            counted < bursts,
        )
        assertTrue("but a real shake must still count", counted > bursts / 4)
    }

    @Test
    fun `nothing counts before the filter has warmed up`() {
        // Priming from the first sample rather than from zero is what stops the opening second of
        // every attempt reading as one enormous linear acceleration and handing out free shakes.
        val detector = ShakeDetector()
        var t = 0L
        var counted = 0
        repeat(ShakeDetector.WARMUP_SAMPLES + 1) {
            if (detector.onSample(0.0, 0.0, gravity, t)) counted++
            t += SAMPLE_MS
        }
        assertEquals(0, counted)
        assertTrue(detector.isWarmedUp)
    }

    @Test
    fun `it warms up against whatever orientation the phone starts in`() {
        // The phone is not necessarily flat when the challenge opens — it is most likely held at an
        // angle, since the user just tapped a button on it. Gravity landing on X instead of Z must
        // not read as sustained motion.
        val detector = ShakeDetector()
        var t = 0L
        var counted = 0
        repeat(200) {
            if (detector.onSample(gravity, 0.0, 0.0, t)) counted++
            t += SAMPLE_MS
        }
        assertEquals("a phone held on its side is not shaking", 0, counted)
    }

    @Test
    fun `reset forgets the gravity estimate and the latch`() {
        val detector = ShakeDetector()
        detector.warmUp()
        assertTrue(detector.isWarmedUp)
        detector.reset()
        assertFalse(detector.isWarmedUp)

        // And a shake immediately after a reset must not count against the OLD estimate.
        var counted = 0
        var t = 0L
        repeat(5) {
            if (detector.onSample(14.0, 0.0, gravity, t)) counted++
            t += SAMPLE_MS
        }
        assertEquals("post-reset samples must warm up again first", 0, counted)
    }

    private companion object {
        /** Roughly SENSOR_DELAY_GAME, which is what AccelShakeSource registers at. */
        const val SAMPLE_MS = 20L
    }
}
