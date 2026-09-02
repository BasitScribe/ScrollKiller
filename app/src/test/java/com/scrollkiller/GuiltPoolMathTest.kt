package com.scrollkiller

import com.scrollkiller.guilt.GuiltLocale
import com.scrollkiller.guilt.GuiltPack
import com.scrollkiller.guilt.GuiltPackParser
import com.scrollkiller.guilt.GuiltPoolMath
import com.scrollkiller.guilt.GuiltSurface
import com.scrollkiller.guilt.GuiltTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How much content the cadence eats (D46, flattened by D48), and whether the shipped pack can
 * feed it for a week.
 *
 * This test exists to make an uncomfortable number impossible to ignore. The 7-day no-repeat rule
 * (D47) has a graceful fallback, and a graceful fallback is exactly the kind of thing that lets
 * an under-supplied pack look fine forever. So the arithmetic is pinned here, and
 * [`the shipped pack's coverage is what we think it is`] states in one place who each tier
 * currently serves against the target it is being held to.
 */
class GuiltPoolMathTest {

    private val pack: GuiltPack = GuiltPackParser.parse(
        listOf("src/main/assets/guilt_pack.json", "app/src/main/assets/guilt_pack.json")
            .map(::File).first { it.exists() }.readText(),
    )!!

    /**
     * The pool a FREE user sees — which is the only one the coverage targets may be measured
     * against (D85). Premium lines are additive on top and a free user provably never reaches
     * them, so counting them here would let the pack claim a coverage most users do not have.
     */
    private fun poolSize(tier: GuiltTier) =
        pack.pool(GuiltSurface.AMBIENT, tier, GuiltLocale.DEFAULT).size

    /** Everything at [tier], premium included. Reported alongside, never used as the target. */
    private fun poolSizeWithPremium(tier: GuiltTier) =
        pack.pool(GuiltSurface.AMBIENT, tier, GuiltLocale.DEFAULT, includePremium = true).size

    @Test
    fun `consumption per tier is what the cadence implies`() {
        // Derived by running the REAL GuiltFiring, so this cannot drift from what ships. If the
        // schedule or the tier thresholds are retuned, this fails and the pool targets below have
        // to be revisited — which is the point.
        val table = mapOf(
            150 to mapOf(GuiltTier.MILD to 1, GuiltTier.MEDIUM to 1, GuiltTier.STRONG to 5, GuiltTier.EXTREME to 1),
            300 to mapOf(GuiltTier.MILD to 1, GuiltTier.MEDIUM to 1, GuiltTier.STRONG to 5, GuiltTier.EXTREME to 18),
            500 to mapOf(GuiltTier.MILD to 1, GuiltTier.MEDIUM to 1, GuiltTier.STRONG to 5, GuiltTier.EXTREME to 57),
            800 to mapOf(GuiltTier.MILD to 1, GuiltTier.MEDIUM to 1, GuiltTier.STRONG to 5, GuiltTier.EXTREME to 117),
        )
        table.forEach { (scrolls, expected) ->
            assertEquals("fires/day at $scrolls scrolls", expected, GuiltPoolMath.firesPerDay(scrolls))
        }
    }

    @Test
    fun `tiers 1 and 2 fire exactly once a day, forever`() {
        // Structural, not incidental: they sit below the schedule's first row, so they only ever
        // fire on their tier crossing. Seven lines each would sustain a week — which is why they
        // are the two tiers nobody needs to write more content for.
        listOf(150, 400, 800, 2_000).forEach { scrolls ->
            val fires = GuiltPoolMath.firesPerDay(scrolls)
            assertEquals(1, fires[GuiltTier.MILD])
            assertEquals(1, fires[GuiltTier.MEDIUM])
        }
    }

    @Test
    fun `tier 3 is capped at five a day because its band is finite`() {
        // 100..149 at every 10 scrolls. It cannot consume more however hard someone scrolls, so
        // 35 lines sustains a week for ANY user — a fixed, reachable target.
        listOf(150, 400, 800, 2_000).forEach { scrolls ->
            assertEquals(5, GuiltPoolMath.firesPerDay(scrolls)[GuiltTier.STRONG])
        }
        assertEquals(35, GuiltPoolMath.requiredPool(GuiltTier.STRONG, scrollsPerDay = 800))
    }

