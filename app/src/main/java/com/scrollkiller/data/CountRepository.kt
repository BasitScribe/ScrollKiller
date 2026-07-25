package com.scrollkiller.data

import com.scrollkiller.data.db.DailyCountDao
import com.scrollkiller.data.db.DailyCountEntity
import com.scrollkiller.data.db.ScrollEvent
import com.scrollkiller.data.db.ScrollEventDao
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Today's counts as the overlay needs them: one grand [total] plus the [perPlatform] split
 * behind it.
 *
 * The total is the user-facing number (D35). [perPlatform] contains ONLY platforms with at
 * least one advance today — a `daily_counts` row is created lazily on first increment — so
 * callers must treat a missing platform as 0 rather than assuming every platform is present.
 */
data class TodaySummary(
    val total: Int,
    val perPlatform: Map<Platform, Int>,
) {
    /** Today's count for [platform], 0 when it has no row yet. */
    fun countFor(platform: Platform): Int = perPlatform[platform] ?: 0

    companion object {
        /** Before the first emission. */
        val EMPTY = TodaySummary(total = 0, perPlatform = emptyMap())
    }
}

/**
 * Counts that have been DETECTED but whose Room write may not have come back round yet, plus
 * the day they belong to. The in-memory half of the optimistic read path (D39).
 *
 * A running TOTAL per platform, not a pending delta, which is what makes reconciliation with
 * Room a plain `max` — see [CountRepository.merge].
 */
internal data class PendingCounts(val date: String, val perPlatform: Map<Platform, Int>) {

    /** This day's counts with one more advance on [platform]. */
    fun plus(platform: Platform): PendingCounts =
        copy(perPlatform = perPlatform + (platform to (perPlatform[platform] ?: 0) + 1))

    companion object {
        /** Nothing detected this process yet (and after a data clear). Date never matches. */
        val NONE = PendingCounts(date = "", perPlatform = emptyMap())
    }
}

/**
 * Reconciles Room's persisted counts with the not-yet-written [PendingCounts]. Pure and
 * database-free, like [com.scrollkiller.service.BlockPolicy] and
 * [com.scrollkiller.service.BubbleBreakdown], and for the same reason: this is the one piece of
 * the D39 optimistic path that can be silently wrong — a count that flickers backwards, or one
 * that a data clear can't zero — and neither is something you would notice by looking at a
 * bubble.
 */
internal object TodayMerge {

    /**
     * Today's numbers as the UI should see them: [rows] from Room, raised to [inFlight]
     * wherever a detected advance hasn't come back round yet.
     *
     * `max` per platform rather than a sum, because [PendingCounts] holds a running total for
     * the day and not a delta — so once a write lands, both sides hold the same number and the
     * max is a no-op. That is also what makes it safe for the two to be out of step in EITHER
     * direction: a Room emission for advance N arriving after advance N+1 was detected keeps
     * N+1 (monotonic, never a visible flicker backwards), and a pending map left over from a
     * stale day is ignored entirely because its date won't match [date].
     *
     * Rows for a platform not in the [Platform] enum are dropped rather than guessed at — the
     * table is keyed by [Platform.id] strings and could outlive a renamed enum entry.
     */
    fun merge(
        date: String,
        rows: List<DailyCountEntity>,
        inFlight: PendingCounts,
    ): TodaySummary {
        val persisted = rows.mapNotNull { row ->
            Platform.entries.firstOrNull { it.id == row.platform }?.let { it to row.count }
        }.toMap()
        val unwritten = if (inFlight.date == date) inFlight.perPlatform else emptyMap()
        if (unwritten.isEmpty()) {
            return TodaySummary(total = persisted.values.sum(), perPlatform = persisted)
        }
        val merged = (persisted.keys + unwritten.keys).associateWith { platform ->
            maxOf(persisted[platform] ?: 0, unwritten[platform] ?: 0)
        }
        return TodaySummary(total = merged.values.sum(), perPlatform = merged)
    }
}

