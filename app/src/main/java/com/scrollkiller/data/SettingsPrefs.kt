package com.scrollkiller.data

import android.content.Context
import com.scrollkiller.challenge.ChallengeEscalation
import com.scrollkiller.challenge.ChallengeRegistry
import com.scrollkiller.guilt.GuiltLocale
import com.scrollkiller.service.BlockLimits
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import java.time.LocalDate
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

    /** The ONE daily scroll limit, across every blocking platform (D76). */
    private const val KEY_DAILY_LIMIT = "daily_limit"

    /**
     * VESTIGIAL: the pre-D76 per-platform limit keys (`daily_limit_instagram`, …). Still read
     * ONCE, by [migrateToUnifiedLimit], and never written again. Deliberately not deleted — a
     * migration that destroys its own input cannot be re-run or audited if the collapse rule
     * turns out to be wrong, and the cost of leaving a few stale ints in SharedPreferences is
     * nothing.
     */
    private const val KEY_DAILY_LIMIT_PREFIX = "daily_limit_"

    /**
     * LEGACY per-platform reprieve keys, read-only since the reprieve went global.
     *
     * Kept, not deleted, for the same reason [KEY_DAILY_LIMIT_PREFIX] is: a migration that destroys
     * its own input cannot be re-run or audited. [graceUntilMs] still reads these as a fallback so
     * nobody loses a reprieve they already paid for across the update that changed this.
     */
    private const val KEY_GRACE_UNTIL_PREFIX = "block_grace_until_"

    /**
     * THE reprieve deadline. ONE, across every blocking app — see [graceUntilMs].
     */
    private const val KEY_GRACE_UNTIL = "block_grace_until_global"

    /**
     * Per-CHALLENGE key prefix: how many reprieves that challenge has bought today (D83).
     *
     * Keyed by [com.scrollkiller.challenge.ChallengeSpec.id] and not by platform, because the
     * escalation follows the CHALLENGE — walking twenty steps out of Instagram and walking twenty
     * steps out of YouTube are the same act absorbed the same way, and D76 already made the limit
     * itself one global number.
     */
    private const val KEY_CHALLENGE_USES_PREFIX = "challenge_uses_"

    /** The day [KEY_CHALLENGE_USES_PREFIX]'s counters belong to. See [challengeUses]. */
    private const val KEY_CHALLENGE_USES_DAY = "challenge_uses_day"

    /** Day key of the last "the block is dead" early warning. See [blockWarnedOn]. */
    private const val KEY_BLOCK_WARNED_ON = "block_warned_on"

    /** Observed: the system refused our overlay window. See [overlayRuntimeDenied]. */
    private const val KEY_OVERLAY_RUNTIME_DENIED = "overlay_runtime_denied"

    /** Stand-in for a premium entitlement until monetisation. See [premiumOverride]. */
    private const val KEY_PREMIUM_OVERRIDE = "premium_override"

    /** Whether the floating counter bubble may show. Default on (matches Phase-1 behaviour). */
    fun isBubbleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_ENABLED, true)

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
    }

    /**
     * How many short videos a day — ACROSS EVERY BLOCKING PLATFORM COMBINED — before the block
     * escalates (D76, superseding D49/D19's per-platform limit).
     *
     * ONE budget, not one per app. The product is a doomscrolling day, not an Instagram day and a
     * YouTube day: 60 reels then 45 Shorts is 105 short videos, and under per-platform limits it
     * blocked at neither. D49 argued the opposite — that "100 reels" and "100 Shorts" are
     * different commitments — which is true of how people *talk* about apps and false about what
     * the limit is for. The old shape also had the failure mode that adding a platform silently
     * RAISED the effective ceiling, because each new app arrived with its own fresh allowance.
     *
     * Unset falls back to [migrateToUnifiedLimit], so an existing user's intent is carried across
     * rather than reset. Clamped on the way out as well as in: a value can arrive from a restored
     * backup or a build with a different range, and an out-of-range limit is the number that
     * decides whether someone's screen gets covered.
     */
    fun dailyLimit(context: Context): Int {
        val p = prefs(context)
        if (!p.contains(KEY_DAILY_LIMIT)) {
            val migrated = migrateToUnifiedLimit(p)
            p.edit().putInt(KEY_DAILY_LIMIT, migrated).apply()
            return migrated
        }
        return BlockLimits.clampLimit(p.getInt(KEY_DAILY_LIMIT, BlockLimits.DEFAULT_DAILY_LIMIT))
    }

    fun setDailyLimit(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DAILY_LIMIT, BlockLimits.clampLimit(value)).apply()
    }

    /**
     * Collapse the pre-D76 per-platform limits into one, ONCE, on the first read after upgrade.
     *
     * THE RULE IS "SUM WHAT THE USER ACTUALLY CHOSE", and each half of that matters:
     *  - SUM, because it preserves the ceiling they already had. Someone who set Instagram 60 and
     *    YouTube 45 could previously scroll 105 items before either app blocked; a unified 105
     *    leaves them exactly where they were. Taking the max (60) would silently tighten their
     *    limit on upgrade, which is the one direction a migration must never move on its own —
     *    the user would hit a block far earlier than they asked for and read it as a bug.
     *  - WHAT THEY ACTUALLY CHOSE, because summing untouched DEFAULTS inflates the result. A user
     *    who only ever moved the Instagram slider to 60 would otherwise get 60 + YouTube's
     *    default 100 = 160, a ceiling they never asked for. So only keys explicitly present in
     *    SharedPreferences are summed; a user who set nothing gets [BlockLimits.DEFAULT_DAILY_LIMIT].
     *
     * Only platforms that could actually block are considered — a SHADOW platform's stored limit
     * was never enforced, so folding it in would invent budget out of a control that did nothing.
     */
    private fun migrateToUnifiedLimit(p: android.content.SharedPreferences): Int {
        val chosen = PlatformRegistry.enabled
            .filter { it.blocksAtLimit }
            .mapNotNull { spec ->
                val key = KEY_DAILY_LIMIT_PREFIX + spec.platform.id
                if (p.contains(key)) p.getInt(key, spec.dailyLimit) else null
            }
        if (chosen.isEmpty()) return BlockLimits.DEFAULT_DAILY_LIMIT
        return BlockLimits.clampLimit(chosen.sum())
    }

    /**
     * Wall-clock millis until which the block is suppressed — the earned reprieve (D49). 0 means
     * no reprieve. Since D77 a completed challenge is the only writer; a deadline was the right
     * representation back when the free tap wrote here too, and still is.
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
     *
     * ## ⚑ ONE REPRIEVE, ACROSS EVERY BLOCKING APP — and it used to be per-platform (D88)
     * This was keyed by platform, which was right while every platform had its own limit. **D76
     * made the limit GLOBAL** — one budget for the whole doomscrolling day across Instagram and
     * YouTube together — and the reprieve was not moved with it. The result was a bug a user hit
     * within minutes of real use: walk twenty steps to get out of Instagram, switch to YouTube
     * Shorts, and the block is waiting there immediately, because the global count is still over
     * the global limit and YouTube's own grace key is zero.
     *
     * That is the user paying the price once and being charged again per app. It is incoherent
     * with D76 (one budget), with D77 (the challenge is the ONLY way past a block), and with D83
     * (escalation is already charged across the whole challenge set, precisely so the price cannot
     * be dodged by switching). The reprieve is what the challenge BUYS, and it has to be
     * denominated in the same currency as the thing it is spent against.
     *
     * So: one key, one deadline, and the exercise you did in Instagram gets you out of YouTube too.
     */
    fun graceUntilMs(context: Context): Long {
        val p = prefs(context)
        val global = p.getLong(KEY_GRACE_UNTIL, 0L)
        if (global != 0L) return global
        // Nothing global recorded yet. Fall back to the largest legacy per-platform deadline, so a
        // reprieve bought minutes before this change landed is honoured rather than silently
        // revoked — MAX rather than the current platform's, because the whole point of the change
        // is that where it was earned no longer matters. Self-heals: the next write is global, and
        // this branch stops being reached.
        return Platform.entries.maxOf { p.getLong(KEY_GRACE_UNTIL_PREFIX + it.id, 0L) }
    }

    fun setGraceUntilMs(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_GRACE_UNTIL, atMs).apply()
    }

    /** Drop the reprieve, legacy keys included. Called from Settings → Clear data, with the counts. */
    fun clearGrace(context: Context) {
        val edit = prefs(context).edit()
        edit.remove(KEY_GRACE_UNTIL)
        // The legacy keys must go too, or clearing data would leave a stale per-platform deadline
        // that graceUntilMs's fallback would happily resurrect on the next read.
        Platform.entries.forEach { edit.remove(KEY_GRACE_UNTIL_PREFIX + it.id) }
        edit.apply()
    }

    /**
     * How many reprieves [challengeId] has bought TODAY — the rung
     * [com.scrollkiller.challenge.ChallengeEscalation] escalates from (D83).
     *
     * ## The daily reset is a property of the READ, not a job that runs
     * Returning 0 whenever the stored day is not today means the reset cannot be missed. There is
     * nothing to schedule, nothing to run at midnight, and no way for a device that was switched
     * off across the rollover to wake up still escalated — which is exactly why [GuiltFiring]
     * baselines on a day change rather than being told about one, and the same shape as it.
     *
     * ## Why the day is reset DAILY at all
     * The whole app is denominated in days: the limit is daily (D76), the counts are daily, the
     * guilt cadence baselines per day, streaks are per day (D81). Escalation carrying across
     * midnight would be the only mechanic here that does not, and a fresh morning that opens at
     * "hold it for two minutes" is charging the user for yesterday — which reads as the app being
     * broken rather than being strict, and D9's anti-uninstall principle applies to the mechanic
     * (D50).
     *
     * Device-local `LocalDate`, the same interim boundary the counts and the guilt cadence use
     * until the server owns the day in Phase 3 (D14). It moves when they move, not before.
     *
     * ## Why the whole map, rather than one id at a time
     * Escalation charges a challenge for its OWN reprieves *and* a share of every other
     * challenge's ([ChallengeEscalation.effectiveUses]), so the caller needs both numbers. Read
     * separately they could straddle midnight — own returning 0 from the new day while the total
     * still reflected the old one, which would price a fresh challenge as though it had been used.
     * One snapshot answers both questions against one day key, so that skew is not expressible.
     *
     * Empty map on a stale day; callers default to 0.
     *
     * @param dayKey defaulted so callers never compute a date; injectable so the rollover is
     *   reachable in a test without winding a device clock.
     */
    fun challengeUsesToday(context: Context, dayKey: String = today()): Map<String, Int> {
        val p = prefs(context)
        if (p.getString(KEY_CHALLENGE_USES_DAY, null) != dayKey) return emptyMap()
        return ChallengeRegistry.enabled.associate {
            it.id to p.getInt(KEY_CHALLENGE_USES_PREFIX + it.id, 0)
        }
    }

    /**
     * [challengeId] just bought a reprieve — escalate it for next time.
     *
     * Called ONLY from the completion path. Not on start and not on cancel: a user who opens the
     * chooser, reads the rows and backs out has not bought anything, and charging them for looking
     * is the same mistake as banking partial progress across blocks (D50g).
     *
     * The stale-day branch SWEEPS every other challenge's counter rather than only rolling the day
     * stamp. Leaving them would be a real bug and not merely untidy: [challengeUses] gates on ONE
     * shared day key, so writing today's stamp while yesterday's counters sat in the file would
     * make every challenge the user did *not* complete today read back at yesterday's rung.
     */
    fun noteChallengeCompleted(
        context: Context,
        challengeId: String,
        dayKey: String = today(),
    ) {
        val p = prefs(context)
        val stale = p.getString(KEY_CHALLENGE_USES_DAY, null) != dayKey
        val current = if (stale) 0 else p.getInt(KEY_CHALLENGE_USES_PREFIX + challengeId, 0)
        val edit = p.edit()
        if (stale) {
            ChallengeRegistry.enabled.forEach { edit.remove(KEY_CHALLENGE_USES_PREFIX + it.id) }
            edit.putString(KEY_CHALLENGE_USES_DAY, dayKey)
        }
        edit.putInt(KEY_CHALLENGE_USES_PREFIX + challengeId, current + 1).apply()
    }

    /**
     * Put every challenge back to its base target. Called from Settings → Clear data, beside
     * [clearGrace] — someone wiping their history should not find the app still remembering how
     * hard it had decided to be.
     */
    fun clearChallengeEscalation(context: Context) {
        val edit = prefs(context).edit()
        ChallengeRegistry.enabled.forEach { edit.remove(KEY_CHALLENGE_USES_PREFIX + it.id) }
        edit.remove(KEY_CHALLENGE_USES_DAY).apply()
    }

    /** Today, device-local, in the ISO form every day key in the app uses. */
    private fun today(): String = LocalDate.now().toString()

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
     * Stand-in for a premium entitlement (D85). Defaults FALSE — nobody can buy anything yet, so
     * the shipped behaviour is that everybody is on the free pack.
     *
     * Exists so the premium GATE is real and testable from the day the premium CONTENT lands,
     * rather than being a field nothing reads until monetisation. Read only through
     * [com.scrollkiller.guilt.Entitlements], which is the seam a Play Billing check replaces —
     * **and when it does, this must not survive as a way to unlock content in a release build.**
     */
    fun premiumOverride(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PREMIUM_OVERRIDE, false)

    fun setPremiumOverride(context: Context, premium: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREMIUM_OVERRIDE, premium).apply()
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