    @Test
    fun `tier 4 is unbounded and is where the content problem lives`() {
        // 150+ never ends, so consumption grows with the count and the required pool grows with
        // it — flattening the cadence changed the SLOPE, not the fact. These are the numbers that
        // say "18 lines is not a week", and that 150 is a choice about who we cover, not a fix.
        assertEquals(126, GuiltPoolMath.requiredPool(GuiltTier.EXTREME, scrollsPerDay = 300))
        assertEquals(399, GuiltPoolMath.requiredPool(GuiltTier.EXTREME, scrollsPerDay = 500))
        assertEquals(819, GuiltPoolMath.requiredPool(GuiltTier.EXTREME, scrollsPerDay = 800))
    }

    @Test
    fun `flattening the cadence is what made the middle of the curve affordable`() {
        // The point of D48, stated as an assertion rather than left in an ADR. Under the old
        // schedule a 500/day user needed 434 tier-4 lines and an 800/day user 1,372; the target
        // is 150. Flattening does not rescue the ceiling — it moves the affordable band up to
        // where the users actually are.
        assertTrue(
            "no count below 320 should be cheaper than the target — that is what 150 buys",
            GuiltPoolMath.requiredPool(GuiltTier.EXTREME, 320) <= GuiltPoolMath.targetPool(GuiltTier.EXTREME),
        )
        assertTrue(
            "the ceiling is still unaffordable, and the ADR must not pretend otherwise",
            GuiltPoolMath.requiredPool(GuiltTier.EXTREME, 800) > 5 * GuiltPoolMath.targetPool(GuiltTier.EXTREME),
        )
    }

    @Test
    fun `the expansion targets are the right size for the cadence`() {
        // The targets are a content commitment, so they have to be checked against the engine
        // rather than just written down. If a future cadence edit makes 40 stop closing tier 3,
        // or moves what 150 covers in tier 4, this is where it surfaces.

        // T1/T2: one fire/day forever, so the shipped 18 covers any user at any rate.
        listOf(GuiltTier.MILD, GuiltTier.MEDIUM).forEach { tier ->
            assertEquals(18, GuiltPoolMath.targetPool(tier))
            assertTrue(GuiltPoolMath.sustainedScrollsPerDay(tier, GuiltPoolMath.targetPool(tier)) >= 2_000)
        }

        // T3: 40 must CLOSE the tier — not "cover the heavy user", cover everyone, forever,
        // because consumption is capped at 5/day by the band's finite width.
        assertEquals(40, GuiltPoolMath.targetPool(GuiltTier.STRONG))
        assertTrue(
            "40 must exceed tier 3's permanent ceiling of 35",
            GuiltPoolMath.requiredPool(GuiltTier.STRONG, 2_000) <= GuiltPoolMath.targetPool(GuiltTier.STRONG),
        )

        // T4: 150 is sized against the realistic heavy user, ~320 scrolls/day. Asserted as a
        // band so a small cadence tweak reports honestly instead of failing on an exact number.
        assertEquals(150, GuiltPoolMath.targetPool(GuiltTier.EXTREME))
        val covers = GuiltPoolMath.sustainedScrollsPerDay(GuiltTier.EXTREME, GuiltPoolMath.targetPool(GuiltTier.EXTREME))
        assertTrue(
            "tier 4's 150-line target covers $covers scrolls/day — the ADR claims ~320",
            covers in 300..350,
        )
    }

