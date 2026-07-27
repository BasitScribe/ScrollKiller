package com.scrollkiller.guilt

import kotlin.math.ceil
import kotlin.random.Random

/**
 * The two clocks and the day, passed together because the selector genuinely needs all three and
 * because bundling them is the only way the difference between the clocks stays visible at every
 * call site.
 *
 * @param dayKey today, device-local ISO date. Keys the pin and the daily ordering seed.
 * @param monotonicMs `SystemClock.elapsedRealtime`. Drives the D46 display gap, which is a
 *   DURATION: wall time would let a clock change jam it open or — worse, jumping backwards —
 *   shut, permanently.
 * @param wallMs `System.currentTimeMillis`. Drives the D47 7-day history, which is PERSISTED and
 *   so must stay comparable across reboots; `elapsedRealtime` resets to zero on every one.
 */
data class GuiltNow(
    val dayKey: String,
    val monotonicMs: Long,
    val wallMs: Long,
)

/**
 * THE picker. Every guilt line the app shows, on every surface, comes out of one instance of
 * this class ([GuiltLines.selector]).
 *
 * ## The one-line invariant
 * Home's header, the bubble's threshold nudge and the bubble's expanded panel must not disagree.
 * They render at different moments, from different processes' worth of state (a Compose
 * ViewModel and an AccessibilityService overlay), so "they each call the picker" is not enough —
 * three independent draws would give three different lines and the app would visibly say three
 * things at once.
 *
 * So [current] PINS its answer. It draws once per (tier, day, pack, locale) and returns that same
 * line to everyone until one of those changes. A tier change is exactly the moment a new line is
 * wanted, so the pin needs no invalidation logic beyond the key itself; [invalidate] exists for
 * the one case the key can't express, which is "the user re-opened the app and deserves
 * something new".
 *
 * [draw] is the unpinned path, for a surface that is its own moment rather than a reflection of
 * the current one — the full-screen block. It shares the decks, the rotations and the
 * cross-surface no-repeat guard, so it still cannot echo the line the bubble is showing.
 *
 * ## Layering
 *  - [GuiltTier] decides HOW HARD (from the count),
 *  - [GuiltCadence] + [GuiltFiring] decide WHEN and HOW OFTEN (also from the count),
 *  - [GuiltPack.pool] decides WHAT IS ELIGIBLE (audience → tone → intensity),
 *  - [GuiltHistory] decides WHAT IS BURNT (anything shown in the last 7 days — the hard rule),
 *  - [GuiltDeck] decides the ORDER within what's left (deterministic per-install daily seed),
 *  - [GuiltRotation] decides WHICH ONE NOW (weighted, no repeat until the pool cycles).
 * Each is a pure function of its inputs; this class is the only thing holding state. Note that
 * the cadence layer feeds the SAME draw path as everything else (D46): firing more often at a
 * high count does not change which pool is drawn from, so rapid-fire lines are still tier-correct.
 *
 * Pure Kotlin and [Random]-injectable so all of it — pinning, escalation, the 7-day window,
 * exhaustion — is unit-testable off-device without a Context or a clock.
 *
 * NOT thread-safe. Every caller is on the app's main thread: the ViewModel collects on
 * `viewModelScope` (Main.immediate) and the overlay collects on Dispatchers.Main, and the
 * accessibility service shares that looper because it is the same process.
 *
 * @param installId stable per-install id, mixed into the daily seed so two users don't order
 *   their fallbacks in lockstep. See [GuiltDeck].
 * @param history the persisted 7-day record of what this user has already seen. Injected rather
 *   than owned because it must survive things the selector does not — a locale change resets the
 *   selector, but a line the user has read is still a line they have read.
 * @param onPoolExhausted called when a tier's whole pool is inside the 7-day window and the
 *   least-recently-shown fallback has to run. A callback rather than a log call so this class
 *   stays Android-free and so a test can assert it fired; [GuiltLines] turns it into a warning
 *   that quotes [GuiltPoolMath]. This is the signal that a tier needs more content — it must
 *   never be silent (D47).
 */
