package com.scrollkiller

import android.app.Application
import com.scrollkiller.data.CountRepository
import com.scrollkiller.data.db.ScrollKillerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Custom [Application] class — created once, before any Activity/Service, and lives
 * for the whole process lifetime. Registered via android:name in AndroidManifest.xml.
 *
 * It owns the app-wide singletons so both the AccessibilityService and the UI share
 * one database + repository instance. A plain manual graph is enough here — no DI
 * framework (keeps the ₹0 / low-complexity constraint).
 */
class ScrollKillerApp : Application() {

    /** Process-lifetime IO scope for fire-and-forget count writes. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: ScrollKillerDatabase by lazy { ScrollKillerDatabase.build(this) }

    val countRepository: CountRepository by lazy {
        CountRepository(database.dailyCountDao(), database.scrollEventDao(), appScope)
    }
}
