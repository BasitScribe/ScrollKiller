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

    private fun poolSize(tier: GuiltTier) =
        pack.pool(GuiltSurface.AMBIENT, tier, GuiltLocale.DEFAULT).size

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
        // Read as: "tier T sustains 7-day no-repeat for a user doing up to N scrolls/day."
        assertEquals(18, poolSize(GuiltTier.MILD))
        assertEquals(18, poolSize(GuiltTier.MEDIUM))
        assertEquals(18, poolSize(GuiltTier.STRONG))
        assertEquals(18, poolSize(GuiltTier.EXTREME))

        // Tiers 1-2: one fire/day, so 18 lines covers any user at all. Target already met.
        assertTrue(GuiltPoolMath.sustainedScrollsPerDay(GuiltTier.MILD, 18) >= 2_000)
        assertTrue(GuiltPoolMath.sustainedScrollsPerDay(GuiltTier.MEDIUM, 18) >= 2_000)

        // Tier 3: 5 fires/day needs 35. At 18 it runs out partway through the week for ANY user
        // who reaches 100 — this is a real, permanent shortfall, and a small one to fix.
        assertTrue(
            "tier 3 should still be short at 18 lines — if this passes, update the ADR",
            GuiltPoolMath.requiredPool(GuiltTier.STRONG, 800) > poolSize(GuiltTier.STRONG),
        )

        // Tier 4: 18 lines sustains only a very light day. Anyone habitually past ~200 burns the
        // week's supply and lands in the least-recently-shown fallback.
        val tier4Covers = GuiltPoolMath.sustainedScrollsPerDay(GuiltTier.EXTREME, 18)
        assertTrue(
            "tier 4 at 18 lines covers only up to $tier4Covers scrolls/day",
            tier4Covers in 150..260,
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
        val short = GuiltTier.entries.filter { poolSize(it) < GuiltPoolMath.targetPool(it) }
        val report = GuiltTier.entries.joinToString("\n") { tier ->
            "  $tier: ${poolSize(tier)}/${GuiltPoolMath.targetPool(tier)} lines, " +
                "sustains ${GuiltPoolMath.sustainedScrollsPerDay(tier, poolSize(tier))} scrolls/day"
        }
        assertEquals(
            "pack coverage changed — update the pinned sizes above and D48:\n$report",
            listOf(GuiltTier.STRONG, GuiltTier.EXTREME),
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
