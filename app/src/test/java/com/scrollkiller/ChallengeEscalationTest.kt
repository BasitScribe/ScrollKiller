package com.scrollkiller

import com.scrollkiller.challenge.ChallengeEscalation
import com.scrollkiller.challenge.ChallengeRegistry
import com.scrollkiller.challenge.EscalationCurve
import com.scrollkiller.challenge.SensorStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalating punishment curve (D83).
 *
 * The load-bearing test in here is [the cap holds for any uses at all] — not because a number is
 * pretty, but because an uncapped ladder produces a challenge nobody can finish, and the only way
 * to buy time being impossible is the spirit of invariant 6 failing while its letter is kept. That
 * is worth pinning against arithmetic (overflow), against bad persisted data, and structurally
 * across every enabled spec so the next challenge added inherits the guarantee.
 */
class ChallengeEscalationTest {

    private val walk = ChallengeRegistry.forId("walk_20")!!
    private val jump = ChallengeRegistry.forId("jump_10")!!
    private val faceDown = ChallengeRegistry.forId("face_down_30")!!
    private val forehead = ChallengeRegistry.forId("forehead_30")!!

    /* ------------------------------------------------------------------------------------- */
    /* The ladders                                                                           */
    /* ------------------------------------------------------------------------------------- */

    @Test
    fun `an unused challenge asks for exactly its base target`() {
        // The first block of a fresh day must be the challenge as written. If this drifts, every
        // spec's KDoc and every prompt string is quietly lying about what the app asks for.
        ChallengeRegistry.enabled.forEach { spec ->
            assertEquals(
                "${spec.id} at zero uses must be its base target",
                spec.target,
                ChallengeEscalation.targetFor(spec, 0),
            )
        }
    }

    @Test
    fun `walk steps by ten and stops at eighty`() {
        assertEquals(listOf(20, 30, 40, 50, 60, 70, 80), ladder(walk, 7))
        assertEquals("the eighth reprieve must not move it", 80, ChallengeEscalation.targetFor(walk, 8))
    }

    @Test
    fun `jump steps by ten and stops at forty`() {
        // Forty is the lowest cap of the four in absolute terms and that is correct — it is the
        // most aerobic thing the app asks for, and D9's anti-uninstall principle applies to the
        // mechanic (D50). PLUS_TEN rather than DOUBLE for the same reason: doubling would reach
        // eighty by the fourth block.
        assertEquals(listOf(10, 20, 30, 40), ladder(jump, 4))
        assertEquals(40, ChallengeEscalation.targetFor(jump, 9))
    }

    @Test
    fun `both holds double and stop at two minutes`() {
        listOf(faceDown, forehead).forEach { hold ->
            assertEquals("${hold.id} ladder", listOf(30, 60, 120), ladder(hold, 3))
            // 240 would be the next rung if the cap were not there. It must not be reachable:
            // D54's anti-cheat is reset-on-break, so failure probability compounds with duration
            // and a four-minute hold surrenders everything to one twitch at 3:59 — with the ring
            // pointing at a table, so the user cannot even see it happen.
            assertEquals("${hold.id} must not reach 240s", 120, ChallengeEscalation.targetFor(hold, 3))
        }
    }

    /* ------------------------------------------------------------------------------------- */
    /* The cap — invariant 6's arithmetic                                                    */
    /* ------------------------------------------------------------------------------------- */

