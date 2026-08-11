package com.scrollkiller

import com.scrollkiller.service.ItemIdentity
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import com.scrollkiller.service.ReelIdentity
import com.scrollkiller.service.ReelIdentity.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that separates a per-Short identity from the ~40 pieces of volatile text the Shorts
 * player emits while ONE Short plays (D34). Fed the literal strings from the device capture,
 * because the whole design rests on `@SagarsKitchen` being usable and
 * "like this video along with 79K other people" not being.
 *
 * Every case here fails toward UNDERcount: an unrecognised string contributes nothing, and an
 * identity with no fields at all is treated by the detector as no information rather than as a
 * new Short.
 *
 * ⚑ Since 2026-08-11 `pick` returns BOTH the handle and the title rather than the first one it
 * finds. Collecting only the handle is what made two consecutive Shorts by one creator
 * indistinguishable — see [IdentityAdvanceDetectorTest] for the behaviour that fixes.
 */
class ReelIdentityTest {

    private val youtube = PlatformRegistry.specFor(Platform.YOUTUBE)

    private fun pick(vararg candidates: Candidate) = ReelIdentity.pick(candidates.toList(), youtube)

    @Test
    fun `a channel handle is part of the identity`() {
        assertEquals(
            "@SagarsKitchen",
            pick(Candidate("reel_player_page_container", "@SagarsKitchen"))?.handle,
        )
        assertEquals("@PakWheels", pick(Candidate("reel_player_page_container", "@PakWheels"))?.handle)
        assertEquals("@DSMotoTube", pick(Candidate("reel_player_page_container", "@DSMotoTube"))?.handle)
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
    fun `the handle is found wherever it sits in the tree, and the title comes with it`() {
        // Real trees interleave: the handle is rarely the first string collected. Both fields are
        // collected now — the title used to be discarded here purely because a handle existed,
        // which is what left two Shorts by one creator identical.
        val identity = pick(
            Candidate("subtitle_view", "so I take the pan off the heat"),
            Candidate("like_button", "like this video along with 79K other people"),
            Candidate("reel_channel_bar", "@SagarsKitchen"),
            Candidate("reel_title", "Perfect omelette every time"),
        )
        assertEquals("@SagarsKitchen", identity?.handle)
        assertEquals("Perfect omelette every time", identity?.title)
    }

    @Test
    fun `two Shorts by one creator are distinguishable`() {
        // The regression, at the extraction layer. Same handle, different title — the pair must
        // report them as different items.
        val first = pick(
            Candidate("reel_channel_bar", "@SagarsKitchen"),
            Candidate("reel_title", "Perfect omelette every time"),
        )
        val second = pick(
            Candidate("reel_channel_bar", "@SagarsKitchen"),
            Candidate("reel_title", "Paneer in ten minutes"),
        )
        assertTrue(first!!.differsFrom(second!!))
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
            pick(Candidate("reel_player_page_container", "@SagarsKitchen · 1.2M subscribers"))?.handle,
        )
        assertEquals(
            pick(Candidate("reel_player_page_container", "@SagarsKitchen · 1.2M subscribers")),
            pick(Candidate("reel_player_page_container", "@SagarsKitchen · 1.3M subscribers")),
        )
    }

    @Test
    fun `a title alone is an identity when the Short has no handle`() {
        val identity = pick(
            Candidate("subtitle_view", "and then you just fold it over twice"),
            Candidate("reel_title", "Perfect omelette every time"),
        )
        assertNull(identity?.handle)
        assertEquals("Perfect omelette every time", identity?.title)
    }

    @Test
    fun `only allowlisted ids can supply the title`() {
        // The allowlist is the point: without it, ANY text node becomes an identity and the
        // subtitle stream counts as a swipe every second.
        assertNull(pick(Candidate("some_other_text_view", "Perfect omelette every time")))
        assertNull(pick(Candidate(null, "Perfect omelette every time")))
    }

    @Test
    fun `a title can never collide with a handle`() {
        // Previously enforced by prefixing titles with "t:". Now it is structural: they are
        // different FIELDS and `differsFrom` never compares one against the other, which is
        // stronger than a prefix somebody could strip while tidying up.
        //
        // Worth stating explicitly because handle extraction is deliberately id-AGNOSTIC — D34
        // found the handle on the player container's contentDescription, not on a node with a
        // known id — so a Short whose TITLE begins with "@" populates both fields from the one
        // string. That is harmless precisely because the two are never cross-compared.
        val titleThatLooksLikeAHandle = pick(Candidate("reel_title", "@Foo"))!!
        assertEquals("@Foo", titleThatLooksLikeAHandle.title)

        assertTrue(ItemIdentity(handle = "@Foo").differsFrom(ItemIdentity(handle = "@Bar")))
        assertFalse(
            "a title must never be measured against a handle",
            ItemIdentity(handle = "@Foo").differsFrom(ItemIdentity(title = "@Bar")),
        )
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
