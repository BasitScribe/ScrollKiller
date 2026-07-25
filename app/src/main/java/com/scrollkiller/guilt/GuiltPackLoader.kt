package com.scrollkiller.guilt

import android.content.Context
import android.util.Log

/**
 * The app's single source of guilt lines: loads the bundled pack once, holds the per-surface
 * rotations, and hands out one line at a time.
 *
 * Replaces the `R.array.block_guilt_lines` string-array — a static list picked from with
 * `.random()`, which had no categories, no weighting, and re-showed the same line back-to-back
 * roughly one time in four.
 *
 * REMOTE-CONFIG SEAM (D33): the pack is bundled today, but nothing here assumes that. The
 * bundled asset is just the FIRST source; [install] accepts an already-parsed pack and swaps
 * it in atomically at runtime, so the Phase-3 backend can add a `GET /guilt_pack` fetcher that
 * calls `GuiltPackParser.parse(body)` → [install] and changes the app's voice with no store
 * release. That is why [install] is a public entry point with a revision check even though
 * only tests call it today: the alternative is discovering later that the loader was written
 * around a `context.assets` assumption and rewriting it.
 *
 * Object (not injected): matches the app's manual-graph, no-DI convention (see SettingsPrefs).
 * SESSION SCOPE: rotation state lives for the process lifetime, which for an always-bound
 * accessibility service is the right meaning of "session" — the user does not see a repeat
 * until the pack has cycled.
 *
 * Threading: called from the service main thread. [pack] is @Volatile so a future background
 * fetch calling [install] publishes safely.
 */
object GuiltPackLoader {

    private const val TAG = "ScrollKiller"
    private const val ASSET = "guilt_pack.json"

    @Volatile
    private var pack: GuiltPack? = null

    /** One independent rotation per surface, so the block and the bubble don't share a cycle. */
    private val rotations = HashMap<GuiltSurface, GuiltRotation>()

    /** Last id handed out on ANY surface — the cross-surface back-to-back guard. */
    private var lastShownId: String? = null

    /**
     * The active pack, loading the bundled asset on first use. Falls back to
     * [GuiltPack.FALLBACK] if the asset is missing or unparseable (a broken build) so callers
     * never have to handle "no lines".
     */
    fun pack(context: Context): GuiltPack {
        pack?.let { return it }
        val loaded = readAsset(context)?.let(GuiltPackParser::parse)
        if (loaded == null) {
            Log.w(TAG, "guilt pack asset missing or unparseable; using fallback")
        } else {
            Log.d(TAG, "guilt pack loaded: ${loaded.packId} rev${loaded.revision}, ${loaded.lines.size} lines")
        }
        val result = loaded ?: GuiltPack.FALLBACK
        pack = result
        return result
    }

    /**
     * One line for [surface]: weighted-random, not repeating within the session until the
     * surface's pool has cycled, and never immediately repeating a line another surface just
     * showed. Never returns null — falls back through [GuiltPack.linesFor]'s own fallbacks and
     * finally to a hard-coded string, because the block screen must never render blank.
     */
    fun line(context: Context, surface: GuiltSurface): String {
        val active = pack(context)
        val rotation = rotations.getOrPut(surface) { GuiltRotation() }
        val chosen = rotation.pick(active.linesFor(surface), avoid = lastShownId)
            ?: return GuiltPack.FALLBACK.lines.first().text
        lastShownId = chosen.id
        return chosen.text
    }

    /**
     * Swap in a new pack at runtime — the hot-swap entry point a future remote fetcher uses.
     *
     * Accepts only a HIGHER [GuiltPack.revision] than the active pack: a stale cached response
     * or an out-of-order retry must not downgrade the user's content. Rotation state is reset
     * because the new pack's ids may not overlap the old one's, which would leave stale
     * no-repeat entries suppressing nothing (or, worse, nothing at all).
     *
     * @return true if the pack was installed.
     */
    fun install(context: Context, candidate: GuiltPack): Boolean {
        if (candidate.schemaVersion != GuiltPack.SUPPORTED_SCHEMA_VERSION) return false
        if (candidate.lines.isEmpty()) return false
        if (candidate.revision <= pack(context).revision) return false

        pack = candidate
        rotations.values.forEach { it.reset() }
        lastShownId = null
        Log.d(TAG, "guilt pack installed: ${candidate.packId} rev${candidate.revision}")
        return true
    }

    /** Drop all cached state. Test hook; also correct after a "clear all data". */
    fun reset() {
        pack = null
        rotations.clear()
        lastShownId = null
    }

    private fun readAsset(context: Context): String? = try {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read $ASSET", e)
        null
    }
}
