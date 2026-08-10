package com.scrollkiller

import com.scrollkiller.guilt.GuiltCadence
import com.scrollkiller.service.BubbleMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble's motion POLICY. There is no view here and there cannot be one — that is the point of
 * [BubbleMotion] being pure Kotlin with the Android half kept in `BubbleAnimator`.
 *
 * What a phone can tell you about animation is whether it feels right. What it cannot tell you is
 * whether the motion is quietly eating the reading time D83 just bought, or whether a hash is
 * producing a negative index on one line in a hundred. Those are the things below.
 */
class BubbleMotionTest {

    /* --- the budget: the rule this whole file exists for ---------------------------------- */

    @Test
    fun `motion may never eat more than its share of a line's display time`() {
        // THE load-bearing assertion. D83 raised DISPLAY_MS 4s → 6.5s because lines were being
        // collapsed mid-sentence, and an unread line is a line that was never shown. Every
        // millisecond of entrance animation is a millisecond taken back off that purchase.
        //
        // Tied to DISPLAY_MS rather than pinned as a number, so a future retune of the display
        // carries the ceiling with it automatically — exactly as MIN_GAP_MS already follows it.
        val ceiling = GuiltCadence.DISPLAY_MS / BubbleMotion.MOTION_BUDGET_DIVISOR
        assertTrue(
            "reveal budget ${BubbleMotion.revealBudgetMs}ms exceeds the ${ceiling}ms allowed out " +
                "of a ${GuiltCadence.DISPLAY_MS}ms display window — shorten the animation, do not " +
                "raise the divisor",
            BubbleMotion.revealBudgetMs <= ceiling,
        )
    }

    @Test
    fun `the budget is the sum of its parts and cannot be quoted independently`() {
        // revealBudgetMs is DERIVED, so nobody can shorten one constant, leave the other, and have
        // the budget assertion above keep passing on a stale total. Same rule that keeps
        // MIN_GAP_MS from being written as a literal (D46, and D83's one-constant payout).
        assertEquals(
            BubbleMotion.REVEAL_MS + BubbleMotion.SETTLE_MS,
            BubbleMotion.revealBudgetMs,
        )
        assertEquals(
            BubbleMotion.MASCOT_OUT_MS + BubbleMotion.MASCOT_IN_MS,
            BubbleMotion.mascotSwapMs,
        )
    }

    @Test
    fun `the mascot swap is allowed to be slower than a line reveal`() {
        // Not a budget violation and worth saying why: the mascot changes at most TWICE A DAY
        // (BrainState flips at 50 and 150) and does not sit on top of anything the user is
        // reading, whereas a reveal fires up to every fifth scroll and delays a sentence.
        // Different budgets because they are spending different things.
        assertTrue(BubbleMotion.mascotSwapMs > BubbleMotion.revealBudgetMs)
    }

    /* --- reveal selection ------------------------------------------------------------------ */

    @Test
    fun `the same line at the same tier always enters the same way`() {
        // Determinism is the reason this is a hash and not a Random. A render path that picks
        // differently on each call would make the entrance untestable AND would mean a line
        // re-shown after a gap animates differently for no reason the user could ever attribute.
        val first = BubbleMotion.revealFor("{count}. Bas thoda aur, you said.", 4)
        repeat(50) {
            assertEquals(first, BubbleMotion.revealFor("{count}. Bas thoda aur, you said.", 4))
        }
    }

    @Test
    fun `tier one is silent-adjacent and gets exactly one calm style`() {
        // The app has only just started speaking at tier 1 (it says NOTHING below 50 — D41), so
        // the first thing it ever does must not be a flourish.
        repeat(100) { i ->
            assertEquals(BubbleMotion.Reveal.FADE, BubbleMotion.revealFor("line-$i", 1))
        }
    }

