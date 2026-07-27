package com.scrollkiller

import com.scrollkiller.guilt.GuiltHistory
import com.scrollkiller.guilt.GuiltLocale
import com.scrollkiller.guilt.GuiltNow
import com.scrollkiller.guilt.GuiltPack
import com.scrollkiller.guilt.GuiltPackParser
import com.scrollkiller.guilt.GuiltSelector
import com.scrollkiller.guilt.GuiltSurface
import com.scrollkiller.guilt.GuiltTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The picker every surface shares, tested against the REAL bundled pack — the escalation,
 * freshness and no-repeat properties are only meaningful over the content that actually ships.
 *
 * Three properties matter most and none is visible by inspection:
 *  - ONE LINE AT A TIME. Home, the bubble nudge and the panel must not disagree.
 *  - ESCALATION. The line gets harsher as the count climbs, never the reverse.
 *  - NO REPEAT FOR SEVEN DAYS, and a graceful, LOUD degradation when the pool cannot manage it.
 */
class GuiltSelectorTest {

    private val pack: GuiltPack = GuiltPackParser.parse(
        listOf("src/main/assets/guilt_pack.json", "app/src/main/assets/guilt_pack.json")
            .map(::File).first { it.exists() }.readText(),
    )!!

    private val locale = GuiltLocale.DEFAULT

    /** A fixed wall-clock origin, so "seven days ago" is expressible. */
    private val t0 = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1_000

    /** Both clocks advanced past the D46 display gap, so it never interferes. */
    private fun at(dayOffset: Int, step: Int = 0) = GuiltNow(
        dayKey = "2026-07-%02d".format(27 + dayOffset),
        monotonicMs = 1_000L + (dayOffset * 1_000L + step) * 10_000L,
        wallMs = t0 + dayOffset * day + step * 60_000L,
    )

    private fun selector(
        history: GuiltHistory = GuiltHistory(),
        installId: String = "install-abc",
        onExhausted: ((GuiltSelector.PoolExhausted) -> Unit)? = null,
    ) = GuiltSelector(installId, history, onPoolExhausted = onExhausted)

    /* --- silence + escalation -------------------------------------------------------- */

    @Test
    fun `below the first threshold there is no line at all`() {
        val selector = selector()
        listOf(0, 30, 49).forEach { count ->
            assertNull("expected silence at $count", selector.current(pack, locale, count, at(0)))
        }
    }

    @Test
    fun `the line's intensity matches the count's tier at every threshold`() {
        val expectations = listOf(
            50 to GuiltTier.MILD, 69 to GuiltTier.MILD,
            70 to GuiltTier.MEDIUM, 99 to GuiltTier.MEDIUM,
            100 to GuiltTier.STRONG, 149 to GuiltTier.STRONG,
            150 to GuiltTier.EXTREME, 400 to GuiltTier.EXTREME,
        )
        val selector = selector()
        expectations.forEachIndexed { i, (count, tier) ->
            val line = selector.current(pack, locale, count, at(0, i))
            assertNotNull("no line at $count", line)
            assertEquals("wrong intensity at $count", tier.level, line!!.intensity)
        }
    }

    @Test
    fun `a day of scrolling escalates and never softens`() {
        val selector = selector()
        var previous = 0
        (50..200 step 5).forEachIndexed { i, count ->
            val line = selector.current(pack, locale, count, at(0, i))!!
            assertTrue(
                "intensity dropped from $previous to ${line.intensity} at count $count",
                line.intensity >= previous,
            )
            previous = line.intensity
        }
        assertEquals(GuiltTier.EXTREME.level, previous)
    }

    /* --- the one-line invariant ------------------------------------------------------ */

    @Test
    fun `every surface asking at the same moment gets the same line`() {
        val selector = selector()
        val home = selector.current(pack, locale, 80, at(0))
        val nudge = selector.current(pack, locale, 80, at(0))
        val panel = selector.current(pack, locale, 81, at(0))   // same tier, count moved on
        assertEquals(home, nudge)
        assertEquals(home, panel)
    }

    @Test
    fun `an app open draws a fresh line at the same tier`() {
        val selector = selector()
        val first = selector.current(pack, locale, 80, at(0))!!
        selector.invalidate()
        val second = selector.current(pack, locale, 80, at(0, 1))!!
        assertNotEquals(first.id, second.id)
        assertEquals(first.intensity, second.intensity)
    }

    /* --- the 7-day window (D47) ------------------------------------------------------- */

    @Test
    fun `a line is not shown twice within seven days`() {
        // The headline requirement. Draw a tier dry across a week of app opens and assert every
        // id is distinct — the pool is 18 and tier 2 fires once a day, so a week fits easily.
        val history = GuiltHistory()
        val selector = selector(history)
        val seen = mutableListOf<String>()
        repeat(7) { dayOffset ->
            selector.invalidate()
            seen += selector.current(pack, locale, 80, at(dayOffset))!!.id
        }
        assertEquals("a line repeated inside the 7-day window", seen.size, seen.distinct().size)
    }

    @Test
    fun `a line becomes available again once it falls out of the window`() {
        val history = GuiltHistory()
        val selector = selector(history)
        val first = selector.current(pack, locale, 80, at(0))!!

        // Eight days later the original draw is outside the window, so it is eligible again.
        // Prove it by burning everything ELSE and checking the pool did not go to the fallback.
        val exhausted = mutableListOf<GuiltSelector.PoolExhausted>()
        val later = selector(history, onExhausted = { exhausted += it })
        history.seed(
            pack.pool(GuiltSurface.AMBIENT, GuiltTier.MEDIUM, locale)
                .filter { it.id != first.id }
                .associate { it.id to t0 + 8 * day },
        )
        val drawn = later.current(pack, locale, 80, at(8))!!
        assertEquals("the expired line should be the only eligible one", first.id, drawn.id)
        assertTrue("the pool was not exhausted; no warning expected", exhausted.isEmpty())
    }