class GuiltSelector(
    private val installId: String,
    private val history: GuiltHistory = GuiltHistory(),
    private val random: Random = Random.Default,
    private val onPoolExhausted: ((PoolExhausted) -> Unit)? = null,
) {

    /** Everything the caller needs to log a useful "expand this tier" warning. */
    data class PoolExhausted(
        val surface: GuiltSurface,
        val tier: GuiltTier,
        val poolSize: Int,
        val countToday: Int,
    )

    /** The line every AMBIENT surface is currently showing, plus what it was drawn for. */
    data class Pinned(
        val tier: GuiltTier,
        val dayKey: String,
        val packIdentity: String,
        val locale: GuiltLocale,
        val line: GuiltLine,
    )

    /**
     * One rotation per (surface, tier). Keyed by tier as well as surface because the tiers hold
     * disjoint lines: a shared rotation would carry MILD ids in its "already shown" set while
     * drawing from the EXTREME deck, so its cycle bookkeeping would describe a pool it is no
     * longer drawing from.
     */
    private val rotations = HashMap<Pair<GuiltSurface, GuiltTier>, GuiltRotation>()

    /** Last id handed out on ANY surface — the cross-surface back-to-back guard. */
    private var lastShownId: String? = null

    private var pinned: Pinned? = null

    /** Set by [invalidate]; makes the next [current] redraw even though its key is unchanged. */
    private var pinStale = false

    /** Pack the state above was built against, so a hot-swap can throw it away ([syncTo]). */
    private var packIdentity: String? = null

    /** WHEN a new line is due. See [fire]. */
    private val firing = GuiltFiring()

    /**
     * The line the app is saying right now for a daily total of [count], or NULL when the count
     * is below [GuiltThresholds.MILD_AT] and the app should stay quiet.
     *
     * Read-only in spirit: normally it just returns whatever [fire] last pinned, so Home shows
     * the latest FIRED line. It still draws lazily when there is no usable pin — a tier the pin
     * doesn't cover, a new day, a swapped pack — because the overlay may never run at all (bubble
     * switched off, or the overlay permission never granted) and Home must still have something
     * tier-appropriate to say.
     *
     * Idempotent while the tier, day, pack and locale hold — call it on every count emission;
     * only a genuine change costs a draw.
     */
    fun current(
        pack: GuiltPack,
        locale: GuiltLocale,
        count: Int,
        now: GuiltNow,
    ): GuiltLine? {
        syncTo(pack)
        val tier = GuiltTier.forCount(count)
        if (tier == null) {
            // Dropped below the threshold (a data clear, a day rollover). Unpin, so climbing
            // back past 50 later draws a new line instead of resurrecting the old one.
            pinned = null
            return null
        }

        val held = pinned
        if (!pinStale &&
            held != null &&
            held.tier == tier &&
            held.dayKey == now.dayKey &&
            held.packIdentity == pack.identity &&
            held.locale == locale
        ) {
            return held.line
        }

        val line = draw(pack, locale, GuiltSurface.AMBIENT, tier, count, now) ?: return null
        pinned = Pinned(tier, now.dayKey, pack.identity, locale, line)
        pinStale = false
        return line
    }

    /**
     * Advance the firing cadence for [count] and, if a line is now due, draw it and make it THE
     * line — pinned, so every surface picks it up on its next read.
     *
     * Returns the newly-fired line, or null when nothing is due (which is the overwhelmingly
     * common case — this is called on every count emission).
     *
     * Only the overlay calls this, because the overlay is the only surface that can *show* a
     * fire — the bubble's nudge is what "firing" means visually. Home deliberately does not,
     * even though it also observes the count: two callers would race for the same fire, and
     * whichever won, the bubble could silently lose a nudge. Home reads [current] instead, which
     * returns whatever this last pinned. See D46.
     *
     */
    fun fire(
        pack: GuiltPack,
        locale: GuiltLocale,
        count: Int,
        now: GuiltNow,
    ): GuiltLine? {
        syncTo(pack)
        val tier = GuiltTier.forCount(count)
        if (!firing.onCount(count, tier, now.dayKey, now.monotonicMs)) return null
        // onCount only returns true for a non-null tier, but the compiler can't know that and a
        // render path is the wrong place to assert it.
        tier ?: return null

        val line = draw(pack, locale, GuiltSurface.AMBIENT, tier, count, now) ?: return null
        pinned = Pinned(tier, now.dayKey, pack.identity, locale, line)
        pinStale = false
        return line
    }

    /**
     * Drop the pin so the next [current] draws a fresh line even at the same tier. Called on app
     * open: re-opening ScrollKiller after an hour and being met with the sentence you already
     * read is the single most "this is a static string" thing the feature could do.
     *
     * Deliberately does NOT clear the rotations — the no-repeat history is what makes the fresh
     * line actually fresh.
     */
    fun invalidate() {
        pinStale = true
    }

    /**
     * An UNPINNED draw for [surface] at the tier [count] falls in, or null below the threshold.
     *
     * For the block screen, which is its own moment rather than a reflection of the ambient one.
     * Still tier-filtered — hitting the daily limit is at least a STRONG-tier event, and the
     * block must not be the one surface where escalation doesn't apply.
     */
    fun draw(
        pack: GuiltPack,
        locale: GuiltLocale,
        surface: GuiltSurface,
        count: Int,
        now: GuiltNow,
    ): GuiltLine? {
        syncTo(pack)
        val tier = GuiltTier.forCount(count) ?: return null
        return draw(pack, locale, surface, tier, count, now)
    }

    /** Forget everything: rotations, pin, cadence, pack identity. New pack, new locale, or a test.
     *  Does NOT touch [history] — that is the user's record, not this object's working state. */
    fun reset() {
        rotations.clear()
        lastShownId = null
        pinned = null
        pinStale = false
        packIdentity = null
        firing.reset()
    }

    /**
     * The one draw path. Every line the app has ever shown came out of here, which is why the
     * 7-day rule and the history write both live here rather than at the four call sites.
     *
     * Order matters and is the D47 reconciliation with D42:
     *  1. [GuiltPack.pool] — audience, tone, intensity. What this tier could ever say.
     *  2. The 7-DAY EXCLUSION. The hard rule: anything the user has seen this week is out.
     *  3. Exhaustion fallback, if that emptied the pool — see [stalest].
     *  4. [GuiltDeck.order] — a deterministic per-install daily ordering of what survived. Only
     *     the fallback actually depends on it (the weighted draw below ignores order), which is
     *     exactly why the deck no longer NARROWS: narrowing on top of the exclusion is how a
     *     healthy pack reaches step 3.
     *  5. [GuiltRotation] — weighted pick, no repeat until the remaining pool cycles.
     */
    private fun draw(
        pack: GuiltPack,
        locale: GuiltLocale,
        surface: GuiltSurface,
        tier: GuiltTier,
        count: Int,
        now: GuiltNow,
    ): GuiltLine? {
        val pool = pack.pool(surface, tier, locale)
        if (pool.isEmpty()) return null

        val burnt = history.shownSince(now.wallMs - NO_REPEAT_MS)
        val fresh = pool.filterNot { it.id in burnt }
        val eligible = if (fresh.isNotEmpty()) {
            fresh
        } else {
            onPoolExhausted?.invoke(PoolExhausted(surface, tier, pool.size, count))
            stalest(pool)
        }

        val ordered = GuiltDeck.order(eligible, now.dayKey, installId)
        val rotation = rotations.getOrPut(surface to tier) { GuiltRotation(random) }
        val chosen = rotation.pick(ordered, avoid = lastShownId) ?: return null
        lastShownId = chosen.id
        history.record(chosen.id, now.wallMs)
        return chosen
    }

    /**
     * The least-recently-shown slice of [pool], for when every line is inside the window.
     *
     * This is the graceful-degradation path, and it degrades on the right axis: showing the line
     * you saw longest ago is the least-bad repeat available. It narrows to a SLICE rather than
     * returning the sorted pool because the weighted draw that follows ignores order — handing it
     * everything would let it pick the line shown a minute ago. The slice keeps enough lines for
     * the draw to still feel random.
     *
     * Ties (everything shown "today", which is the normal shape of exhaustion at a high cadence)
     * are broken by [GuiltDeck.order] downstream, so the degradation is stable within a day and
     * differs between users.
     *
     * Reaching here at all means a tier is under-supplied for this user's rate. It is never
     * silent — [onPoolExhausted] fires first.
     */
    private fun stalest(pool: List<GuiltLine>): List<GuiltLine> {
        val take = maxOf(1, ceil(pool.size * STALE_FALLBACK_FRACTION).toInt())
        return pool.sortedBy { history.lastShownAt(it.id) }.take(take)
    }

    /**
     * Throw away derived state if the pack has been hot-swapped underneath us ([GuiltPackLoader
     * .install]). The new pack's ids may not overlap the old one's, which would leave the
     * rotations suppressing lines that no longer exist and the pin holding text that is no longer
     * in the pack.
     *
     * Detected by comparing [GuiltPack.identity] rather than by the loader calling us, so the
     * loader stays a pack source with no knowledge of who is selecting from it.
     */
    private fun syncTo(pack: GuiltPack) {
        if (packIdentity == pack.identity) return
        reset()
        packIdentity = pack.identity
    }

    private companion object {

        /** The rolling no-repeat window, in millis. [GuiltPoolMath.NO_REPEAT_DAYS] is the source. */
        val NO_REPEAT_MS = GuiltPoolMath.NO_REPEAT_DAYS * 24L * 60L * 60L * 1_000L

        /**
         * How much of an exhausted pool the fallback reaches for. A quarter keeps the draw from
         * collapsing onto a single line while still meaning "the stale end" — with the shipped
         * 18-line tiers that is 5 lines, enough for the weighted pick to stay unpredictable.
         */
        const val STALE_FALLBACK_FRACTION = 0.25
    }
}
