package com.scrollkiller.data

import android.content.Context
import com.scrollkiller.guilt.GuiltLocale
import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import java.util.UUID

/**
 * Tiny wrapper over one SharedPreferences file for user settings, so the writer (the
 * Settings tab) and the reader (the overlay) can't drift on key/file names. No DI —
 * matches the app's manual-graph, ₹0-complexity constraint.
 */
object SettingsPrefs {

    private const val FILE = "scrollkiller_settings"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
    private const val KEY_GUILT_LOCALE = "guilt_locale"
    private const val KEY_INSTALL_ID = "install_id"

    /** Per-platform key prefixes. See [dailyLimit] and [graceUntilMs] for why they are per-platform. */
    private const val KEY_DAILY_LIMIT_PREFIX = "daily_limit_"
    private const val KEY_GRACE_UNTIL_PREFIX = "block_grace_until_"

    /** Day key of the last "the block is dead" early warning. See [blockWarnedOn]. */
    private const val KEY_BLOCK_WARNED_ON = "block_warned_on"

    /** Observed: the system refused our overlay window. See [overlayRuntimeDenied]. */
    private const val KEY_OVERLAY_RUNTIME_DENIED = "overlay_runtime_denied"

    /** Whether the floating counter bubble may show. Default on (matches Phase-1 behaviour). */
    fun isBubbleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_ENABLED, true)

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
    }

    /**
     * How many items a day on [platform] before the block escalates (D49).
     *
     * PER-PLATFORM, even though exactly one platform can block today. The limit is a property of
     * a surface the user has an opinion about — "100 reels" and "100 Shorts" are different
     * commitments — and a single shared budget would mean promoting YouTube later silently
     * halves everyone's Instagram allowance. Storing it per-platform now costs one string
     * concatenation and removes that migration entirely.
     *
     * Unset falls back to the platform spec's own default, so the value is right from the first
     * read without Settings ever having been opened. Clamped on the way out as well as in: a
     * value can arrive from a restored backup or a build with a different range, and an
     * out-of-range limit is the number that decides whether someone's screen gets covered.
     */
    fun dailyLimit(context: Context, platform: Platform): Int {
        val fallback = PlatformRegistry.specOrNull(platform)?.dailyLimit
            ?: BlockLimits.DEFAULT_DAILY_LIMIT
        val stored = prefs(context).getInt(KEY_DAILY_LIMIT_PREFIX + platform.id, fallback)
        return BlockLimits.clampLimit(stored)
    }

    fun setDailyLimit(context: Context, platform: Platform, value: Int) {
        prefs(context).edit()
            .putInt(KEY_DAILY_LIMIT_PREFIX + platform.id, BlockLimits.clampLimit(value))
            .apply()
    }

    /**
     * Wall-clock millis until which the block is suppressed on [platform] — the reprieve (D49).
     * 0 means none. Written by ONE caller since D74: completing a challenge. The free "5 more
     * minutes" tap wrote here too and was removed; the storage shape is unchanged because a
     * deadline was always the right representation, whoever earned it.
     *
     * PERSISTED, and that is the point: the app made a promise measured in minutes, and a
     * reprieve that a service restart or a crash silently revokes is a promise broken at the
     * worst possible moment. It also survives leaving Instagram, because a user who steps out to
     * reply to a message inside their own five minutes has not used them up.
     *
     * WALL clock, not [android.os.SystemClock.elapsedRealtime] — same split as D47's 7-day
     * history: persisted state that must survive a reboot is dated, and only a duration measured
     * within one process (D46's display gap) can use the monotonic clock. The accepted
     * consequence is that winding the device clock BACKWARDS extends the user's own reprieve.
     * That is a self-harm cheat, not a way to get trapped, and defending it would cost more than
     * it is worth.
     */
    fun graceUntilMs(context: Context, platform: Platform): Long =
        prefs(context).getLong(KEY_GRACE_UNTIL_PREFIX + platform.id, 0L)

    fun setGraceUntilMs(context: Context, platform: Platform, atMs: Long) {
        prefs(context).edit().putLong(KEY_GRACE_UNTIL_PREFIX + platform.id, atMs).apply()
    }

    /** Drop every platform's reprieve. Called from Settings → Clear data, with the counts. */
    fun clearGrace(context: Context) {
        val edit = prefs(context).edit()
        Platform.entries.forEach { edit.remove(KEY_GRACE_UNTIL_PREFIX + it.id) }
        edit.apply()
    }

    /**
     * The day the user was last warned that the block cannot fire, or null if never (D51).
     *
     * PERSISTED rather than held in memory, which is the whole reason it is here: the early
     * warning fires once per day on entering a reel surface, and an AccessibilityService is
     * restarted by the system far more often than a day rolls over. An in-memory flag would
     * re-warn on every restart, which is how a once-a-day alert becomes a notification the user
     * turns off — and turning it off would silence the very thing telling them the app is broken.
     */
    fun blockWarnedOn(context: Context): String? =
        prefs(context).getString(KEY_BLOCK_WARNED_ON, null)

    fun setBlockWarnedOn(context: Context, dayKey: String) {
        prefs(context).edit().putString(KEY_BLOCK_WARNED_ON, dayKey).apply()
    }

    /**
     * Did the system REFUSE our overlay window the last time we tried? (D52)
     *
     * The odd one out among these settings: every other value here is something the user chose,
     * and this is something we OBSERVED. It exists because `Settings.canDrawOverlays` cannot be
     * trusted on the MediaTek/Chinese ROMs our users run — it returns true while AppOps refuses
     * the op, so the only honest answer to "can we block?" is "did the window actually appear last
     * time we asked". That answer has to reach the UI process's banner from the service that
     * discovered it, and it has to survive a service restart, so it is persisted rather than held
     * in memory.
     *
     * Set on a refused or lost window, cleared on a verified attach — see
     * [com.scrollkiller.service.BlockScreenController].
     */
    fun overlayRuntimeDenied(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_RUNTIME_DENIED, false)

    fun setOverlayRuntimeDenied(context: Context, denied: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_RUNTIME_DENIED, denied).apply()
    }

    /**
     * Which guilt pack the user hears (D43). Defaults to [GuiltLocale.DEFAULT] — the India
     * Gen-Z pack — for an unset or unparseable value.
     */
    fun guiltLocale(context: Context): GuiltLocale =
        GuiltLocale.parse(prefs(context).getString(KEY_GUILT_LOCALE, null))

    fun setGuiltLocale(context: Context, locale: GuiltLocale) {
        prefs(context).edit().putString(KEY_GUILT_LOCALE, locale.tag).apply()
    }

    /**
     * A random, stable id for this install. Generated on first read and never changed.
     *
     * Its only job today is to seed the guilt pack's daily rotation ([com.scrollkiller.guilt
     * .GuiltDeck]) so two users don't see the same "fresh" set of lines on the same day. That
     * is why it is a locally-generated UUID and NOT a device identifier: nothing about the
     * hardware is wanted here, only that the value differs between people and survives restarts.
     * It never leaves the device today; when Phase 3 adds sync it is the natural client id for
     * the batch dedupe, which is a second reason to keep it install-scoped and meaningless.
     *
     * Uninstall/reinstall makes a new one, which is correct — it is a rotation seed, not an
     * identity.
     */
    fun installId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