/**
 * The single point between short-video detection and stored counts. Backed by Room, so
 * counts survive process death.
 *
 * Every advance dual-writes: it increments the `daily_counts` aggregate (the counter
 * source of truth that powers the UI/bubble Flows) AND appends a raw [ScrollEvent].
 * The aggregate is kept forever; the raw log is pruned after [RAW_RETENTION_DAYS]
 * (invariant #4 / D4).
 *
 * ## Why reads don't wait for the write (D39)
 * [record] is fire-and-forget, so before D39 the number the user saw was gated on a full
 * round trip: two SQLite commits on [Dispatchers.IO], then Room's [InvalidationTracker]
 * noticing the table changed, re-running the query, and only then emitting. Under an
 * Instagram/YouTube foreground load that is tens to low hundreds of ms — a visible beat after
 * the swipe before the bubble moved.
 *
 * So [record] now bumps an in-memory [PendingCounts] SYNCHRONOUSLY, on the caller's thread,
 * before dispatching the write, and every read Flow merges that with Room. Room is still the
 * source of truth and still the only thing that survives process death; the in-memory map is a
 * read-through cache of writes already issued, never a second counter — nothing increments it
 * that does not also go to Room in the same call.
 *
 * Reconciliation is `max(room, pending)` per platform, which works because [PendingCounts]
 * holds a running total rather than a delta: both sides converge on the same number as the
 * writes land, and an emission that arrives mid-flight (Room reflecting advance N while
 * advance N+1 is already pending) simply keeps the larger, so the count is MONOTONIC within a
 * day and can never flicker backwards. The two ways a count legitimately goes DOWN are handled
 * by dropping the pending state rather than by the merge: [clearAll] resets it, and a day
 * rollover no longer matches [PendingCounts.date].
 *
 * @param dao aggregate counts.
 * @param eventDao raw event log.
 * @param scope an application-scoped IO scope. [record] is called from the
 *   AccessibilityService's main thread, so writes are dispatched off it here.
 * @param zone the timezone used for the interim device-local day boundary.
 * @param today supplies the current day key; injectable so tests can pin a date.
 */
