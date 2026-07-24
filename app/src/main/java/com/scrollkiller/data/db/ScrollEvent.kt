package com.scrollkiller.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One detected short-video advance, stored raw.
 *
 * This is the granular log that sits UNDER the [DailyCountEntity] aggregate: every
 * advance dual-writes here (raw) and increments `daily_counts` (aggregate). Per
 * CLAUDE.md invariant #4 and D4, raw events are PRUNED after 7 days
 * ([ScrollEventDao.deleteOlderThan]) while the daily aggregates are kept forever — so
 * this table stays small and is used for recent breakdowns/detail, not history.
 *
 * @param id auto-generated row id.
 * @param platform a [com.scrollkiller.service.Platform.id] value ("instagram", …).
 * @param appPackage the source app package (e.g. "com.instagram.android").
 * @param timestamp event time, epoch millis (device clock — interim, like the day
 *   boundary; the server is the long-term time-truth per invariant #2).
 * @param countedAs the unit this advance was counted as ("reel", "short", …), from
 *   [com.scrollkiller.service.PlatformSpec.unitNoun].
 */
@Entity(tableName = "scroll_events")
data class ScrollEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val appPackage: String,
    val timestamp: Long,
    val countedAs: String,
)