    @Test
    fun `the cap holds for any uses at all, including garbage`() {
        // `uses` comes from a persisted preference, which means it can arrive from a restored
        // backup, a build whose curve differed, or a file somebody edited on a rooted device. The
        // function is TOTAL by construction rather than by the caller behaving, for the same
        // reason BlockLimits.clampLimit is applied on the way in AND the way out of storage.
        val hostile = listOf(1, 5, 31, 32, 33, 64, 1_000, Int.MAX_VALUE)
        ChallengeRegistry.enabled.forEach { spec ->
            val cap = ChallengeEscalation.capFor(spec)
            hostile.forEach { uses ->
                val target = ChallengeEscalation.targetFor(spec, uses)
                assertTrue(
                    "${spec.id} at $uses uses produced $target, above its cap of $cap",
                    target <= cap,
                )
                // The overflow case specifically: `target shl uses` goes NEGATIVE around 27 uses,
                // and a naive coerceAtMost would accept that happily — producing a challenge that
                // completes instantly, which is the same feature failing in the other direction.
                assertTrue(
                    "${spec.id} at $uses uses produced $target, below its base of ${spec.target}",
                    target >= spec.target,
                )
            }
        }
    }

    @Test
    fun `negative uses cannot make a challenge easier than its base`() {
        // Not reachable from the current writer, which only ever increments. Pinned anyway: a
        // preference read defaulting wrong, or a future decay rule that subtracts, must not be
        // able to hand somebody "walk 0 steps" — a free reprieve is exactly what D77 deleted.
        ChallengeRegistry.enabled.forEach { spec ->
            assertEquals(spec.target, ChallengeEscalation.targetFor(spec, -1))
            assertEquals(spec.target, ChallengeEscalation.targetFor(spec, Int.MIN_VALUE))
        }
    }

    @Test
    fun `every enabled spec caps at four times its base`() {
        // Structural, so the next challenge added inherits the guarantee instead of needing its
        // own line here. A per-spec maxTarget would be a second number that could drift from the
        // base it bounds; this cannot.
        ChallengeRegistry.enabled.forEach { spec ->
            assertEquals(
                "${spec.id} cap",
                spec.target * ChallengeEscalation.CAP_MULTIPLE,
                ChallengeEscalation.capFor(spec),
            )
            assertEquals(
                "${spec.id} must actually REACH its cap — a ladder that stops short is a cap that lies",
                ChallengeEscalation.capFor(spec),
                ChallengeEscalation.targetFor(spec, 1_000),
            )
        }
    }

    @Test
    fun `escalation is monotonic - a reprieve never makes the next one cheaper`() {
        ChallengeRegistry.enabled.forEach { spec ->
            (0 until 12).forEach { uses ->
                assertTrue(
                    "${spec.id} got easier between $uses and ${uses + 1} uses",
                    ChallengeEscalation.targetFor(spec, uses + 1) >=
                        ChallengeEscalation.targetFor(spec, uses),
                )
            }
        }
    }

    /* ------------------------------------------------------------------------------------- */
    /* escalated() — the whole-spec swap the call sites depend on                            */
    /* ------------------------------------------------------------------------------------- */

    @Test
    fun `escalated changes the target and nothing else`() {
        // This is what lets the chooser label, the prompt, the ring and ChallengeProgress all keep
        // reading `spec.target` without knowing escalation exists. If it altered the id, the
        // surprise-me exclusion and every greppable log line would break across rungs.
        val raised = ChallengeEscalation.escalated(walk, ownUses = 3)
        assertEquals(50, raised.target)
        assertEquals(walk.id, raised.id)
        assertEquals(walk.type, raised.type)
        assertEquals(walk.sensorStrategy, raised.sensorStrategy)
        assertEquals(walk.promptRes, raised.promptRes)
        assertEquals(walk.unit, raised.unit)
        assertEquals(walk.escalation, raised.escalation)
        assertEquals(walk, ChallengeEscalation.escalated(walk, ownUses = 0))
    }

    @Test
    fun `escalating an already escalated spec would compound - so it is only ever done once`() {
        // Not a bug being asserted, a REASON being pinned. The target is the base the curve reads
        // from, so applying the curve twice reads the raised number as a base and runs away.
        // OverlayController.offerable() is the single application point precisely because of this.
        val once = ChallengeEscalation.escalated(jump, ownUses = 1)
        assertEquals(20, once.target)
        assertEquals("re-escalating compounds — 30, not 20", 30, ChallengeEscalation.targetFor(once, 1))
    }

