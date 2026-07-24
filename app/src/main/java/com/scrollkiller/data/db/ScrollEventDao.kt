package com.scrollkiller.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Room access to the raw `scroll_events` log (the granular counterpart to
 * [DailyCountDao]'s aggregate). Date arithmetic is kept OUT of here — callers pass
 * epoch-millis bounds — so the DAO stays pure and the day-boundary policy lives in
 * one place ([com.scrollkiller.data.CountRepository]).
 */
@Dao
interface ScrollEventDao {

    /** Append one detected advance. */
    @Insert
    suspend fun insertScroll(event: ScrollEvent)

    /**
     * All events at or after [sinceMs] (caller computes the day's start), newest first.
     * The repository's `getScrollsToday()` passes local midnight.
     */
    @Query("SELECT * FROM scroll_events WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getScrollsSince(sinceMs: Long): List<ScrollEvent>

    /** All events for one platform, newest first. */
    @Query("SELECT * FROM scroll_events WHERE platform = :platform ORDER BY timestamp DESC")
    suspend fun getScrollsByPlatform(platform: String): List<ScrollEvent>

    /**
     * Prune raw events older than [timestampMs] (invariant #4 / D4: raw pruned >7d,
     * aggregates kept forever). Returns the number of rows deleted.
     */
    @Query("DELETE FROM scroll_events WHERE timestamp < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long): Int

    /** Wipe all raw events (Settings → Clear data). Aggregates are cleared separately. */
    @Query("DELETE FROM scroll_events")
    suspend fun deleteAll()
}
