package com.scrollkiller.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrollkiller.ScrollKillerApp
import com.scrollkiller.data.SettingsPrefs
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One platform's line on the dashboard: its label, unit, and today's count. */
data class PlatformCount(
    val platform: Platform,
    val displayName: String,
    val unitNoun: String,
    val count: Int,
)

/**
 * Backs the tabbed dashboard ([DashboardScreen]). [AndroidViewModel] so it can reach the
 * app-scoped [com.scrollkiller.data.CountRepository] and settings without a DI framework.
 *
 * All read state is derived from the SAME Room source the detector writes and the bubble
 * reads — no second counter (invariant: one source of truth).
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as ScrollKillerApp).countRepository

    /** Live grand total across all platforms today. */
    val total: StateFlow<Int> = repository.observeToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Live per-platform breakdown. Every enabled platform appears (filled to 0 from the
     * registry when it has no row yet), sorted by count desc then name — so the Today and
     * Apps tabs show a stable, complete list.
     */
    val breakdown: StateFlow<List<PlatformCount>> = repository.observeBreakdown()
        .map { rows ->
            val byId = rows.associate { it.platform to it.count }
            PlatformRegistry.enabled
                .map { spec ->
                    PlatformCount(
                        platform = spec.platform,
                        displayName = spec.displayName,
                        unitNoun = spec.unitNoun,
                        count = byId[spec.platform.id] ?: 0,
                    )
                }
                .sortedWith(compareByDescending<PlatformCount> { it.count }.thenBy { it.displayName })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _bubbleEnabled = MutableStateFlow(SettingsPrefs.isBubbleEnabled(app))

    /** Whether the floating bubble is allowed (Settings toggle; read by the overlay). */
    val bubbleEnabled: StateFlow<Boolean> = _bubbleEnabled

    fun setBubbleEnabled(enabled: Boolean) {
        SettingsPrefs.setBubbleEnabled(getApplication(), enabled)
        _bubbleEnabled.value = enabled
    }

    /** Settings → Clear data: wipe aggregates + raw events. */
    fun clearData() {
        viewModelScope.launch { repository.clearAll() }
    }
}
