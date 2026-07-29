package com.scrollkiller

import com.scrollkiller.challenge.ChallengeRegistry
import com.scrollkiller.challenge.ChallengeType
import com.scrollkiller.challenge.ProgressUnit
import com.scrollkiller.challenge.SensorStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Guards the challenge registry's safety invariants, in the same spirit as [PlatformRegistryTest].
 *
 * The one that matters: a spec whose [SensorStrategy] the engine does not implement would ship a
 * challenge that can never complete — the user walks, the ring never moves, and the only way out
 * is the Back button they were told they did not need. That fails the build here instead.
 */
class ChallengeRegistryTest {

    @Test
    fun `every enabled spec uses a strategy the engine actually implements`() {
        ChallengeRegistry.enabled.forEach { spec ->
            assertTrue(
                "${spec.id} declares ${spec.sensorStrategy}, which nothing implements — it could " +
                    "never complete. Wire the sensor or don't enable the spec.",
                spec.sensorStrategy in ChallengeRegistry.IMPLEMENTED,
            )
        }
    }

    @Test
    fun `IMPLEMENTED is honest about what is not built yet`() {
        // The gap between the declared enum and the implemented set is the written menu the next
        // challenge picks from. It is currently EMPTY — every declared strategy is built, which is
        // what "the challenge suite is complete" means. That is a legitimate state, not a bug.
        //
        // The subset assertion below is the one that must never break: it catches someone widening
        // IMPLEMENTED past what the enum declares. When the NEXT strategy is declared ahead of its
        // sensor code, this equality is what will fail, and the fix is to narrow the expectation
        // here — not to add the unbuilt strategy to IMPLEMENTED.
        val declared = SensorStrategy.entries.toSet()
        assertTrue(
            "IMPLEMENTED must be a subset of the declared strategies",
            ChallengeRegistry.IMPLEMENTED.all { it in declared },
        )
        assertEquals(
            "steps D50, accel peaks D53, orientation hold D54, proximity hold D55 — the suite is complete",
            declared,
            ChallengeRegistry.IMPLEMENTED,
        )
    }