    /* ------------------------------------------------------------------------------------- */
    /* The hybrid charge — rotation cannot dodge the ladder                                  */
    /* ------------------------------------------------------------------------------------- */

    @Test
    fun `reprieves earned elsewhere are charged at half`() {
        assertEquals(2, ChallengeEscalation.GLOBAL_DIVISOR)
        assertEquals(0, ChallengeEscalation.effectiveUses(ownUses = 0, otherUses = 0))
        // Rounds DOWN, deliberately: the first reprieve elsewhere is free here. Without that, one
        // completion would raise all four challenges at once and the model would be pure-global.
        assertEquals(0, ChallengeEscalation.effectiveUses(ownUses = 0, otherUses = 1))
        assertEquals(1, ChallengeEscalation.effectiveUses(ownUses = 0, otherUses = 2))
        assertEquals(1, ChallengeEscalation.effectiveUses(ownUses = 0, otherUses = 3))
        assertEquals(2, ChallengeEscalation.effectiveUses(ownUses = 0, otherUses = 4))
        assertEquals(5, ChallengeEscalation.effectiveUses(ownUses = 3, otherUses = 4))
    }

    @Test
    fun `rotating the whole set still raises every challenge`() {
        // The hole this closes. One full lap of four: each challenge has one use of its own and
        // three elsewhere, so each is charged 1 + 3/2 = 2 rather than the 1 pure per-challenge
        // state would have charged. Rotation is no longer a discount.
        val lap = ChallengeRegistry.enabled.map { spec ->
            spec.id to ChallengeEscalation.escalated(spec, ownUses = 1, otherUses = 3).target
        }.toMap()
        assertEquals(40, lap["walk_20"])
        assertEquals(30, lap["jump_10"])
        assertEquals(120, lap["face_down_30"])
        assertEquals(120, lap["forehead_30"])
    }

    @Test
    fun `the challenge being leaned on is always strictly the hardest`() {
        // The reason per-challenge state is kept at all rather than collapsing to one global
        // counter. Four reprieves all spent on walk: walk is charged 4, everything else 4/2 = 2.
        val leanedOn = ChallengeEscalation.escalated(walk, ownUses = 4, otherUses = 0).target
        val rotated = ChallengeEscalation.escalated(walk, ownUses = 1, otherUses = 3).target
        assertTrue(
            "leaning on walk ($leanedOn) must cost more than rotating to it ($rotated)",
            leanedOn > rotated,
        )
        assertEquals(60, leanedOn)

        // And against its neighbours at the same moment: the counting challenges can be compared
        // directly. The holds are already at their cap by this point, which is the DOUBLE curve
        // behaving, not the hybrid failing.
        val jumpAlongside = ChallengeEscalation.escalated(jump, ownUses = 0, otherUses = 4).target
        assertEquals(30, jumpAlongside)
        assertTrue("walk was the one leaned on", leanedOn > jumpAlongside)
    }

    @Test
    fun `a single option user is charged the same rate as a four option user`() {
        // The equity property, and the correction to D83's first draft. Rotation dilution was only
        // ever available to somebody who could physically do several challenges — a user with no
        // pedometer who cannot jump at 2am had one option and no discount at all. After four
        // reprieves the single-option user is at the cap either way; what changed is that the
        // four-option user no longer sits far below them for the same number of reprieves.
        val singleOption = ChallengeEscalation.escalated(faceDown, ownUses = 4, otherUses = 0).target
        val fourOption = ChallengeEscalation.escalated(faceDown, ownUses = 1, otherUses = 3).target
        assertEquals(120, singleOption)
        assertEquals(120, fourOption)
    }

