package com.scrollkiller.guilt

import android.content.Context
import android.util.Log

/**
 * The app's source of guilt CONTENT: loads the bundled pack once and holds whichever pack is
 * currently active. It does not choose lines — that is [GuiltLines] / [GuiltSelector].
 *
 * The split is deliberate (D41). This object used to do both, and once selection grew tiers,
 * daily decks, a locale filter and a cross-surface pin, "loads a file" and "decides what the app
 * says" were plainly two jobs. Keeping the loader a pack source also means the selector can
 * detect a hot-swap by comparing [GuiltPack.identity] instead of the loader having to know who
 * its consumers are.
 *
 * REMOTE-CONFIG SEAM (D33, intact): the pack is bundled today, but nothing here assumes that.
 * The bundled asset is just the FIRST source; [install] accepts an already-parsed pack and swaps
 * it in atomically at runtime, so the Phase-3 backend can add a `GET /guilt_pack` fetcher that
 * calls `GuiltPackParser.parse(body)` → [install] and changes the app's voice with no store
 * release. That is why [install] is a public entry point with a revision check even though only
 * tests call it today.
 *
 * Object (not injected): matches the app's manual-graph, no-DI convention (see SettingsPrefs).
 *
 * Threading: [pack] is @Volatile so a future background fetch calling [install] publishes safely.
 */
object GuiltPackLoader {

    private const val TAG = "ScrollKiller"
    private const val ASSET = "guilt_pack.json"

    @Volatile
    private var pack: GuiltPack? = null

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
            Log.d(
                TAG,
                "guilt pack loaded: ${loaded.identity}, ${loaded.lines.size} lines, " +
                    "locales=${loaded.locales.joinToString { it.tag }}",
            )
        }
        val result = loaded ?: GuiltPack.FALLBACK
        pack = result
        return result
    }

    /**
     * Swap in a new pack at runtime — the hot-swap entry point a future remote fetcher uses.
     *
     * Accepts only a HIGHER [GuiltPack.revision] than the active pack: a stale cached response
     * or an out-of-order retry must not downgrade the user's content.
     *
     * Selection state (decks, rotations, the pinned line) is NOT reset from here — [GuiltSelector]
     * notices the [GuiltPack.identity] change on its next call and drops its own state. That
     * keeps this method's contract to exactly "which content is live", which is also what makes
     * it safe to call from a background fetch.
     *
     * @return true if the pack was installed.
     */
    fun install(context: Context, candidate: GuiltPack): Boolean {
        if (candidate.schemaVersion != GuiltPack.SUPPORTED_SCHEMA_VERSION) return false
        if (candidate.lines.isEmpty()) return false
        if (candidate.revision <= pack(context).revision) return false

        pack = candidate
        Log.d(TAG, "guilt pack installed: ${candidate.identity}")
        return true
    }

    /** Drop the cached pack. Test hook; also correct after a "clear all data". */
    fun reset() {
        pack = null
    }

    private fun readAsset(context: Context): String? = try {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read $ASSET", e)
        null
    }
}
