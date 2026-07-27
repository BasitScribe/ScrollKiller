package com.scrollkiller

import com.scrollkiller.guilt.GuiltHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory half of the 7-day window (D47). */
class GuiltHistoryTest {

    private val day = 24L * 60 * 60 * 1_000
    private val now = 1_800_000_000_000L

    @Test
    fun `only ids inside the window are excluded`() {
        val history = GuiltHistory()
        history.record("fresh", now - 1 * day)
        history.record("edge", now - 7 * day)
        history.record("expired", now - 8 * day)

        val burnt = history.shownSince(now - 7 * day)
        assertEquals(setOf("fresh", "edge"), burnt)
    }

    @Test
    fun `an unseen line sorts stalest, ahead of anything ever shown`() {
        // Long.MIN_VALUE rather than null, so the exhaustion fallback reaches for never-seen
        // lines first — which is exactly what it should do.
        val history = GuiltHistory()
        history.record("seen", now - 100 * day)
        assertTrue(history.lastShownAt("never") < history.lastShownAt("seen"))
    }

    @Test
    fun `re-showing a line moves it forward, not backward`() {
        val history = GuiltHistory()
        history.record("a", now - 5 * day)
        history.record("a", now)
        assertEquals(now, history.lastShownAt("a"))
        assertEquals(1, history.size)
    }

    @Test
    fun `seeding merges and keeps the LATER timestamp`() {
        // The load is asynchronous, so a line may already have been shown and recorded in the gap
        // since process start. Overwriting with what disk said would move that line backwards and
        // let it come round early.
        val history = GuiltHistory()
        history.record("a", now)                                  // shown during the load
        history.seed(mapOf("a" to now - 6 * day, "b" to now - day))

        assertEquals("the live record must win", now, history.lastShownAt("a"))
        assertEquals(now - day, history.lastShownAt("b"))
    }

    @Test
    fun `recording notifies the persistence sink`() {
        val written = mutableListOf<Pair<String, Long>>()
        val history = GuiltHistory().apply { onRecord = { id, at -> written += id to at } }
        history.record("a", now)
        history.record("b", now + 1)
        assertEquals(listOf("a" to now, "b" to (now + 1)), written)
    }

    @Test
    fun `seeding does not notify the sink`() {
        // Otherwise priming would write every loaded row straight back to the table it came from.
        val written = mutableListOf<String>()
        val history = GuiltHistory().apply { onRecord = { id, _ -> written += id } }
        history.seed(mapOf("a" to now, "b" to now))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `clear empties it`() {
        val history = GuiltHistory()
        history.record("a", now)
        history.clear()
        assertEquals(0, history.size)
        assertTrue(history.shownSince(0).isEmpty())
    }
}
