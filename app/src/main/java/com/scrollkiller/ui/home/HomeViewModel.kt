package com.scrollkiller.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrollkiller.ScrollKillerApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Exposes today's live reel count to the Home screen.
 *
 * [AndroidViewModel] so it can reach the app-scoped [com.scrollkiller.data.CountRepository]
 * without a DI framework. The repository Flow is cached as a [StateFlow] tied to
 * [viewModelScope] and only kept warm while the UI is subscribed.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as ScrollKillerApp).countRepository

    val count: StateFlow<Int> = repository.observeToday()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )
}
