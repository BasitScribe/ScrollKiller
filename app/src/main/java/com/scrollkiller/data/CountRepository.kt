package com.scrollkiller.data

import com.scrollkiller.data.db.DailyCountDao
import com.scrollkiller.service.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The single point between reel detection and stored counts. Backed by Room, so
 * counts survive process death.
 *
 * @param scope an application-scoped IO scope. [record] is called from the
 *   AccessibilityService's main thread, so writes are dispatched off it here.
 * @param today supplies the current day key; injectable so tests can pin a date.
 */
class CountRepository(
    private val dao: DailyCountDao,
    private val scope: CoroutineScope,
    private val today: () -> String = { LocalDate.now().toString() },
) {

    /**
     * Record one detected reel advance for [platform]. Fire-and-forget: the caller
     * (the service) must not block, and a dropped write on process death is
     * acceptable (at most one advance lost).
     *
     * TODO(Phase 3): the day key is the DEVICE-LOCAL date. Replace with the
     * server timezone-truth boundary — see docs/SCHEMA.md "Day boundary" and
     * invariant #2 (the client must never decide the date long-term).
     */
    fun record(platform: Platform) {
        scope.launch { dao.increment(today(), platform.id) }
    }

    /**
     * Live total across all platforms for the current day. The date is resolved at
     * collection time, so reopening the app after midnight observes the new day.
     * (A session left open across midnight keeps showing the old day — acceptable
     * under the interim local-rollover boundary; Phase 3 fixes this properly.)
     */
    fun observeToday(): Flow<Int> = dao.observeTotalForDate(today())

    /**
     * Live count for a single [platform] for the current day. Used by the on-surface
     * overlay (bubble/block), which is contextual to the app you're in, whereas
     * [observeToday] powers the Home dashboard total. Same Room source of truth — a
     * per-platform projection, not a second counter.
     */
    fun observeToday(platform: Platform): Flow<Int> =
        dao.observeCountForDatePlatform(today(), platform.id)
}