    @Test
    fun `effectiveUses is total against hostile persisted values`() {
        // Both operands come from SharedPreferences. Own + other/2 overflows Int at the top of the
        // range, and a naive Int sum would wrap NEGATIVE — which coerceAtLeast(0) inside targetFor
        // would then quietly turn into a base-target challenge, i.e. a free reprieve.
        assertEquals(Int.MAX_VALUE, ChallengeEscalation.effectiveUses(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(0, ChallengeEscalation.effectiveUses(-1, -1))
        assertEquals(0, ChallengeEscalation.effectiveUses(Int.MIN_VALUE, Int.MIN_VALUE))
        assertEquals(5, ChallengeEscalation.effectiveUses(ownUses = 5, otherUses = -10))

        // And the cap still holds through the composed path, which is the guarantee that actually
        // matters — no persisted value can produce an unfinishable challenge.
        ChallengeRegistry.enabled.forEach { spec ->
            assertEquals(
                "${spec.id} must still cap through escalated()",
                ChallengeEscalation.capFor(spec),
                ChallengeEscalation.escalated(spec, Int.MAX_VALUE, Int.MAX_VALUE).target,
            )
        }
    }

    /* ------------------------------------------------------------------------------------- */
    /* Curve assignment                                                                      */
    /* ------------------------------------------------------------------------------------- */

    @Test
    fun `holds double and counts step`() {
        // Structural rather than per-spec, so the next hold added cannot get the counting curve by
        // default — the same shape as the ProgressUnit test next door. A hold on PLUS_TEN would
        // escalate 30 → 40 → 50s, which is not what "the challenge gets harder" means for a
        // duration; a count on DOUBLE reaches numbers nobody walks.
        val holds = setOf(
            SensorStrategy.ORIENTATION_HOLD,
            SensorStrategy.PROXIMITY_HOLD,
            SensorStrategy.TILT_BALANCE,
        )
        ChallengeRegistry.enabled.forEach { spec ->
            val expected =
                if (spec.sensorStrategy in holds) EscalationCurve.DOUBLE else EscalationCurve.PLUS_TEN
            assertEquals("${spec.id} curve", expected, spec.escalation)
        }
    }

    @Test
    fun `the D84 challenges escalate on the same ladders`() {
        // The reason Piece 2 was sequenced AFTER escalation: each new spec declares its curve at
        // birth and inherits the cap, rather than being retrofitted afterwards.
        val shake = ChallengeRegistry.forId("shake_30")!!
        assertEquals(listOf(30, 40, 50, 60, 70, 80, 90, 100, 110, 120), ladder(shake, 10))
        assertEquals(120, ChallengeEscalation.targetFor(shake, 50))

        val flip = ChallengeRegistry.forId("flip_10")!!
        assertEquals(listOf(10, 20, 30, 40), ladder(flip, 4))
        assertEquals(40, ChallengeEscalation.targetFor(flip, 50))

        val balance = ChallengeRegistry.forId("balance_20")!!
        assertEquals(listOf(20, 40, 80), ladder(balance, 3))
        // 160s of balancing a phone on an open palm is not a challenge, it is a wall. The shared
        // cap is what stops the DOUBLE curve getting there.
        assertEquals(80, ChallengeEscalation.targetFor(balance, 50))
    }

    @Test
    fun `the cap is a real ceiling and not a rounding accident`() {
        // Four is the number the ADR argues for; if someone changes it, they should have to come
        // here and change it deliberately rather than discover it by a device run.
        assertEquals(4, ChallengeEscalation.CAP_MULTIPLE)
        assertEquals(10, ChallengeEscalation.COUNT_STEP)
        assertTrue("a cap at or below 1x would make escalation a no-op", ChallengeEscalation.CAP_MULTIPLE > 1)
        assertNotNull(ChallengeRegistry.default)
    }

    /** [spec]'s target at uses 0 until [rungs]. */
    private fun ladder(spec: com.scrollkiller.challenge.ChallengeSpec, rungs: Int): List<Int> =
        (0 until rungs).map { ChallengeEscalation.targetFor(spec, it) }
}
