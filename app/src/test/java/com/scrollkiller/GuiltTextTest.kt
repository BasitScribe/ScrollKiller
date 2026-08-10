package com.scrollkiller

import com.scrollkiller.guilt.GuiltText
import com.scrollkiller.stats.TimeEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The count-token model and, more importantly, the guard that keeps numbers OUT of content.
 *
 * The bug being fenced off (D47): lines were authored with the number baked in ("Fifty already"),
 * so once the D46 cadence started re-showing them all day the app announced "one-fifty" at 312
 * scrolls, contradicting its own counter an inch away.
 */
class GuiltTextTest {

    /* --- rendering ------------------------------------------------------------------- */

    @Test
    fun `the count token becomes the live total`() {
        assertEquals(
            "312 already. Your thumb has done more reps than you have all week.",
            GuiltText.render("{count} already. Your thumb has done more reps than you have all week.", 312),
        )
    }

    @Test
    fun `the minutes token becomes the estimate for that count`() {
        assertEquals(
            "${TimeEstimate.minutesLabel(312)} minutes gone.",
            GuiltText.render("{minutes} minutes gone.", 312),
        )
    }

    @Test
    fun `a line can use a token more than once, and both move together`() {
        assertEquals("50 and 50", GuiltText.render("{count} and {count}", 50))
    }

    @Test
    fun `a line with no tokens is returned untouched`() {
        val text = "The feed is infinite. Your evening is not."
        assertEquals(text, GuiltText.render(text, 999))
    }

    @Test
    fun `rendering tracks the count, so the line can never contradict the counter`() {
        // The pinned line is re-rendered on every emission rather than frozen at fire time. A
        // line that fired at 310 and is still on screen at 314 must read 314 — the alternative
        // is the app disagreeing with the number next to it, which is the whole bug.
        val text = "{count} and you are still reading this."
        assertEquals("310 and you are still reading this.", GuiltText.render(text, 310))
        assertEquals("314 and you are still reading this.", GuiltText.render(text, 314))
    }

    /* --- the guard ------------------------------------------------------------------- */

    @Test
    fun `spelled-out scale words are caught`() {
        // THE case that matters. Every offender in the shipped pack was a WORD, not a digit, so
        // a "no bare integers" guard would have passed the entire broken pack.
        val offenders = listOf(
            "Fifty already. Your thumb has done more reps than you have.",
            "Seventy. At this point the app should be paying you rent.",
            "A hundred and fifty. Your phone is worried about you.",
            "Half a century. Great in cricket.",
            "You have heard this sound thirty times.",
            "Fine. Two hundred. Let us see what is on the other side.",
        )
        offenders.forEach { assertNotNull("missed a baked number in: $it", GuiltText.offendingNumber(it)) }
    }

    @Test
    fun `digits are caught`() {
        assertNotNull(GuiltText.offendingNumber("150 reels already?"))
        assertNotNull(GuiltText.offendingNumber("You are at 312."))
    }

    @Test
    fun `bare time units are caught — time is the count in different units`() {
        listOf(
            "That is half an hour you do not get back.",
            "A full hour of your one life.",
            "You came for five minutes.",
            "Thirty-five minutes gone.",
        ).forEach { assertNotNull("missed a duration claim in: $it", GuiltText.offendingNumber(it)) }
    }

    @Test
    fun `a tokenised time claim is allowed`() {
        assertNull(GuiltText.offendingNumber("{minutes} minutes gone. You would notice the cash."))
        assertNull(GuiltText.offendingNumber("That is {minutes} minutes you do not get back."))
    }

    @Test
    fun `the token's own text does not trip the guard`() {
        // `{minutes}` literally contains the word "minutes"; the guard blanks known tokens before
        // looking, or it would reject every line it exists to enable.
        assertNull(GuiltText.offendingNumber("{minutes} minutes."))
        assertNull(GuiltText.offendingNumber("{count} reels."))
    }

    @Test
    fun `small idiomatic numbers are allowed`() {
        // Deliberately not banned: these are never claims about the total, and a guard that fired
        // on them would be switched off within a week. See GuiltText's class doc.
        listOf(
            "Go on, one more. The good one is definitely next.",
            "You opened this to check one thing.",
            "Putting it down is a skill. Try one rep.",
            "That is one full lecture of scrolling.",
            "It was mid the first time.",
            "You get a finite number of evenings. You just spent one of them here.",
        ).forEach { assertNull("false positive on: $it", GuiltText.offendingNumber(it)) }
    }

    @Test
    fun `an unknown token is caught rather than printed at the user`() {
        // A newer pack's `{streak}` would otherwise reach the screen as literal braces.
        assertNotNull(GuiltText.offendingNumber("You are on a {streak} day run."))
        assertEquals("{streak}", GuiltText.offendingNumber("You are on a {streak} day run."))
    }

    @Test
    fun `the offender is named, not just flagged`() {
        // So a parser log or a test failure is actionable: "contains 'hundred'" beats "invalid".
        assertEquals("hundred", GuiltText.offendingNumber("A hundred windows into other lives."))
    }

    @Test
    fun `Indian scale words are caught too (D85)`() {
        // A REAL HOLE, found when the pack leaned harder into Hinglish. The guard was written
        // against a pack that happened to use only English scale words, so "a lakh of these" would
        // have sailed past every check and shipped precisely the D47 bug the guard exists to stop.
        // The lesson: a content guard has to widen when the CONTENT's register changes, not only
        // when the code does.
        assertEquals("lakh", GuiltText.offendingNumber("You have watched a lakh of these."))
        assertEquals("crore", GuiltText.offendingNumber("A crore people saw this before you."))
        // Reported with the casing the author used, like every other offender — the message names
        // the fragment to go and fix, so it has to match what is actually in the file.
        assertEquals("Lakhs", GuiltText.offendingNumber("Lakhs of reels, same face."))
        assertEquals("Crores", GuiltText.offendingNumber("Crores of them, all the same."))
    }

    @Test
    fun `the desi lines that shipped are clean`() {
        // Spot-check of the register D85 added, so the guard's new words cannot be over-broad and
        // start rejecting ordinary Hinglish. These are real shipped lines.
        listOf(
            "Arre keep going. It is only {count}, that is basically nothing.",
            "Bhai. {count}. Bas. Enough.",
            "Theek hai, keep going. It is not like the day is going anywhere. Oh.",
            "Bas thoda aur. That is what you said at {count} too.",
            "{count} reels of sasta dopamine, paid for in evenings.",
            "{minutes} minutes, and not one of them is coming back.",
        ).forEach { line ->
            assertNull("a shipped line was rejected: $line", GuiltText.offendingNumber(line))
        }
    }
}