    @Test
    fun `the shipped pack's coverage is what we think it is`() {
        // ONE place stating who each tier currently serves. Deliberately asserts the CURRENT
        // (inadequate) reality rather than the target, so it passes today and fails the moment
        // the pack changes — at which point these numbers get updated and the ADR with them.
        //
        // Read as: "tier T sustains 7-day no-repeat for a FREE user doing up to N scrolls/day."
        // Free-only, deliberately — see poolSize. D85 grew the pack 72 → 155; D86 grew it
        // 155 → 217, entirely in tier 4, which is the only tier this file still tracks as short.
        assertEquals(24, poolSize(GuiltTier.MILD))
        assertEquals(24, poolSize(GuiltTier.MEDIUM))
        assertEquals(40, poolSize(GuiltTier.STRONG))
        assertEquals(129, poolSize(GuiltTier.EXTREME))

        // Premium is additive on top and must never be counted toward a target.
        assertEquals(26, poolSizeWithPremium(GuiltTier.MILD))
        assertEquals(26, poolSizeWithPremium(GuiltTier.MEDIUM))
        assertEquals(45, poolSizeWithPremium(GuiltTier.STRONG))
        assertEquals(146, poolSizeWithPremium(GuiltTier.EXTREME))

        // Tiers 1-2: one fire/day, so anything past 7 covers any user at all. Long since met.
        assertTrue(GuiltPoolMath.sustainedScrollsPerDay(GuiltTier.MILD, poolSize(GuiltTier.MILD)) >= 2_000)
        assertTrue(GuiltPoolMath.sustainedScrollsPerDay(GuiltTier.MEDIUM, poolSize(GuiltTier.MEDIUM)) >= 2_000)

        // Tier 3 is CLOSED as of D85, and this assertion is the inverse of the one it replaced.
        // The band is finite (100..149 at every 10), so consumption caps at 5/day and 35 lines
        // sustains a week for ANY user at ANY scroll rate. At 40 free lines it is done — not
        // "covered for now", done, permanently, and no future scroll rate can reopen it.
        assertTrue(
            "tier 3 must now be CLOSED — free pool ${poolSize(GuiltTier.STRONG)} vs ceiling " +
                "${GuiltPoolMath.requiredPool(GuiltTier.STRONG, 2_000)}",
            GuiltPoolMath.requiredPool(GuiltTier.STRONG, 2_000) <= poolSize(GuiltTier.STRONG),
        )

        // Tier 4 is unbounded and remains the open one. 103 covered 270; 129 covers more of the
        // ~320/day heavy user the 150 target is set against. Still short, still the odometer.
        val tier4Covers = GuiltPoolMath.sustainedScrollsPerDay(GuiltTier.EXTREME, poolSize(GuiltTier.EXTREME))
        assertTrue(
            "tier 4 at ${poolSize(GuiltTier.EXTREME)} free lines covers up to $tier4Covers scrolls/day",
            tier4Covers in 270..340,
        )
    }

    @Test
    fun `the pack is still short of its targets, and this test says by how much`() {
        // THE ONE THE BUILD TRACKS. Content is being authored in parallel with the engine work,
        // so this asserts the gap rather than the goal: green while the pack is short, RED the
        // day a tier lands its target — at which point the assertion above is updated, this
        // tier's entry moves out of `short`, and the ADR records the pack that shipped.
        //
        // Failing the build on "not written yet" was considered and rejected in D47: it would
        // block every unrelated change until a content task nobody is mid-way through completes.
        // As of D85 tier 3 has LANDED its target and moved out of this list, which is exactly the
        // transition this assertion was written to force. EXTREME is the only one left, and D86
        // moved it from 48/150 to 103/150 without closing it — so the list is unchanged and the
        // pinned sizes above are what actually record the progress. That is the intended
        // behaviour of this pair of tests: this one is a LATCH on the set of open tiers, the one
        // above is the odometer.
        val short = GuiltTier.entries.filter { poolSize(it) < GuiltPoolMath.targetPool(it) }
        val report = GuiltTier.entries.joinToString("\n") { tier ->
            "  $tier: ${poolSize(tier)}/${GuiltPoolMath.targetPool(tier)} free lines " +
                "(${poolSizeWithPremium(tier)} with premium), " +
                "sustains ${GuiltPoolMath.sustainedScrollsPerDay(tier, poolSize(tier))} scrolls/day"
        }
        assertEquals(
            "pack coverage changed — update the pinned sizes above and D48/D85:\n$report",
            listOf(GuiltTier.EXTREME),
            short,
        )
    }

    @Test
    fun `sustainedScrollsPerDay is the inverse of requiredPool`() {
        GuiltTier.entries.forEach { tier ->
            val covers = GuiltPoolMath.sustainedScrollsPerDay(tier, poolSize = 40)
            assertTrue(
                "$tier: pool of 40 claims to cover $covers but needs more than that there",
                GuiltPoolMath.requiredPool(tier, covers) <= 40,
            )
        }
    }
}
