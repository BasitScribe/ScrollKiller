package com.scrollkiller.guilt

import android.content.Context
import com.scrollkiller.data.SettingsPrefs

/**
 * Does this user have premium? **A STUB (D85).** The real answer arrives with monetisation.
 *
 * ## Why a stub exists at all instead of the check being added later
 * The alternative was to write the premium CONTENT now and wire the gate up later, which sounds
 * cheaper and is not: without a gate, "premium" is a field nothing reads, so nothing can test that
 * the free pool survives on its own, nothing stops a call site forgetting the distinction, and the
 * day entitlement lands it is a change to the selection path rather than a change to one function.
 * A stub costs one file and makes the gate REAL immediately — [GuiltPack.pool] already filters on
 * it, [GuiltPackTest] already asserts the free pool stands up unaided, and the entire remaining
 * work is replacing the body of [isPremium].
 *
 * ## What replaces this
 * A Play Billing entitlement check, cached, with the usual grace handling. When it lands:
 *  - keep the signature — every caller already treats this as "ask, do not assume";
 *  - keep [SettingsPrefs.premiumOverride] as a DEBUG-only override for the device runs, or delete
 *    it, but do not let it survive as a way to unlock content in a release build;
 *  - nothing else in this package should need to change, which is the property being bought here.
 *
 * ## It is deliberately not cached
 * Called once per guilt draw, which is at most a handful of times a minute (the display gap caps it
 * — D83), against one SharedPreferences read. Caching an entitlement is where staleness bugs live,
 * and there is no measurement saying this needs it.
 */
object Entitlements {

    /**
     * True if premium content should be shown to this user.
     *
     * Currently reads a persisted debug flag that defaults to FALSE, so the shipped behaviour is
     * "everybody is on the free pack" — which is correct, because nobody can buy anything yet.
     */
    fun isPremium(context: Context): Boolean = SettingsPrefs.premiumOverride(context)
}