    @Test
    fun `every spec has a positive target and a prompt`() {
        ChallengeRegistry.enabled.forEach { spec ->
            assertTrue("${spec.id} needs a target above zero", spec.target > 0)
            // promptRes is a format string taking the target; a zero resource id would throw at
            // the moment the challenge is shown, on an overlay over someone else's app.
            assertTrue("${spec.id} has no prompt string", spec.promptRes != 0)
            assertTrue("${spec.id} has a blank id", spec.id.isNotBlank())
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = ChallengeRegistry.enabled.map { it.id }
        assertEquals("duplicate challenge id in $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `all four challenges ship - the suite is complete`() {
        // Pinned by name: each session's scope was ONE challenge end to end, and a spec appearing
        // without its acceptance runs is exactly the thing to catch. All four are now verified, so
        // this count going UP means a fifth arrived without its device runs.
        assertEquals(4, ChallengeRegistry.enabled.size)

        val walk = ChallengeRegistry.forId("walk_20")
        assertNotNull("walk_20 must exist", walk)
        assertEquals(ChallengeType.WALK, walk!!.type)
        assertEquals(20, walk.target)
        assertEquals(SensorStrategy.STEP_EVENTS, walk.sensorStrategy)
        assertEquals(ProgressUnit.COUNT, walk.unit)

        val jump = ChallengeRegistry.forId("jump_10")
        assertNotNull("jump_10 must exist", jump)
        assertEquals(ChallengeType.JUMP, jump!!.type)
        assertEquals(10, jump.target)
        assertEquals(SensorStrategy.ACCEL_PEAKS, jump.sensorStrategy)
        assertEquals(ProgressUnit.COUNT, jump.unit)

        val faceDown = ChallengeRegistry.forId("face_down_30")
        assertNotNull("face_down_30 must exist", faceDown)
        assertEquals(ChallengeType.FACE_DOWN, faceDown!!.type)
        assertEquals(30, faceDown.target)
        assertEquals(SensorStrategy.ORIENTATION_HOLD, faceDown.sensorStrategy)
        // A hold whose target were read as a COUNT would render "0 / 30" and never tick — the ring
        // would look broken for 30 seconds.
        assertEquals("a 30-second hold must be measured in SECONDS", ProgressUnit.SECONDS, faceDown.unit)

        val forehead = ChallengeRegistry.forId("forehead_30")
        assertNotNull("forehead_30 must exist", forehead)
        assertEquals(ChallengeType.FOREHEAD, forehead!!.type)
        assertEquals(30, forehead.target)
        // Its OWN strategy, not a flavour of ORIENTATION_HOLD — sharing would have let forehead into
        // IMPLEMENTED for free the moment face-down landed, defeating the gate.
        assertEquals(SensorStrategy.PROXIMITY_HOLD, forehead.sensorStrategy)
        assertEquals(ProgressUnit.SECONDS, forehead.unit)

        assertEquals(
            "every ChallengeType must now have an enabled spec",
            ChallengeType.entries.toSet(),
            ChallengeRegistry.enabled.map { it.type }.toSet(),
        )
    }

    @Test
    fun `every hold spec is measured in seconds`() {
        // Structural rather than per-spec, so the next hold added cannot forget its unit. A hold whose
        // target were read as a COUNT would render "0 / 30" and never tick — the ring would look
        // broken for thirty seconds.
        val holds = setOf(SensorStrategy.ORIENTATION_HOLD, SensorStrategy.PROXIMITY_HOLD)
        val found = ChallengeRegistry.enabled.filter { it.sensorStrategy in holds }
        assertEquals("both holds should be enabled by now", 2, found.size)
        found.forEach {
            assertEquals("${it.id} is a hold and must use SECONDS", ProgressUnit.SECONDS, it.unit)
        }
    }

    @Test
    fun `default is the first enabled spec, and lookup rejects unknown ids`() {
        assertEquals(ChallengeRegistry.enabled.first(), ChallengeRegistry.default)
        // Deliberately a NEVER-VALID id rather than the next unbuilt challenge's. Previous revisions
        // used whichever spec was coming next ("jump_10", then "face_down_30", then "forehead_30")
        // and this test broke on every increment for no reason — the assertion is about lookup
        // rejecting nonsense, not about what is unbuilt.
        assertNull(ChallengeRegistry.forId("no_such_challenge"))
        assertNull(ChallengeRegistry.forId(""))
    }

    /* ------------------------------------------------------------------------------------- */
    /* Surprise me (D53)                                                                     */
    /* ------------------------------------------------------------------------------------- */

    @Test
    fun `surpriseMe never returns the challenge it was told to avoid`() {
        val all = ChallengeRegistry.enabled
        // Asserts the EXCLUSION, not which specific challenge comes back — an earlier version pinned
        // the survivor by name, which only held while the pool had exactly two entries and broke the
        // moment a third challenge shipped. Seeded across many draws rather than once: the guarantee
        // is about every branch of the random pick, and a single draw would pass by luck.
        all.forEach { avoided ->
            repeat(200) { seed ->
                val picked = ChallengeRegistry.surpriseMe(all, avoid = avoided.id, random = Random(seed))
                assertNotNull(picked)
                assertNotEquals("must have avoided ${avoided.id}", avoided.id, picked!!.id)
            }
        }
    }

    @Test
    fun `surpriseMe repeats rather than returning nothing when it is the only option`() {
        // Repeating beats rendering nothing — the same precedence GuiltRotation.pick applies. A
        // device with one available challenge must still be able to use "Surprise me" if it is shown.
        val only = ChallengeRegistry.enabled.take(1)
        val picked = ChallengeRegistry.surpriseMe(only, avoid = only.first().id)
        assertEquals(only.first(), picked)
    }

    @Test
    fun `surpriseMe on an empty candidate list is null, not a crash`() {
        // Reachable if availability drops to zero between the chooser opening and a tap. Returning
        // null keeps the user on the chooser; throwing would take down the accessibility service.
        assertNull(ChallengeRegistry.surpriseMe(emptyList()))
        assertNull(ChallengeRegistry.surpriseMe(emptyList(), avoid = "walk_20"))
    }

    @Test
    fun `surpriseMe draws every candidate given enough seeds`() {
        // Guards against a picker that technically avoids the last id but always returns the same
        // one otherwise — which would make "Surprise me" a third button for one fixed challenge.
        val all = ChallengeRegistry.enabled
        val seen = (0 until 200)
            .mapNotNull { ChallengeRegistry.surpriseMe(all, random = Random(it))?.id }
            .toSet()
        assertEquals("every enabled challenge must be reachable", all.map { it.id }.toSet(), seen)
    }
}
