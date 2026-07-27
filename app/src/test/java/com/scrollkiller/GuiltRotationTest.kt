package com.scrollkiller

import com.scrollkiller.guilt.GuiltCategory
import com.scrollkiller.guilt.GuiltLine
import com.scrollkiller.guilt.GuiltRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Guards the two properties the block screen actually depends on: weighted odds, and no
 * repeat within a session. The second is the one a naive `.random()` (what this replaced)
 * fails — and it fails invisibly, as "the app keeps saying the same thing".
 */
class GuiltRotationTest {

    private fun line(id: String, weight: Int = 1) =
        GuiltLine(id, GuiltCategory.ROAST, intensity = 1, weight = weight, text = "text-$id")

    @Test
    fun `empty candidates yield null rather than throwing`() {
        assertNull(GuiltRotation().pick(emptyList()))
    }

    @Test
    fun `every line is shown exactly once before any repeats`() {
        val pool = (1..8).map { line("l$it") }
        val rotation = GuiltRotation(Random(1))

        val firstCycle = (1..8).map { rotation.pick(pool)!!.id }

        assertEquals("a full cycle must contain every line once", pool.map { it.id }.toSet(), firstCycle.toSet())
        assertEquals("no duplicates within a cycle", 8, firstCycle.distinct().size)
    }

    @Test
    fun `a cycle boundary never produces a back-to-back repeat`() {
        // The leaky case: the pool empties and immediately re-draws the line that emptied it.
        // Run many cycles over a deliberately tiny pool, where the odds of that are highest.
        val pool = (1..3).map { line("l$it") }
        val rotation = GuiltRotation(Random(7))

        val seen = (1..300).map { rotation.pick(pool)!!.id }

        seen.zipWithNext().forEachIndexed { i, (a, b) ->
            assertNotEquals("repeat at index $i in $seen", a, b)
        }
    }

    @Test
    fun `weights bias the draw across cycles`() {
        // Within one cycle every line appears once regardless of weight — weight controls the
        // ORDER lines come out in. So a heavy line should, over many cycles, appear EARLY in
        // its cycle far more often than a light one.
        val heavy = line("heavy", weight = 20)
        val light = line("light", weight = 1)
        val filler = (1..3).map { line("f$it", weight = 1) }
        val pool = listOf(heavy, light) + filler
        val rotation = GuiltRotation(Random(42))

        var heavyFirst = 0
        var lightFirst = 0
        repeat(400) {
            val first = rotation.pick(pool)!!.id           // first draw of a cycle
            repeat(pool.size - 1) { rotation.pick(pool) }  // drain the rest of the cycle
            if (first == "heavy") heavyFirst++
            if (first == "light") lightFirst++
        }

        assertTrue(
            "weight 20 should lead its cycle far more often than weight 1 (heavy=$heavyFirst light=$lightFirst)",
            heavyFirst > lightFirst * 3,
        )
    }

    @Test
    fun `avoid keeps two surfaces from showing the same line back to back`() {
        val pool = (1..5).map { line("l$it") }
        val rotation = GuiltRotation(Random(3))

        repeat(50) {
            val justShownElsewhere = "l3"
            assertNotEquals(justShownElsewhere, rotation.pick(pool, avoid = justShownElsewhere)!!.id)
        }
    }

    @Test
    fun `a single-line pack repeats rather than returning nothing`() {
        // Degenerate but real: a remote pack could ship one line. Showing it twice beats
        // rendering a blank block screen.
        val only = listOf(line("solo"))
        val rotation = GuiltRotation(Random(5))

        assertEquals("solo", rotation.pick(only)!!.id)
        assertEquals("solo", rotation.pick(only, avoid = "solo")!!.id)
    }

    @Test
    fun `zero and negative weights cannot break the draw`() {
        // A malformed pack must not throw inside the accessibility service.
        val pool = listOf(line("a", weight = 0), line("b", weight = -5))
        val rotation = GuiltRotation(Random(11))

        val picked = (1..20).map { rotation.pick(pool)!!.id }
        assertTrue(picked.toSet().containsAll(setOf("a", "b")))
    }

    @Test
    fun `reset restores a fresh session`() {
        val pool = (1..4).map { line("l$it") }
        val rotation = GuiltRotation(Random(9))
        repeat(4) { rotation.pick(pool) }   // exhaust the cycle

        rotation.reset()

        val afterReset = (1..4).map { rotation.pick(pool)!!.id }
        assertEquals("a reset session cycles all lines again", 4, afterReset.distinct().size)
    }
}
