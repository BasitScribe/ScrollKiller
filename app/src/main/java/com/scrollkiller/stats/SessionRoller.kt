package com.scrollkiller.stats

/**
 * Turns a day's raw scroll timestamps into the seconds actually spent scrolling (D80).
 *
 * ## Why this exists at all
 * [TimeEstimate] multiplies a count by a flat per-item guess. That is honest but crude: it cannot
 * tell forty reels watched in one sitting from forty spread across a day, and it charges the same
 * six seconds for a Short someone skipped in one and one they watched to the end.
 *
 * Timestamps can do better — but only for seven days, because `scroll_events` is pruned at
 * [com.scrollkiller.data.CountRepository.RAW_RETENTION_DAYS] and invariant 4 says it stays that
 * way. So the honest number is computed while the evidence exists and the RESULT is stored in the
 * forever-kept aggregate; the raw log still disappears on schedule. That is the whole design.
 *
 * ## Android-free on purpose
 * No Android imports and no clock: every input is a parameter, so session-splitting is unit-testable
 * off-device. Same rule as [StreakCalculator], [com.scrollkiller.service.BlockPolicy] and
 * [com.scrollkiller.brain.BrainState].
 */
object SessionRoller {

    /**
     * Silence long enough to call the next scroll a NEW sitting, in millis.
     *
     * Five minutes sits between the two ways this can be wrong. Shorter, and a pause to read a
     * comment thread or reply to a message splits one sitting into three, inflating the session
     * count while leaving total time roughly right. Longer, and a morning scroll and an evening
     * scroll merge into one "session" that spans lunch — which would be catastrophic here, because
     * a session's duration is measured end to end, so the hours between would be counted as
     * scrolling.
     *
     * The asymmetry is what fixes the value: over-splitting costs accuracy in a number nobody
     * reads (how many sittings), under-splitting corrupts the number everybody reads (how long).
     * When in doubt, split.
     */
    const val SESSION_GAP_MS = 5 * 60_000L

    /**
     * Seconds credited to the LAST item of a session.
     *
     * A session's span is `last - first`, which is the time between the first swipe and the last
     * one. That deliberately misses the item the user was actually watching when they stopped, and
     * for a one-event session it produces zero — someone who opened Reels, watched one, and left
     * would be recorded as having spent no time at all.
     *
     * Reusing [TimeEstimate.AVG_SECONDS_PER_ITEM] rather than inventing a second constant keeps
     * the flat estimate and the session estimate anchored to the same assumption, so the two can
     * be compared and the ramp in B2 does not have a step change baked into it.
     */
    const val TAIL_SECONDS = TimeEstimate.AVG_SECONDS_PER_ITEM

    /**
     * Split [timestampsMs] into sittings. Input need not be sorted; the result is, and each element
     * is that session's timestamps in ascending order. An empty input yields no sessions.
     */
    fun sessions(
        timestampsMs: List<Long>,
        gapMs: Long = SESSION_GAP_MS,
    ): List<List<Long>> {
        if (timestampsMs.isEmpty()) return emptyList()
        val sorted = timestampsMs.sorted()
        val out = mutableListOf<MutableList<Long>>()
        var current = mutableListOf(sorted.first())
        for (t in sorted.drop(1)) {
            if (t - current.last() > gapMs) {
                out += current
                current = mutableListOf(t)
            } else {
                current += t
            }
        }
        out += current
        return out
    }

    /**
     * Total seconds spent scrolling across every sitting in [timestampsMs].
     *
     * Each session contributes `(last - first)` plus [tailSeconds] for the item still on screen
     * when it ended. Gaps BETWEEN sessions are never counted — that is the entire reason sessions
     * are split before summing rather than measuring first-to-last across the whole day.
     *
     * Returns 0 for no events. Never negative: duplicate or equal timestamps collapse to a session
     * of span zero, which still earns its tail.
     */
    fun secondsFor(
        timestampsMs: List<Long>,
        gapMs: Long = SESSION_GAP_MS,
        tailSeconds: Int = TAIL_SECONDS,
    ): Long {
        val sessions = sessions(timestampsMs, gapMs)
        if (sessions.isEmpty()) return 0L
        return sessions.sumOf { session ->
            val spanMs = session.last() - session.first()
            spanMs / 1000L + tailSeconds
        }
    }
}
