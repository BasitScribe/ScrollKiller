package com.scrollkiller.data

import com.scrollkiller.data.db.DailyCountDao
import com.scrollkiller.data.db.DailyMinutesDao
import com.scrollkiller.data.db.DailyMinutesEntity
import com.scrollkiller.data.db.DailyCountEntity
import com.scrollkiller.data.db.ScrollEvent
import com.scrollkiller.data.db.ScrollEventDao
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.scrollkiller.stats.SessionRoller
import java.time.Instant
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
 * One platform's total across a date RANGE — the Insights per-app breakdown (D82).
 *
 * Distinct from [TodaySummary.perPlatform], which is a single day: this collapses the date
 * dimension entirely, so it cannot be derived from the day map without walking every row.
 */
data class PlatformRangeTotal(val platform: Platform, val total: Int)

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
    private val minutesDao: DailyMinutesDao,
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
    /**
     * @param sourcePackage the package the event actually came from. Defaults to the spec's
     *   canonical one; the service passes the real value so a platform with variants records WHICH
     *   client produced each raw event (D52). The AGGREGATE row stays keyed on the platform — a
     *   user's Shorts habit is one habit whether it arrives via Google's client or ReVanced — but
     *   the raw event keeping the true package is what makes a surface tour on a modified client
     *   readable.
     */
    fun record(
        spec: PlatformSpec,
        atMs: Long = System.currentTimeMillis(),
        sourcePackage: String = spec.packageName,
    ) {
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
                    appPackage = sourcePackage,
                    timestamp = atMs,
                    countedAs = spec.unitNoun,
                ),
            )
            CountLatency.persisted()
            rollUpAndPrune(atMs)
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
    /* --- RANGE reads for Insights (D82) ---------------------------------------------------
     *
     * Both count reads below fold in TODAY's value from [observeTodaySummary] rather than trusting
     * the `daily_counts` row alone, and that is the point rather than a detail.
     *
     * Today's Room row lags the in-memory [pending] counts by a DB round trip (D39/D65), so a range
     * query reading the table alone would draw a last bar one or two lower than the hero numeral the
     * Today tab is showing at that same instant. Two surfaces disagreeing about today is exactly the
     * defect D35 existed to kill, and it is MORE visible here, not less: the trend's final bar is one
     * tab-switch from the number it has to match.
     *
     * Composing the existing today Flow instead of re-implementing the merge is what makes them
     * agree BY CONSTRUCTION — there is one merge rule in this class and both paths run it.
     */

    /**
     * Total per day across the inclusive range, keyed by ISO date. Days with no row are ABSENT
     * rather than zero; [com.scrollkiller.stats.TrendBuckets] fills the gaps, because only the
     * caller knows whether a missing day should be a zero bar or no bar at all.
     *
     * Today's entry is OVERRIDDEN with the merged value — exact, because the map is keyed by date
     * so replacing one key cannot disturb the others.
     */
    fun observeDailyTotalsBetween(from: String, to: String): Flow<Map<String, Int>> {
        val key = today()
        return combine(
            dao.observeDailyTotalsBetween(from, to),
            observeTodaySummary(),
        ) { rows, todaySummary ->
            val out = rows.associate { it.date to it.total }.toMutableMap()
            if (key in from..to) out[key] = todaySummary.total
            out.toMap()
        }.distinctUntilChanged()
    }

    /**
     * Total per platform across the whole inclusive range, biggest first.
     *
     * The date dimension is collapsed here, so today cannot simply be overridden the way it is
     * above — its Room contribution is already summed into each platform's figure and is not
     * separable afterwards. So the QUERY is asked for the range up to YESTERDAY and today's merged
     * per-platform counts are added on top. Subtracting an assumed Room value instead would double-
     * count or under-count the moment the two disagreed, which is precisely when it matters.
     *
     * An inverted range (`from` after the adjusted `to`, i.e. the window is only today) returns no
     * rows from SQLite, so it needs no special case.
     */
    fun observePlatformTotalsBetween(from: String, to: String): Flow<List<PlatformRangeTotal>> {
        val key = today()
        val includesToday = key in from..to
        val daoTo = if (includesToday) LocalDate.parse(key).minusDays(1).toString() else to
        return combine(
            dao.observePlatformTotalsBetween(from, daoTo),
            observeTodaySummary(),
        ) { rows, todaySummary ->
            val out = mutableMapOf<Platform, Int>()
            rows.forEach { row ->
                Platform.entries.firstOrNull { it.id == row.platform }
                    ?.let { out[it] = (out[it] ?: 0) + row.total }
            }
            if (includesToday) {
                todaySummary.perPlatform.forEach { (platform, count) ->
                    out[platform] = (out[platform] ?: 0) + count
                }
            }
            out.filterValues { it > 0 }
                .map { (platform, total) -> PlatformRangeTotal(platform, total) }
                .sortedByDescending { it.total }
        }.distinctUntilChanged()
    }

    /**
     * Daily totals across ALL stored history — what milestones are derived from (D83).
     *
     * Reuses [observeDailyTotalsBetween] with an open lower bound rather than adding a query,
     * because the ISO-8601 `yyyy-MM-dd` key sorts lexicographically in the same order it sorts
     * chronologically (the reason D14 chose it), so `BETWEEN '0000-01-01' AND today` is every row
     * up to today and nothing after. Today's merge therefore applies here too, for free.
     *
     * Bounded in practice: `daily_counts` holds at most a handful of rows per day, so even years of
     * history is a few thousand small rows — and milestones ask an all-time question that a windowed
     * query cannot answer.
     */
    fun observeAllDailyTotals(): Flow<Map<String, Int>> =
        observeDailyTotalsBetween(EARLIEST_POSSIBLE_DATE, today())

    /** Session-derived seconds across the inclusive range (D80). 0 where nothing is rolled up yet. */
    fun observeSecondsBetween(from: String, to: String): Flow<Long> =
        minutesDao.observeTotalSecondsBetween(from, to).distinctUntilChanged()

    /**
     * Earliest day carrying a time rollup, or null. Lets Insights state what its time figure is
     * measured FROM rather than presenting a partial sum as a whole-range total — D80's ramp.
     */
    fun observeEarliestRolledDate(): Flow<String?> =
        minutesDao.observeEarliestRolledDate().distinctUntilChanged()

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
        // The time rollup is derived from the events being deleted, so it goes with them — leaving
        // it would show a history of minutes for days whose counts the user just cleared.
        minutesDao.deleteAll()
        pending.value = PendingCounts.NONE
    }

    /**
     * Roll each day's sessions into the permanent time aggregate, THEN prune the raw events. At
     * most once per hour (D80).
     *
     * ## The ordering is the whole point
     * `scroll_events` carries the only timestamps this app ever has, and invariant 4 deletes them
     * after seven days. Pruning first would throw away the evidence before the number is computed,
     * so time older than a week could never be anything better than a flat count guess. Rolling
     * first means the raw log still disappears exactly on schedule — what survives is one integer
     * per day, the same shape and privacy posture `daily_counts` already has.
     *
     * ## Why today and past days are written differently
     * The prune cutoff is ROLLING (`now - 7d`), so the oldest day loses its events gradually
     * through the day rather than all at once. Re-rolling such a day would read only the events not
     * yet deleted and record a number that had silently SHRUNK since it was correct. So past days
     * use `insertIfAbsent` — first write wins, and the first write for a completed day is the
     * complete one, since a day that has ended keeps all its events for roughly six more days.
     * Today uses `upsert`, for the mirror-image reason: it is still accumulating, every pass has
     * strictly more evidence than the last, and its events are hours old so a partial read is not
     * possible.
     *
     * Reads all remaining raw events, which is bounded by construction: the prune keeps at most
     * seven days of them.
     */
    private suspend fun rollUpAndPrune(nowMs: Long) {
        if (nowMs - lastPruneMs < PRUNE_INTERVAL_MS) return
        lastPruneMs = nowMs

        val todayKey = today()
        eventDao.getScrollsSince(0L)
            .groupBy { event ->
                LocalDate.ofInstant(Instant.ofEpochMilli(event.timestamp), zone).toString()
            }
            .forEach { (date, events) ->
                val row = DailyMinutesEntity(
                    date = date,
                    seconds = SessionRoller.secondsFor(events.map { it.timestamp }),
                    computedAt = nowMs,
                )
                if (date == todayKey) minutesDao.upsert(row) else minutesDao.insertIfAbsent(row)
            }

        eventDao.deleteOlderThan(nowMs - RAW_RETENTION_MS)
    }

    private fun startOfTodayMs(): Long =
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        /** Lower bound for an "all history" range read. Sorts before any real ISO date. */
        const val EARLIEST_POSSIBLE_DATE = "0000-01-01"

        const val RAW_RETENTION_DAYS = 7L
        val RAW_RETENTION_MS = TimeUnit.DAYS.toMillis(RAW_RETENTION_DAYS)
        val PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }
}
