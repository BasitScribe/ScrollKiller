package com.scrollkiller

import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import com.scrollkiller.service.ReelIdentity
import com.scrollkiller.service.ReelIdentity.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that separates a per-Short identity from the ~40 pieces of volatile text the Shorts
 * player emits while ONE Short plays (D34). Fed the literal strings from the device capture,
 * because the whole design rests on `@SagarsKitchen` being usable and
 * "like this video along with 79K other people" not being.
 *
 * Every case here fails toward UNDERcount: an unrecognised string yields null, which the
 * detector treats as no information rather than as a new Short.
 */
class ReelIdentityTest {

    private val youtube = PlatformRegistry.specFor(Platform.YOUTUBE)

    private fun pick(vararg candidates: Candidate) = ReelIdentity.pick(candidates.toList(), youtube)

    @Test
    fun `a channel handle is the identity`() {
        assertEquals("@SagarsKitchen", pick(Candidate("reel_player_page_container", "@SagarsKitchen")))
        assertEquals("@PakWheels", pick(Candidate("reel_player_page_container", "@PakWheels")))
        assertEquals("@DSMotoTube", pick(Candidate("reel_player_page_container", "@DSMotoTube")))
    }

    @Test
    fun `the volatile text from a single Short is never an identity`() {
        // These are the strings that fire dozens of times while ONE Short plays. If any of them
        // became the identity, idling would count — the exact bug the identity check prevents.
        assertNull(pick(Candidate("like_button", "like this video along with 79K other people")))
        assertNull(pick(Candidate(null, "Auto-dubbed")))
        assertNull(pick(Candidate("subtitle_view", "and then you just fold it over twice")))
        assertNull(pick(Candidate("comment_count", "1,204")))
        assertNull(pick(Candidate("reel_progress_bar", "0:07")))
    }

    @Test
    fun `an empty tree yields nothing`() {
        assertNull(ReelIdentity.pick(emptyList(), youtube))
        assertNull(pick(Candidate("reel_player_page_container", "   ")))
    }

    @Test
    fun `the handle wins over any other text, wherever it sits in the tree`() {
        // Real trees interleave: the handle is rarely the first string collected.
        val identity = pick(
            Candidate("subtitle_view", "so I take the pan off the heat"),
            Candidate("like_button", "like this video along with 79K other people"),
            Candidate("reel_channel_bar", "@SagarsKitchen"),
            Candidate("reel_title", "Perfect omelette every time"),
        )
        assertEquals("@SagarsKitchen", identity)
    }

    @Test
    fun `only a LEADING handle counts, not a mention inside a caption`() {
        // A subtitle or caption quoting someone must not become the identity — it changes as
        // the Short plays, which is precisely what we're guarding against.
        assertNull(pick(Candidate("subtitle_view", "shout out to @someone for the recipe")))
    }

    @Test
    fun `trailing metadata is stripped so a ticking subscriber count cannot flap the identity`() {
        // If the whole description were the identity, "1.2M subscribers" ticking to "1.3M"
        // would read as a new Short. Only the handle token is kept.
        assertEquals(
            "@SagarsKitchen",
            pick(Candidate("reel_player_page_container", "@SagarsKitchen · 1.2M subscribers")),
        )
        assertEquals(
            pick(Candidate("reel_player_page_container", "@SagarsKitchen · 1.2M subscribers")),
            pick(Candidate("reel_player_page_container", "@SagarsKitchen · 1.3M subscribers")),
        )
    }

    @Test
    fun `a title node is the fallback when the Short has no handle`() {
        val identity = pick(
            Candidate("subtitle_view", "and then you just fold it over twice"),
            Candidate("reel_title", "Perfect omelette every time"),
        )
        assertEquals("t:Perfect omelette every time", identity)
    }

    @Test
    fun `only allowlisted ids can supply the title fallback`() {
        // The allowlist is the point: without it, ANY text node becomes an identity and the
        // subtitle stream counts as a swipe every second.
        assertNull(pick(Candidate("some_other_text_view", "Perfect omelette every time")))
        assertNull(pick(Candidate(null, "Perfect omelette every time")))
    }

    @Test
    fun `a title identity can never collide with a handle`() {
        // A channel literally called "@Foo" appearing as a title must stay distinguishable
        // from the handle @Foo, or two different Shorts could share one identity.
        val asTitle = pick(Candidate("reel_title", "Cooking with fire"))
        assertNotEquals("Cooking with fire", asTitle)
        assertEquals("t:Cooking with fire", asTitle)
    }

    @Test
    fun `a re-wrapped title is the same identity`() {
        // Whitespace collapsing: a relayout that re-wraps the title must not read as a new Short.
        assertEquals(
            pick(Candidate("reel_title", "Perfect omelette every time")),
            pick(Candidate("reel_title", "Perfect omelette\n  every  time")),
        )
    }

    @Test
    fun `a platform with no title hints is handle-only`() {
        // Instagram carries no identity config at all — pick must not invent one for it.
        val instagram = PlatformRegistry.specFor(Platform.INSTAGRAM)
        assertNull(ReelIdentity.pick(listOf(Candidate("reel_title", "anything")), instagram))
    }
}
