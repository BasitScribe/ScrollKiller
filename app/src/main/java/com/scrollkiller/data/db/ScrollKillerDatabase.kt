package com.scrollkiller.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The app's Room database. Version 1 — bump + add a Migration when the schema changes. */
@Database(entities = [DailyCountEntity::class], version = 1, exportSchema = true)
abstract class ScrollKillerDatabase : RoomDatabase() {

    abstract fun dailyCountDao(): DailyCountDao

    companion object {
        private const val DB_NAME = "scrollkiller.db"

        fun build(context: Context): ScrollKillerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ScrollKillerDatabase::class.java,
                DB_NAME,
            ).build()
    }
}