    @Test
    fun `the block screen shares the window, so it cannot echo the week`() {
        val history = GuiltHistory()
        val selector = selector(history)
        val ambient = selector.current(pack, locale, 120, at(0))!!
        val block = selector.draw(pack, locale, GuiltSurface.BLOCK, 120, at(0, 1))!!
        assertNotEquals(ambient.id, block.id)
    }

    /* --- graceful exhaustion ---------------------------------------------------------- */

    @Test
    fun `an exhausted pool warns rather than failing or going silent`() {
        val history = GuiltHistory()
        val exhausted = mutableListOf<GuiltSelector.PoolExhausted>()
        val selector = selector(history, onExhausted = { exhausted += it })

        // Every tier-4 line shown today: the shape of exhaustion at a high cadence.
        val pool = pack.pool(GuiltSurface.AMBIENT, GuiltTier.EXTREME, locale)
        history.seed(pool.associate { it.id to t0 })

        val line = selector.current(pack, locale, 400, at(0, 1))
        assertNotNull("exhaustion must not mean a blank screen", line)
        assertEquals("the fallback must still be tier-correct", GuiltTier.EXTREME.level, line!!.intensity)
        assertEquals(1, exhausted.size)
        assertEquals(GuiltTier.EXTREME, exhausted.single().tier)
        assertEquals(pool.size, exhausted.single().poolSize)
        assertEquals(400, exhausted.single().countToday)
    }

    @Test
    fun `the fallback picks from the least recently shown`() {
        val history = GuiltHistory()
        val selector = selector(history)
        val pool = pack.pool(GuiltSurface.AMBIENT, GuiltTier.EXTREME, locale)

        // Every line shown, on a gradient: index 0 is the stalest (six days ago), the last was
        // shown minutes ago. A gradient rather than "three stale, the rest today" because the
        // fallback reaches for a SLICE of the pool, and a stale set smaller than that slice
        // leaves fresh lines inside it — which the weighted draw then picks perfectly legally,
        // failing this test a third of the time for no real defect.
        history.seed(pool.mapIndexed { i, line -> line.id to t0 - (pool.size - i) * 8 * 60 * 60 * 1_000L }.toMap())

        // Computed BEFORE the draw: drawing RECORDS, so afterwards the chosen line is the
        // freshest thing in the history and could never be in its own staler half.
        // Asserted as the older HALF, not the exact slice: the slice width is the selector's own
        // tuning knob and a test that mirrors it would break on a change that is not a defect.
        // Any fraction up to a half satisfies this, and "picks anything" or "picks the freshest"
        // still fail it.
        val stalerHalf = pool.sortedBy { history.lastShownAt(it.id) }.take(pool.size / 2).map { it.id }

        val drawn = selector.current(pack, locale, 400, at(0, 1))!!
        assertTrue(
            "fell back to ${drawn.id}, which is not among the stalest ($stalerHalf)",
            drawn.id in stalerHalf,
        )
    }

    @Test
    fun `a healthy pool never triggers the warning`() {
        // Guards against the fallback becoming the normal path — the whole reason it is loud.
        val exhausted = mutableListOf<GuiltSelector.PoolExhausted>()
        val selector = selector(onExhausted = { exhausted += it })
        repeat(10) { i ->
            selector.invalidate()
            selector.current(pack, locale, 80, at(0, i))
        }
        assertTrue("warned on a pool with plenty left: $exhausted", exhausted.isEmpty())
    }

    /* --- history is recorded ----------------------------------------------------------- */

    @Test
    fun `every draw is recorded, on every surface`() {
        val history = GuiltHistory()
        val selector = selector(history)
        val ambient = selector.current(pack, locale, 120, at(0))!!
        val block = selector.draw(pack, locale, GuiltSurface.BLOCK, 120, at(0, 1))!!
        assertEquals(t0, history.lastShownAt(ambient.id))
        assertEquals(t0 + 60_000L, history.lastShownAt(block.id))
        assertEquals(2, history.size)
    }

    @Test
    fun `re-rendering a pinned line does not re-record it`() {
        // current() returns the pin without drawing while the key holds; only a DRAW is a showing.
        val history = GuiltHistory()
        val selector = selector(history)
        selector.current(pack, locale, 120, at(0))
        repeat(5) { selector.current(pack, locale, 121 + it, at(0, it + 1)) }
        assertEquals(1, history.size)
    }

    /* --- pack swap + fallback pack ------------------------------------------------------ */

    @Test
    fun `a hot-swapped pack drops the pin but keeps the user's history`() {
        val history = GuiltHistory()
        val selector = selector(history)
        val old = selector.current(pack, locale, 80, at(0))!!
        val swapped = pack.copy(revision = pack.revision + 1)
        val new = selector.current(swapped, locale, 80, at(0, 1))
        assertNotNull(new)
        assertNotEquals("history must survive a pack swap", old.id, new!!.id)
        assertEquals(2, history.size)
    }

    @Test
    fun `the fallback pack still yields a line at every tier`() {
        val selector = selector()
        listOf(50, 70, 100, 150).forEachIndexed { i, count ->
            assertNotNull(
                "FALLBACK yielded nothing at $count",
                selector.current(GuiltPack.FALLBACK, locale, count, at(0, i)),
            )
        }
    }
}
