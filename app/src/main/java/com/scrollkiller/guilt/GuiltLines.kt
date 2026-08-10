package com.scrollkiller.guilt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.scrollkiller.ScrollKillerApp
import com.scrollkiller.data.SettingsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * What every surface calls to ask the app what it is currently saying.
 *
 * A thin Android façade over one process-wide [GuiltSelector]: it supplies the things the
 * selector deliberately does not know about — the Context-backed pack, the user's chosen
 * audience, the two clocks, and the persisted 7-day history — and renders the chosen line's
 * tokens. All the behaviour (escalation, cadence, the 7-day window, exhaustion) is in the
 * selector, which is pure and unit-tested off-device.
 *
 * ## Why an object, and why one instance
 * The pin only means anything if Home and the overlay share it. They live in a Compose
 * ViewModel and an AccessibilityService respectively — same process, same main thread, no
 * shared owner — so a process-wide object is what "one source of truth" actually reduces to
 * here. Matches the app's no-DI, manual-graph convention (see [SettingsPrefs], [GuiltPackLoader]).
 *
 * Threading: main thread only. See [GuiltSelector]. The history load is the one exception and it
 * posts back to Main before touching anything.
 */
object GuiltLines {

    private const val TAG = "ScrollKiller"

    private var selector: GuiltSelector? = null

    /**
     * The 7-day record. Owned HERE and not by the selector: a locale change resets the selector,
     * but a line the user has already read is still a line they have already read.
     */
    private val history = GuiltHistory()

    /** Whether the database has been wired to [history] yet. See [prime]. */
    private var primed = false

    /**
     * The line the CURRENT block screen is showing, held raw (tokens unsubstituted) for as long
     * as that block is up. Null when no block is showing. See [blockLine].
     */
    private var blockEpisode: GuiltLine? = null

    /**
     * The line for a daily total of [count], with tokens rendered, or NULL below
     * [GuiltThresholds.MILD_AT] — in which case the surface renders nothing at all, which is the
     * designed silence and not an error.
     *
     * The SAME text for every caller at a given moment. Safe and cheap to call on every count
     * emission: it redraws only when the tier, the day, the pack or the locale changes.
     */
    fun current(context: Context, count: Int): String? =
        selector(context)
            .current(GuiltPackLoader.pack(context), locale(context), count, now(context))
            ?.let { GuiltText.render(it.text, count) }

    /**
     * Advance the firing cadence for [count]; returns a NEWLY FIRED line, or null when none is
     * due (the common case — this is called on every count emission).
     *
     * The overlay calls this and nobody else: a fire is a thing you SHOW, and the bubble's nudge
     * is what showing it means. Home reads [current], which returns whatever this last pinned.
     * See [GuiltSelector.fire].
     */
    fun fire(context: Context, count: Int): String? =
        selector(context)
            .fire(GuiltPackLoader.pack(context), locale(context), count, now(context))
            ?.let { GuiltText.render(it.text, count) }

    /**
     * The full-screen block's line for a daily total of [count]. Its own draw, not the pinned
     * ambient line (the block is its own moment), but tier-filtered like everything else and
     * sharing the 7-day history so it cannot echo anything shown this week.
     *
     * Never null: the block screen must not render blank text, so a count below the tier
     * threshold — or a pool that somehow yields nothing — falls back to the built-in pack.
     *
     * ## Drawn once per EPISODE, rendered on every call (D49)
     * The overlay calls this from its render path, which runs on every count emission. Drawing
     * there would hand out a new line each time AND stamp each one into the 7-day history — an
     * unbroken block would eat a tier's whole pool in a handful of emissions and land the app in
     * D47's exhaustion fallback for the rest of the week, for lines nobody ever finished reading.
     *
     * So the DRAW is pinned for the life of one block ([endBlockEpisode] releases it) while the
     * RENDER is not: the raw text is re-substituted every call, so `{count}` and `{minutes}` stay
     * live even though the sentence does not change. Same split as the ambient pin, for the same
     * reason — a line must never contradict the number beside it (D47).
     */
    fun blockLine(context: Context, count: Int): String {
        val line = blockEpisode
            ?: selector(context)
                .draw(GuiltPackLoader.pack(context), locale(context), GuiltSurface.BLOCK, count, now(context))
            ?: GuiltPack.FALLBACK.lines.first()
        blockEpisode = line
        return GuiltText.render(line.text, count)
    }

    /**
     * The block came down (exit, reprieve, or leaving the app), so the next one draws fresh.
     *
     * Always called on the way out rather than on the way in: releasing here means a block that
     * is raised, dismissed and raised again says something new the second time, which is the
     * whole point of a pack. Idempotent — dismissing a block that never showed is a no-op.
     */
    fun endBlockEpisode() {
        blockEpisode = null
    }

    /**
     * The user opened the app: the next [current] draws a fresh line even at the same tier.
     *
     * Called from `MainActivity.onCreate` rather than on every resume — an app open is a new
     * look at the number, whereas a resume can be a notification shade being dismissed, and
     * swapping the sentence someone is mid-way through reading is worse than repeating it.
     */
    fun onAppOpen() {
        selector?.invalidate()
    }

