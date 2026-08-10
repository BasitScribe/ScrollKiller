package com.scrollkiller.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrollkiller.ScrollKillerApp
import com.scrollkiller.data.SettingsPrefs
import com.scrollkiller.data.TodaySummary
import com.scrollkiller.guilt.GuiltLines
import com.scrollkiller.guilt.GuiltLocale
import com.scrollkiller.permission.PermissionHealth
import com.scrollkiller.permission.PermissionHealthReader
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import com.scrollkiller.service.PlatformSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One platform's line on the dashboard: its label, unit, and today's count.
 *
 * [isBeta] mirrors [com.scrollkiller.service.PlatformSpec.isBeta] — a platform whose count
 * we don't yet trust (see D32). The UI badges it so the number isn't read as gospel, and
 * such a platform can never drive a limit or a block.
 */
data class PlatformCount(
    val platform: Platform,
    val displayName: String,
    val unitNoun: String,
    val count: Int,
    val isBeta: Boolean = false,
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

    /**
     * Today's counts, total and split, from ONE collector.
     *
     * [total] and [breakdown] are both projections of this rather than two independent Flows:
     * they are the same numbers, and the repository serves them from one merged source (D39),
     * so subscribing twice would only buy a second chance for the tabs to disagree.
     */
    private val summary: StateFlow<TodaySummary> = repository.observeTodaySummary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodaySummary.EMPTY)

    /** Live grand total across all platforms today. Same number the bubble shows (D35). */
    val total: StateFlow<Int> = summary
        .map { it.total }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Live per-platform breakdown. Every enabled platform appears (filled to 0 from the
     * registry when it has no row yet), sorted by count desc then name — so the Today and
     * Apps tabs show a stable, complete list.
     */
    val breakdown: StateFlow<List<PlatformCount>> = summary
        .map { today ->
            PlatformRegistry.enabled
                .map { spec ->
                    PlatformCount(
                        platform = spec.platform,
                        displayName = spec.displayName,
                        unitNoun = spec.unitNoun,
                        count = today.countFor(spec.platform),
                        isBeta = spec.isBeta,
                    )
                }
                .sortedWith(compareByDescending<PlatformCount> { it.count }.thenBy { it.displayName })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which guilt pack the user hears (D43). One option today; the plumbing takes many. */
    private val _guiltLocale = MutableStateFlow(SettingsPrefs.guiltLocale(app))

    val guiltLocale: StateFlow<GuiltLocale> = _guiltLocale

    /**
     * The line the app is currently saying, or NULL below the first tier — in which case Home
     * shows nothing at all, which is the designed silence and not a loading state (D41).
     *
     * The SAME line the bubble's nudge and expanded panel are showing at this moment: all three
     * read [GuiltLines.current], which pins one draw per (tier, day, pack, locale). Home does
     * not get its own rotation.
     *
     * Combined with [_guiltLocale] rather than mapped from [summary] alone so that changing the
     * pack in Settings re-emits — the count has not changed, so nothing else would wake this up.
     */
    val guiltLine: StateFlow<String?> = combine(summary, _guiltLocale) { today, _ ->
        GuiltLines.current(app, today.total)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setGuiltLocale(locale: GuiltLocale) {
        SettingsPrefs.setGuiltLocale(getApplication(), locale)
        // Decks, no-repeat history and the pinned line all describe the OLD pool — drop them
        // before the re-emission below asks for a line in the new one.
        GuiltLines.onLocaleChanged()
        _guiltLocale.value = locale
    }

    private val _health = MutableStateFlow(PermissionHealthReader.of(app))

    /**
     * Whether the app can actually do its job right now (D51). The SAME model the block path
     * reads, so Home cannot claim to be healthy while the overlay is silently being refused.
     *
     * Not a Flow of anything observable — Android gives no callback for "the user revoked a
     * permission" — so it is refreshed explicitly by [refreshHealth] on every resume.
     */
    val health: StateFlow<PermissionHealth> = _health

    /**
     * Re-read the permission state. Called from `MainActivity.onResume`, which is what makes
     * returning from the system settings screen flip the banner to healthy with no restart — the
     * same mechanism the accessibility onboarding step has always used.
     */
    fun refreshHealth() {
        _health.value = PermissionHealthReader.of(getApplication())
    }

    private val _bubbleEnabled = MutableStateFlow(SettingsPrefs.isBubbleEnabled(app))

    /** Whether the floating bubble is allowed (Settings toggle; read by the overlay). */
    val bubbleEnabled: StateFlow<Boolean> = _bubbleEnabled

    fun setBubbleEnabled(enabled: Boolean) {
        SettingsPrefs.setBubbleEnabled(getApplication(), enabled)
        _bubbleEnabled.value = enabled
    }

    /**
     * The platforms whose count may actually raise the block screen, and therefore the ones a
     * daily limit means anything for (D49). Instagram alone today.
     *
     * Derived from [PlatformSpec.blocksAtLimit] rather than listed, so promoting a platform to
     * STABLE and switching its block on makes its slider appear with no UI change — and so a
     * BETA platform can never get a limit control implying it will enforce one.
     */
    val blockingPlatforms: List<PlatformSpec> =
        PlatformRegistry.enabled.filter { it.blocksAtLimit }

    private val _dailyLimit = MutableStateFlow(SettingsPrefs.dailyLimit(app))

    /**
     * The ONE daily limit, across every blocking platform combined (D76). Was a
     * `Map<Platform, Int>` with a slider each; collapsing it to a single value is the whole of
     * that change on this layer, because the overlay was already the only thing enforcing it.
     */
    val dailyLimit: StateFlow<Int> = _dailyLimit

    /**
     * Move the limit. Written through immediately (no Apply button) because the overlay re-reads
     * the pref on every count emission, so the new limit is live on the very next reel — a limit
     * that took effect "next time you open Instagram" would look broken.
     */
    fun setDailyLimit(value: Int) {
        SettingsPrefs.setDailyLimit(getApplication(), value)
        _dailyLimit.value = SettingsPrefs.dailyLimit(getApplication())
    }

    /**
     * Settings → Clear data: wipe aggregates, raw events, and the guilt-line history.
     *
     * The history goes too because it is the user's data about them and "delete every stored
     * count on this device" should not quietly keep a week of what they were shown (D47).
     */
    fun clearData() {
        GuiltLines.onDataCleared(getApplication())
        // Any running reprieve goes with the counts: it is a fact about how much the user
        // scrolled today, and today is being deleted (D49).
        SettingsPrefs.clearGrace(getApplication())
        // So does how hard the app had decided to be. Escalation is derived from today's
        // reprieves, so a wipe that left it standing would ask 80 steps of someone whose history
        // now says they have not scrolled at all (D83).
        SettingsPrefs.clearChallengeEscalation(getApplication())
        viewModelScope.launch { repository.clearAll() }
    }
}