    @Test
    fun `the palette widens with the tier and never drops a calmer style at the bottom`() {
        // The escalation shape: each tier KEEPS what the one below it had and gains something
        // pushier, so the motion reads as "it can now also do this" rather than as four unrelated
        // behaviours. Asserted by observing what actually comes out over many seeds, because the
        // palette itself is private — a test that reached in would pin the implementation instead
        // of the property.
        assertEquals(setOf(BubbleMotion.Reveal.FADE), stylesSeenAt(1))
        assertEquals(setOf(BubbleMotion.Reveal.FADE, BubbleMotion.Reveal.RISE), stylesSeenAt(2))
        assertEquals(
            setOf(BubbleMotion.Reveal.FADE, BubbleMotion.Reveal.RISE, BubbleMotion.Reveal.POP),
            stylesSeenAt(3),
        )
        assertEquals(
            setOf(BubbleMotion.Reveal.RISE, BubbleMotion.Reveal.POP, BubbleMotion.Reveal.SWEEP),
            stylesSeenAt(4),
        )
    }

    @Test
    fun `tier four drops the flattest style rather than merely adding to it`() {
        // The one deliberate exception to "widening, never replacing". At the top of the curve a
        // plain fade is indistinguishable from the count simply changing, which at that point is
        // the thing the line most needs NOT to look like.
        assertTrue(BubbleMotion.Reveal.FADE !in stylesSeenAt(4))
    }

    @Test
    fun `different lines at the same tier do not all enter identically`() {
        // The reason the seed is in the signature at all. If this ever collapses to one style,
        // every line at that tier arrives the same way and the variety the pack has in its words
        // is thrown away at the last step.
        val seen = (0 until 200).map { BubbleMotion.revealFor("guilt-line-$it", 4) }.toSet()
        assertTrue("tier 4 collapsed to $seen", seen.size >= 2)
    }

    @Test
    fun `a tier level outside the range still answers, on a render path`() {
        // Total by construction. The caller does `GuiltTier.forCount(count)?.level ?: 1`, and a
        // future caller may not be so careful — this runs while somebody is mid-scroll, so a
        // garbage level must degrade to a valid style rather than throw inside a Flow.onEach
        // (which, per the block-screen work, cancels the collection and kills the count).
        assertEquals(BubbleMotion.Reveal.FADE, BubbleMotion.revealFor("x", 0))
        assertEquals(BubbleMotion.Reveal.FADE, BubbleMotion.revealFor("x", -99))
        assertTrue(BubbleMotion.revealFor("x", Int.MAX_VALUE) in BubbleMotion.Reveal.entries)
        assertTrue(BubbleMotion.revealFor("", 4) in BubbleMotion.Reveal.entries)
    }

    @Test
    fun `no seed can produce a negative index`() {
        // The specific bug this guards. FNV-1a overflows into the sign bit constantly, and
        // `hash % size` on a negative Long is negative — an IndexOutOfBounds on whichever line
        // happens to hash that way, discovered by a user rather than by a build. The unsigned
        // shift is what prevents it; this asserts it across a wide seed space rather than
        // trusting the read-through.
        repeat(5_000) { i ->
            val seed = "${i}-नमस्ते-$i"   // include non-ASCII
            for (level in 1..4) {
                assertTrue(BubbleMotion.revealFor(seed, level) in BubbleMotion.Reveal.entries)
            }
        }
    }

    /* --- the transforms themselves --------------------------------------------------------- */

    @Test
    fun `no reveal starts fully invisible`() {
        // A line that begins at alpha 0 spends the first frames of a budget measured in a couple
        // of hundred milliseconds being unreadable, which is the opposite of what the budget is
        // protecting. Every style starts partly visible and finishes at rest.
        BubbleMotion.Reveal.entries.forEach { reveal ->
            assertTrue("${reveal.name} starts invisible", reveal.fromAlpha > 0f)
            assertTrue("${reveal.name} starts over-opaque", reveal.fromAlpha < BubbleMotion.REST_ALPHA)
        }
    }

    @Test
    fun `every reveal actually moves something`() {
        // A style whose start state equals its rest state is a no-op wearing a name — it would
        // show up in the enum, be selected a quarter of the time, and do nothing.
        BubbleMotion.Reveal.entries.forEach { reveal ->
            val moves = reveal.fromAlpha != BubbleMotion.REST_ALPHA ||
                reveal.fromScale != BubbleMotion.REST_SCALE ||
                reveal.fromTranslationXDp != BubbleMotion.REST_TRANSLATION ||
                reveal.fromTranslationYDp != BubbleMotion.REST_TRANSLATION
            assertTrue("${reveal.name} is a no-op", moves)
        }
    }

