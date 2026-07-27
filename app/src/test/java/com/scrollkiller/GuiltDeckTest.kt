package com.scrollkiller

import com.scrollkiller.guilt.GuiltCategory
import com.scrollkiller.guilt.GuiltDeck
import com.scrollkiller.guilt.GuiltLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The daily ordering seed. Much smaller than it was: D42's deck NARROWED the pool to ~60% a day
 * to create freshness, and D47's rolling 7-day exclusion does that job far better, so the
 * narrowing was removed rather than left to fight the new rule (see [GuiltDeck]'s class doc).
 *
 * What survives is determinism — the property the exhaustion fallback leans on for tiebreaks.
 */
class GuiltDeckTest {

    private fun pool(size: Int): List<GuiltLine> = (1..size).map {
        GuiltLine(
            id = "line_%02d".format(it),
            category = GuiltCategory.ROAST,
            intensity = 1,
            weight = 1,
            text = "line $it",
        )
    }

    private companion object {
        const val INSTALL = "install-abc"
    }

    @Test
    fun `ordering keeps every line — it no longer narrows`() {
        // The regression guard for the D42→D47 change. If narrowing came back, an already
        // week-thinned pool would be cut again and healthy packs would hit the fallback.
        val pool = pool(20)
        val ordered = GuiltDeck.order(pool, "2026-07-27", INSTALL)
        assertEquals(pool.size, ordered.size)
        assertEquals(pool.map { it.id }.toSet(), ordered.map { it.id }.toSet())
    }

    @Test
    fun `the order is deterministic for a given day and install`() {
        val pool = pool(20)
        assertEquals(
            GuiltDeck.order(pool, "2026-07-27", INSTALL),
            GuiltDeck.order(pool, "2026-07-27", INSTALL),
        )
    }

    @Test
    fun `two days order differently`() {
        val pool = pool(20)
        assertNotEquals(
            GuiltDeck.order(pool, "2026-07-27", INSTALL).map { it.id },
            GuiltDeck.order(pool, "2026-07-28", INSTALL).map { it.id },
        )
    }

    @Test
    fun `two installs order differently on the same day`() {
        // So two users don't degrade through an exhausted pool in lockstep.
        val pool = pool(20)
        assertNotEquals(
            GuiltDeck.order(pool, "2026-07-27", "install-a").map { it.id },
            GuiltDeck.order(pool, "2026-07-27", "install-b").map { it.id },
        )
    }

    @Test
    fun `the order does not depend on the pack's declaration order`() {
        // Otherwise reordering guilt_pack.json — an edit with no intent behind it — would
        // silently reshuffle every user.
        val pool = pool(20)
        assertEquals(
            GuiltDeck.order(pool, "2026-07-27", INSTALL),
            GuiltDeck.order(pool.reversed(), "2026-07-27", INSTALL),
        )
    }

    @Test
    fun `an empty or single-line pool is handled`() {
        assertEquals(emptyList<GuiltLine>(), GuiltDeck.order(emptyList(), "2026-07-27", INSTALL))
        assertEquals(1, GuiltDeck.order(pool(1), "2026-07-27", INSTALL).size)
    }

    @Test
    fun `the seed is stable and distinguishes both inputs`() {
        assertEquals(GuiltDeck.seed("2026-07-27", INSTALL), GuiltDeck.seed("2026-07-27", INSTALL))
        assertNotEquals(GuiltDeck.seed("2026-07-27", INSTALL), GuiltDeck.seed("2026-07-28", INSTALL))
        assertNotEquals(GuiltDeck.seed("2026-07-27", "a"), GuiltDeck.seed("2026-07-27", "b"))
    }
}
