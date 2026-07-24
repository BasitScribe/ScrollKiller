package com.scrollkiller.data

import com.scrollkiller.data.db.DailyCountDao
import com.scrollkiller.data.db.DailyCountEntity
import com.scrollkiller.data.db.ScrollEvent
import com.scrollkiller.data.db.ScrollEventDao
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The single point between short-video detection and stored counts. Backed by Room, so
 * counts survive process death.
 *
 * Every advance dual-writes: it increments the `daily_counts` aggregate (the counter
 * source of truth that powers the UI/bubble Flows) AND appends a raw [ScrollEvent].
 * The aggregate is kept forever; the raw log is pruned after [RAW_RETENTION_DAYS]
 * (invariant #4 / D4).
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
     * Record one detected advance for [spec]'s platform. Fire-and-forget: the caller
     * (the service) must not block, and a dropped write on process death is acceptable
     * (at most one advance lost). Dual-writes aggregate + raw, then lazily prunes.
     *
     * TODO(Phase 3): the day key and timestamp are DEVICE-LOCAL. Replace with the
     * server timezone-truth boundary — see docs/SCHEMA.md "Day boundary" and
     * invariant #2 (the client must never decide the date long-term).
     */
    fun record(spec: PlatformSpec, atMs: Long = System.currentTimeMillis()) {
        scope.launch {
            dao.increment(today(), spec.platform.id)
            eventDao.insertScroll(
                ScrollEvent(
                    platform = spec.platform.id,
                    appPackage = spec.packageName,
                    timestamp = atMs,
                    countedAs = spec.unitNoun,
                ),
            )
            maybePrune(atMs)
        }
    }

    /**
     * Live total across all platforms for the current day. The date is resolved at
     * collection time, so reopening the app after midnight observes the new day.
     */
    fun observeToday(): Flow<Int> = dao.observeTotalForDate(today())

    /**
     * Live count for a single [platform] for the current day. Used by the on-surface
     * overlay (bubble/block). Same Room source of truth as [observeToday] — a
     * per-platform projection, not a second counter.
     */
    fun observeToday(platform: Platform): Flow<Int> =
        dao.observeCountForDatePlatform(today(), platform.id)

    /** Live per-platform breakdown for the current day (highest first). Powers the dashboard. */
    fun observeBreakdown(): Flow<List<DailyCountEntity>> = dao.observeCountsForDate(today())

    /** Raw events since local midnight, newest first (recent detail, not history). */
    suspend fun getScrollsToday(): List<ScrollEvent> = eventDao.getScrollsSince(startOfTodayMs())

    /** Raw events for one platform, newest first. */
    suspend fun getScrollsByPlatform(platform: Platform): List<ScrollEvent> =
        eventDao.getScrollsByPlatform(platform.id)

    /** Settings → Clear data: wipe both aggregates and raw events. */
    suspend fun clearAll() {
        dao.deleteAll()
        eventDao.deleteAll()
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
