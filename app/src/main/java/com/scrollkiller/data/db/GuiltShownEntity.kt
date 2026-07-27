package com.scrollkiller.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * When a guilt line was last shown. One row per line id, overwritten each time it is shown.
 *
 * ## Why this is persisted at all (D47)
 * The no-repeat rule used to be session-scoped: don't repeat until the pool cycles, forgotten on
 * process death. With the D46 cadence firing every 2–5 scrolls at a high count, a pool cycles in
 * minutes, so "no repeat" had effectively stopped meaning anything. The rule is now a rolling
 * 7-DAY window, and a window that outlives the process has to be on disk.
 *
 * LAST shown, not every showing: the only question ever asked is "was this within the window",
 * plus "which is stalest" when the pool is exhausted. A full log would answer both too, and would
 * grow without bound at the top of the cadence for no extra information.
 *
 * @param lineId the pack line's stable id. PK, so re-showing a line updates in place and the
 *   table can never exceed the pack's size.
 * @param shownAt WALL-CLOCK epoch millis. Wall clock and not `elapsedRealtime` because the value
 *   must stay comparable across reboots, which is the entire point of persisting it. (The D46
 *   display gap uses the monotonic clock for the opposite reason — see [com.scrollkiller.guilt
 *   .GuiltFiring].)
 */
@Entity(tableName = "guilt_shown")
data class GuiltShownEntity(
    @PrimaryKey val lineId: String,
    val shownAt: Long,
)

/**
 * Room access to the guilt-line history. Retention arithmetic stays OUT of here — callers pass
 * an epoch-millis cutoff — matching [ScrollEventDao]'s posture, so the 7-day window is defined in
 * exactly one place.
 */
@Dao
interface GuiltShownDao {

    /** Record (or re-record) that a line was shown. REPLACE, since only the latest matters. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GuiltShownEntity)

    /**
     * The whole table. Bounded by the pack's line count (a few hundred at most), read once at
     * process start to prime the in-memory cache the selector reads synchronously.
     */
    @Query("SELECT * FROM guilt_shown")
    suspend fun getAll(): List<GuiltShownEntity>

    /** Drop rows older than [timestampMs] — outside the window, so they can no longer exclude. */
    @Query("DELETE FROM guilt_shown WHERE shownAt < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long): Int

    /** Wipe the history (Settings → Clear data). */
    @Query("DELETE FROM guilt_shown")
    suspend fun deleteAll()
}
