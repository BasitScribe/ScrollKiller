package com.scrollkiller.guilt

import com.scrollkiller.stats.TimeEstimate

/**
 * Turns a pack line's TEXT into the sentence the user reads: substitutes the live count into
 * placeholder tokens, and — just as importantly — defines what a line is forbidden to say.
 *
 * ## The bug this exists to make impossible (D47)
 * Lines were authored with the number baked in: "Fifty already", "A hundred and fifty reels".
 * They were written for the count that first shows them, but the cadence (D46) re-shows lines
 * for the rest of the day, so at 312 scrolls the app confidently announced "one-fifty" while the
 * counter an inch away read 312. Immersion-breaking, and it made the app look like it was not
 * actually watching.
 *
 * A line may therefore reference the count in exactly ONE way: the [COUNT_TOKEN] placeholder,
 * substituted at render time. Anything else that reads as a magnitude is rejected by
 * [offendingNumber] — in the parser, so a bad remote pack drops the offending line rather than
 * shipping the bug, and in a test over the bundled asset, so a bad LOCAL pack fails the build.
 *
 * ## Why the guard checks English words and not just digits
 * The obvious guard is "no bare integers". It would have passed the entire broken pack: every
 * single offender was spelled out — *Fifty*, *Seventy*, *A hundred*, *One-fifty*. So the guard
 * bans scale WORDS too. Small words (one, two, three…) are left alone because they are almost
 * always idiomatic here ("one more", "one of them", "one full lecture") rather than a claim
 * about the total, and a guard that fires on those would be turned off within a week.
 *
 * ## Time is the same bug
 * "That is half an hour" is as wrong at 312 scrolls as "fifty" is — it is the count in different
 * units, derived through [TimeEstimate]. So there is a [MINUTES_TOKEN] as well, and bare time
 * units are banned by the same rule. Without it, the eight strongest existential lines in the
 * pack would have had to be deleted rather than fixed.
 *
 * Pure Kotlin, no Android imports.
 */
object GuiltText {

    /** Replaced with today's total, e.g. "312". */
    const val COUNT_TOKEN = "{count}"

    /** Replaced with the estimated minutes for today's total, e.g. "31.2". */
    const val MINUTES_TOKEN = "{minutes}"

    /**
     * The line as the user should read it at a total of [count].
     *
     * Called on EVERY render, not once at fire time, so the sentence always agrees with the
     * number sitting next to it on the pill. A line pinned at 310 and still showing at 314 reads
     * "314", because the alternative is the app contradicting its own counter — which is the
     * whole bug.
     */
    fun render(text: String, count: Int): String =
        text.replace(COUNT_TOKEN, count.toString())
            .replace(MINUTES_TOKEN, TimeEstimate.minutesLabel(count))

    /**
     * The first thing in [text] that reads as a baked-in number, or null if the line is clean.
     *
     * Returns the offending fragment rather than a boolean so both the parser log and the test
     * failure can name it — "t4_r2 contains 'hundred'" is actionable, "t4_r2 is invalid" is not.
     */
    fun offendingNumber(text: String): String? {
        // Tokens are legal numbers, so blank them to a marker before looking for illegal ones.
        // Without this the literal word inside `{minutes}` would trip the time-unit rule.
        val stripped = text
            .replace(COUNT_TOKEN, TOKEN_MARKER)
            .replace(MINUTES_TOKEN, TOKEN_MARKER)

        UNKNOWN_TOKEN.find(stripped)?.let { return it.value }
        DIGITS.find(stripped)?.let { return it.value }
        SCALE_WORDS.find(stripped)?.let { return it.value }
        BARE_TIME_UNIT.find(stripped)?.let { return it.value }
        return null
    }

    /** Fixed-width stand-in for a legal token. Width matters — [BARE_TIME_UNIT] looks behind it. */
    private const val TOKEN_MARKER = "NUMTOKEN"

    /**
     * Any digit at all. There is no legitimate use for one in a guilt line: a count comes from
     * [COUNT_TOKEN], and a pack that wants "24/7" can spell it.
     */
    private val DIGITS = Regex("\\d")

    /**
     * Words that only ever appear at count scale in this pack. Deliberately excludes one..nine,
     * which are idiomatic here — see the class doc.
     */
    private val SCALE_WORDS = Regex(
        "\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|century|dozen)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A time unit not immediately preceded by [MINUTES_TOKEN]. Catches "half an hour", "a full
     * hour", "five minutes" — all of which assert a duration derived from a count they cannot
     * know. The lookbehind is fixed-width, which is why [TOKEN_MARKER] is a constant string.
     */
    private val BARE_TIME_UNIT = Regex(
        "(?<!$TOKEN_MARKER )\\b(minute|minutes|hour|hours)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A `{…}` placeholder this client doesn't know. An unrendered token reaches the screen
     * verbatim, so a newer pack's `{streak}` must drop the line rather than print braces at
     * someone. Checked AFTER the known tokens have been blanked, so only unknowns remain.
     */
    private val UNKNOWN_TOKEN = Regex("\\{[^}]*\\}")
}
