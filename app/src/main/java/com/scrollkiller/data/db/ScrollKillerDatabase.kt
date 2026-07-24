package com.scrollkiller.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database.
 *
 * v1 — daily_counts aggregate only.
 * v2 — added the raw scroll_events log (invariant #4 / D4): every advance dual-writes
 *      a raw row here and increments the aggregate. See [MIGRATION_1_2].
 */
@Database(
    entities = [DailyCountEntity::class, ScrollEvent::class],
    version = 2,
    exportSchema = true,
)
abstract class ScrollKillerDatabase : RoomDatabase() {

    abstract fun dailyCountDao(): DailyCountDao

    abstract fun scrollEventDao(): ScrollEventDao

    companion object {
        private const val DB_NAME = "scrollkiller.db"

        /**
         * Adds scroll_events. The CREATE statement is written to match Room's own
         * generated schema exactly (column order, `AUTOINCREMENT`, NOT NULL), so Room's
         * on-open identity check passes — a mismatch here throws at runtime.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scroll_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`platform` TEXT NOT NULL, " +
                        "`appPackage` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`countedAs` TEXT NOT NULL)",
                )
            }
        }

        fun build(context: Context): ScrollKillerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ScrollKillerDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