    /**
     * The user picked a different pack in Settings. Drops selection state: the no-repeat
     * rotations describe lines in the old audience's pool, and the pinned line is in a language
     * the user just said they don't want.
     *
     * The 7-day history SURVIVES, deliberately. Switching packs is not a reason to re-show a
     * line someone read yesterday, and the ids are stable across packs anyway.
     */
    fun onLocaleChanged() {
        selector?.reset()
        endBlockEpisode()   // the held block line is in the language the user just declined
    }

    /**
     * Settings → Clear data. Wipes the 7-day history along with the counts.
     *
     * It is the user's data about them, so "delete everything on this device" should mean it —
     * and it keeps the on-device test runs repeatable, since otherwise a re-run of a handoff
     * check would hit exclusions left by the previous one.
     */
    fun onDataCleared(context: Context) {
        history.clear()
        selector?.reset()
        endBlockEpisode()
        app(context)?.let { app ->
            app.appScope.launch { app.database.guiltShownDao().deleteAll() }
        }
    }

    /**
     * Wire [history] to the database: prune what has fallen out of the window, load the rest, and
     * start writing new showings through. Idempotent; the first call that can reach the
     * Application does the work.
     *
     * Asynchronous by necessity — the selector reads [history] synchronously from a render path,
     * so the load cannot be waited on. Until it lands the window is briefly not enforced; see
     * [GuiltHistory] for why that hole is acceptable and bounded.
     */
    private fun prime(context: Context) {
        if (primed) return
        val app = app(context) ?: return   // not the real Application (a unit/robolectric context)
        primed = true
        val dao = app.database.guiltShownDao()
        history.onRecord = { id, wallMs ->
            app.appScope.launch { dao.upsert(com.scrollkiller.data.db.GuiltShownEntity(id, wallMs)) }
        }
        app.appScope.launch {
            dao.deleteOlderThan(System.currentTimeMillis() - NO_REPEAT_MS)
            val rows = dao.getAll()
            withContext(Dispatchers.Main) {
                history.seed(rows.associate { it.lineId to it.shownAt })
                Log.d(TAG, "guilt history primed: ${rows.size} lines seen in the last ${GuiltPoolMath.NO_REPEAT_DAYS}d")
            }
        }
    }

    /**
     * A tier ran out of unseen lines and fell back to least-recently-shown.
     *
     * This is the signal that the pack is under-supplied for how hard this user scrolls, and it
     * must never be silent (D47) — the fallback is designed to keep the app working, not to hide
     * that it is repeating itself. The warning quotes [GuiltPoolMath] so it names the actual
     * target rather than just complaining, using today's count as the estimate of this user's
     * daily rate (which at the moment of exhaustion is the best estimate available, and is
     * conservative — the day is not over).
     *
     * It names BOTH numbers, because after D48 they answer different questions. `needs` is what
     * this user's rate demands and for tier 4 it is unbounded; `target` is what we have committed
     * to author. When the target is met and this still fires, the line is not "write more" — it
     * is "this user is past what we chose to cover", which is exactly the distribution we want
     * measured before spending another hundred lines chasing it.
     */
    private fun warnExhausted(it: GuiltSelector.PoolExhausted) {
        val needed = GuiltPoolMath.requiredPool(it.tier, scrollsPerDay = it.countToday)
        val target = GuiltPoolMath.targetPool(it.tier)
        Log.w(
            TAG,
            "guilt pool exhausted: ${it.surface}/${it.tier} has ${it.poolSize} lines and every " +
                "one was shown in the last ${GuiltPoolMath.NO_REPEAT_DAYS}d. At ${it.countToday} " +
                "scrolls/day this tier needs ~$needed lines to sustain no-repeat (committed " +
                "target: $target). Falling back to least-recently-shown — EXPAND THIS TIER in " +
                "guilt_pack.json.",
        )
    }

    /**
     * Today plus both clocks. See [GuiltNow] for why there are two.
     *
     * TODO(Phase 3): [LocalDate.now] is the same interim device-local boundary
     * [com.scrollkiller.data.CountRepository] uses, and it must move to the server timezone
     * truth with it (invariant #2).
     */
    private fun now(context: Context): GuiltNow {
        prime(context)
        return GuiltNow(
            dayKey = LocalDate.now().toString(),
            monotonicMs = SystemClock.elapsedRealtime(),
            wallMs = System.currentTimeMillis(),
            // Asked per draw rather than held, so an entitlement that lapses stops mattering on
            // the very next line rather than at the next process start (D85).
            isPremium = Entitlements.isPremium(context),
        )
    }

    private fun locale(context: Context): GuiltLocale = SettingsPrefs.guiltLocale(context)

    private fun app(context: Context): ScrollKillerApp? =
        context.applicationContext as? ScrollKillerApp

    /** The one selector, bound to this install's stable id (see [GuiltDeck]). */
    private fun selector(context: Context): GuiltSelector =
        selector ?: GuiltSelector(
            installId = SettingsPrefs.installId(context),
            history = history,
            onPoolExhausted = ::warnExhausted,
        ).also { selector = it }

    private val NO_REPEAT_MS = GuiltPoolMath.NO_REPEAT_DAYS * 24L * 60L * 60L * 1_000L
}
