package com.scrollkiller

import com.scrollkiller.challenge.ChallengeRegistry
import com.scrollkiller.challenge.ChallengeType
import com.scrollkiller.challenge.SensorStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // The gap between the declared enum and the implemented set is the whole point of having
        // both — it is the written menu the next challenge picks from. If this ever becomes empty,
        // either everything is built (update this test) or someone widened the set without
        // writing the sensor code.
        val declared = SensorStrategy.entries.toSet()
        assertTrue(
            "IMPLEMENTED must be a subset of the declared strategies",
            ChallengeRegistry.IMPLEMENTED.all { it in declared },
        )
        assertEquals(
            "the step strategies are what D50 built",
            setOf(SensorStrategy.STEP_EVENTS, SensorStrategy.STEP_CUMULATIVE),
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
    fun `the walk challenge is the one that ships`() {
        // Pinned by name: this session's scope was ONE challenge end to end, and a second spec
        // appearing without its acceptance runs is exactly the thing to catch.
        assertEquals(1, ChallengeRegistry.enabled.size)
        val walk = ChallengeRegistry.forId("walk_20")
        assertNotNull("walk_20 must exist", walk)
        assertEquals(ChallengeType.WALK, walk!!.type)
        assertEquals(20, walk.target)
        assertEquals(SensorStrategy.STEP_EVENTS, walk.sensorStrategy)
    }

    @Test
    fun `default is the first enabled spec, and lookup rejects unknown ids`() {
        assertEquals(ChallengeRegistry.enabled.first(), ChallengeRegistry.default)
        assertNull(ChallengeRegistry.forId("jump_10"))
        assertNull(ChallengeRegistry.forId(""))
    }
}
