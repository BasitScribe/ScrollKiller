package com.scrollkiller.guilt

/**
 * The count thresholds at which the guilt escalates. THE one place to tune them.
 *
 * Separate object rather than constants on [GuiltTier] itself so that changing the product's
 * escalation curve is a four-line edit in a file with nothing else in it, and so the enum's
 * constructor arguments read as a table of thresholds instead of as magic numbers.
 *
 * ## Why these are NOT [com.scrollkiller.brain.BrainState]'s thresholds
 * BrainState's 50/150 decide which MASCOT POSE is drawn; these decide WHAT THE APP SAYS. They
 * happen to agree at 50 and 150 today, and that is a nice coincidence for the moment the mascot
 * cracks and the app starts talking at the same time — but they answer different questions and
 * must be tunable apart. Copy-testing the lines should not be able to change the artwork, and
 * re-balancing the artwork should not silently make the app start roasting someone earlier.
 * Deliberately no cross-reference between the two files; see D41.
 */
object GuiltThresholds {

    /** Below this, the app says NOTHING — just the count and the mascot. See [GuiltTier]. */
    const val MILD_AT = 50

    /** A cheeky roast starts here. */
    const val MEDIUM_AT = 70

    /** Real guilt starts here. */
    const val STRONG_AT = 100

    /** Maximum guilt. Nothing above this — the curve tops out rather than escalating forever. */
    const val EXTREME_AT = 150
}

/**
 * How hard the app is allowed to hit you right now, as a pure function of TODAY'S TOTAL count
 * across every platform (not the per-app count — 30 reels then 40 shorts is one 70-item day).
 *
 * ## The silence below 50 is a feature, not a gap
 * [forCount] returns NULL below [GuiltThresholds.MILD_AT], and every surface renders nothing at
 * all in that case. Fifty short videos is a normal amount of scrolling; an app that comments on
 * it is an app that has cried wolf by the time the number actually matters. The count and the
 * mascot are still there — the app is watching, it just has not got an opinion yet.
 *
 * ## Intensity, and why a line carries one
 * Every line in the pack declares an `intensity` (1..4). A tier draws only from lines whose
 * intensity falls inside its band, so the escalation is CONTENT, not volume or styling: at 55
 * the app teases, at 160 it does not. That mapping lives in the pack (a line's intensity is
 * data) and in this enum (which band a count falls into), and nowhere else — no surface may
 * decide for itself how hard to hit.
 *
 * [intensityFloor] and [intensityCeiling] are a BAND rather than a single number so a future
 * pack can let tiers overlap (e.g. STRONG drawing from 3..4 once there is enough content to
 * make that interesting) without touching the selection code. They are equal today.
 *
 * Pure Kotlin, no Android imports, so the whole escalation curve is unit-testable off-device.
 *
 * @param level the tier's intensity, 1 (mildest) to 4 (most savage). Also the wire value a
 *   line's `intensity` field is compared against.
 * @param minCount the lowest daily total in this band (inclusive).
 */
enum class GuiltTier(val level: Int, val minCount: Int) {

    /** 50–69. A gentle, playful nudge. */
    MILD(1, GuiltThresholds.MILD_AT),

    /** 70–99. A cheeky roast. */
    MEDIUM(2, GuiltThresholds.MEDIUM_AT),

    /** 100–149. Real guilt; the roast has teeth. */
    STRONG(3, GuiltThresholds.STRONG_AT),

    /** 150+. Maximum guilt. Still funny — see the anti-uninstall principle in D9. */
    EXTREME(4, GuiltThresholds.EXTREME_AT);

    /** Lowest line intensity this tier may show. */
    val intensityFloor: Int get() = level

    /** Highest line intensity this tier may show. Never exceeded except by [GuiltPack.pool]'s
     *  documented last-resort fallback for a broken pack. */
    val intensityCeiling: Int get() = level

    /** Is a line of [intensity] appropriate for this tier? */
    fun covers(intensity: Int): Boolean = intensity in intensityFloor..intensityCeiling

    companion object {

        /** Tiers hardest-first, so [forCount] reads as a descending threshold table. */
        private val descending = entries.sortedByDescending { it.minCount }

        /**
         * The tier for a daily total, or NULL when the app should stay quiet (below
         * [GuiltThresholds.MILD_AT]).
         *
         * Null rather than a fifth `SILENT` entry on purpose: "there is no line" is genuinely
         * the absence of a tier, and making it an enum value invites a surface to look up a
         * pool for it and render whatever falls out of the fallback chain.
         */
        fun forCount(count: Int): GuiltTier? = descending.firstOrNull { count >= it.minCount }

        /** The intensity values any tier can ask for. Used to validate a pack's coverage. */
        val INTENSITY_RANGE: IntRange = 1..entries.maxOf { it.level }
    }
}