    @Test
    fun `no reveal scales up from larger than rest`() {
        // Growing INTO place would push the pill past its measured width for the duration, which
        // over a video near a screen edge is the one visual artefact this surface cannot afford
        // (D38's placement clamping works on the measured size, not on a transform).
        BubbleMotion.Reveal.entries.forEach { reveal ->
            assertTrue("${reveal.name} starts oversized", reveal.fromScale <= BubbleMotion.REST_SCALE)
        }
    }

    @Test
    fun `the tint crossfade is exactly as long as the mascot swap`() {
        // The two halves of ONE transition. D86 shipped the mascot animated and the tint SNAPPED,
        // so the colour flipped on a single frame while the character was still mid-dip — the eye
        // catches the instant change, concludes the state has flipped, then watches the mascot
        // arrive late. Animating part of a composite and not the rest reads as a glitch rather than
        // as a missing feature.
        //
        // Derived, not a second constant, so no future tune of the swap can leave the colour behind.
        assertEquals(BubbleMotion.mascotSwapMs, BubbleMotion.tintCrossfadeMs)
    }

    @Test
    fun `the mascot tilt is small enough to read as a reaction`() {
        // Past roughly ten degrees a tilt on a 40dp pill inside somebody else's video stops reading
        // as the character reacting and starts reading as the overlay being broken or askew — the
        // one impression this surface can least afford. Non-zero, or the rotation is decoration
        // that does nothing.
        assertTrue(BubbleMotion.MASCOT_TILT_DEG > 0f)
        assertTrue("a tilt this large reads as broken, not as motion", BubbleMotion.MASCOT_TILT_DEG <= 10f)
    }

    @Test
    fun `level is the resting rotation`() {
        // settle() asserts this blind. A pill left permanently at eight degrees is the "is this app
        // broken?" impression rather than a flourish, and nothing recreates this window to fix it.
        assertEquals(0f, BubbleMotion.REST_ROTATION, 0f)
    }

    @Test
    fun `the mascot dips but never disappears`() {
        // Inside somebody else's app there is no page transition to explain a vanishing element,
        // so a mascot that hits zero reads as the overlay breaking rather than as the character
        // reacting to your count.
        assertTrue(BubbleMotion.MASCOT_DIP_SCALE > 0f)
        assertTrue(BubbleMotion.MASCOT_DIP_SCALE < BubbleMotion.REST_SCALE)
    }

    @Test
    fun `rest values are the identity transform`() {
        // BubbleAnimator.settle asserts these blind, without knowing what was in flight. If any
        // of them stopped being the identity, "settle" would quietly become "set to something".
        assertEquals(1f, BubbleMotion.REST_ALPHA, 0f)
        assertEquals(1f, BubbleMotion.REST_SCALE, 0f)
        assertEquals(0f, BubbleMotion.REST_TRANSLATION, 0f)
    }

    @Test
    fun `the tap acknowledgement presses in rather than out`() {
        assertTrue(BubbleMotion.TAP_SCALE < BubbleMotion.REST_SCALE)
        assertTrue(BubbleMotion.TAP_SCALE > 0f)
        // The tap is a ROUND TRIP — in and back out, both at TAP_MS — and the whole gesture must
        // still land inside a line's reveal budget. A tap can happen while a line is showing, and
        // an acknowledgement that outlasts the line it interrupted is the wrong thing to be the
        // last piece of motion on screen.
        assertTrue(BubbleMotion.TAP_MS * 2 <= BubbleMotion.revealBudgetMs)
    }

    @Test
    fun `the FNV constants are the real ones`() {
        // Cheap, and it catches the transcription error that would otherwise show up as "the
        // reveals feel oddly repetitive" months later, with no obvious cause.
        assertEquals(-0x340d631b7bdddcdbL, BubbleMotion.FNV_OFFSET)
        assertEquals(1099511628211L, BubbleMotion.FNV_PRIME)
        assertNotEquals(0L, BubbleMotion.FNV_OFFSET)
    }

    private fun stylesSeenAt(tierLevel: Int): Set<BubbleMotion.Reveal> =
        (0 until 2_000).map { BubbleMotion.revealFor("seed-$it", tierLevel) }.toSet()
}
