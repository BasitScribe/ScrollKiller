package com.scrollkiller.data.db

import androidx.room.Entity

/**
 * On-device mirror of one row of the server `daily_counts` table (docs/SCHEMA.md).
 *
 * The device is the source of truth (offline-first); this is where detected reel
 * advances accumulate before Phase-3 sync. Composite PK `(date, platform)` matches
 * the server shape so sync stays a straight mapping.
 *
 * @param date ISO-8601 local date `yyyy-MM-dd`. INTERIM: this is the device-local
 *   date; the server timezone-truth day boundary replaces it in Phase 3 (see
 *   docs/SCHEMA.md "Day boundary" and invariant #2).
 * @param platform a [com.scrollkiller.service.Platform.id] value.
 */
@Entity(tableName = "daily_counts", primaryKeys = ["date", "platform"])
data class DailyCountEntity(
    val date: String,
    val platform: String,
    val count: Int,
)