class CountRepository(
    private val dao: DailyCountDao,
    private val eventDao: ScrollEventDao,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> String = { LocalDate.now(zone).toString() },
) {

    /** Epoch millis of the last prune, so we don't prune on every single advance. */
    @Volatile
    private var lastPruneMs = 0L

    /**
     * Advances detected this process, merged into every read Flow so the UI doesn't wait on
     * the DB round trip. See the class doc for why this is a cache and not a second counter.
     */
    private val pending = MutableStateFlow(PendingCounts.NONE)

    /**
     * Record one detected advance for [spec]'s platform. Fire-and-forget for the DURABLE
     * write: the caller (the service) must not block, and a dropped write on process death is
     * acceptable (at most one advance lost). Dual-writes aggregate + raw, then lazily prunes.
     *
     * The in-memory bump, by contrast, happens SYNCHRONOUSLY on the caller's thread and before
     * the dispatch, so the emission that moves the bubble is already in flight by the time this
     * returns (D39). It is a map update and a StateFlow set — nothing that can block the
     * accessibility service.
     *
     * The day key is resolved HERE rather than inside the coroutine, so both halves file the
     * advance under the day it was detected on, not the day the IO thread happened to run on.
     * Across a midnight boundary those could otherwise differ, and the in-memory count would be
     * reconciled against a Room row for the wrong date.
     *
     * TODO(Phase 3): the day key and timestamp are DEVICE-LOCAL. Replace with the
     * server timezone-truth boundary — see docs/SCHEMA.md "Day boundary" and
     * invariant #2 (the client must never decide the date long-term).
     */
    fun record(spec: PlatformSpec, atMs: Long = System.currentTimeMillis()) {
        CountLatency.begin(spec.platform)
        val date = today()
        pending.update { current ->
            // A new day starts the pending map over rather than adding to yesterday's.
            if (current.date == date) current.plus(spec.platform)
            else PendingCounts(date, mapOf(spec.platform to 1))
        }
        CountLatency.optimistic()

        scope.launch {
            dao.increment(date, spec.platform.id)
            eventDao.insertScroll(
                ScrollEvent(
                    platform = spec.platform.id,
                    appPackage = spec.packageName,
                    timestamp = atMs,
                    countedAs = spec.unitNoun,
                ),
            )
            CountLatency.persisted()
            maybePrune(atMs)
        }
    }

    /**
     * Everything the on-surface overlay needs, from ONE Flow: today's grand total plus the
     * per-platform split behind it.
     *
     * The total is THE number the user is shown (D35) — 30 reels + 10 shorts is one 40-item
     * doomscrolling day, not two separate scores — while the split feeds the bubble's breakdown
     * bars and the per-platform limit the block still keys off. Derived from the breakdown query
     * rather than collecting the SUM separately so the two can never disagree mid-emission, and
     * so the overlay runs one collector instead of two.
     *
     * The date is resolved at collection time, so a collector started after midnight observes
     * the new day. Room's rows are merged with the in-memory [pending] counts (D39) — see the
     * class doc; [merge] is where the reconciliation rule lives.
     *
     * THE read Flow: [observeTodayTotal], [observeToday] and [observeBreakdown] are all
     * projections of this one, so no surface can show a number another surface disagrees with
     * (D35) and none of them can miss the optimistic path.
     */
    fun observeTodaySummary(): Flow<TodaySummary> {
        val date = today()
        return combine(dao.observeCountsForDate(date), pending) { rows, inFlight ->
            TodayMerge.merge(date, rows, inFlight)
        }.distinctUntilChanged()
    }

    /**
     * Live total across all platforms for the current day.
     *
     * Named `…Total` rather than `observeToday()` deliberately: it used to be one half of an
     * overload pair with the per-platform projection, and the overlay bubble bound to the wrong
     * half — showing 10 on YouTube and 30 on Instagram instead of 40 (D35).
     */
    fun observeTodayTotal(): Flow<Int> =
        observeTodaySummary().map { it.total }.distinctUntilChanged()

    /**
     * Live count for a single [platform] for the current day. A projection of
     * [observeTodaySummary] — not a second counter.
     */
    fun observeToday(platform: Platform): Flow<Int> =
        observeTodaySummary().map { it.countFor(platform) }.distinctUntilChanged()

    /** Raw events since local midnight, newest first (recent detail, not history). */
    suspend fun getScrollsToday(): List<ScrollEvent> = eventDao.getScrollsSince(startOfTodayMs())

    /** Raw events for one platform, newest first. */
    suspend fun getScrollsByPlatform(platform: Platform): List<ScrollEvent> =
        eventDao.getScrollsByPlatform(platform.id)

    /**
     * Settings → Clear data: wipe both aggregates and raw events.
     *
     * Drops the in-memory [pending] counts too, and that is not optional: [merge] takes the max
     * of Room and pending, so a clear that only emptied the table would be immediately undone by
     * an in-memory total the user just asked to delete.
     *
     * Reset LAST, after both deletes. An advance detected mid-clear then loses BOTH halves and
     * the two stay consistent at zero. Resetting first would leave that advance in pending with
     * its Room row already deleted — and because pending holds a running total, every later
     * advance would carry that orphan forward as a permanent +1 against the table.
     */
    suspend fun clearAll() {
        dao.deleteAll()
        eventDao.deleteAll()
        pending.value = PendingCounts.NONE
    }

    /** Prune raw events older than the retention window, at most once per hour. */
    private suspend fun maybePrune(nowMs: Long) {
        if (nowMs - lastPruneMs < PRUNE_INTERVAL_MS) return
        lastPruneMs = nowMs
        eventDao.deleteOlderThan(nowMs - RAW_RETENTION_MS)
    }

    private fun startOfTodayMs(): Long =
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        const val RAW_RETENTION_DAYS = 7L
        val RAW_RETENTION_MS = TimeUnit.DAYS.toMillis(RAW_RETENTION_DAYS)
        val PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }
}
