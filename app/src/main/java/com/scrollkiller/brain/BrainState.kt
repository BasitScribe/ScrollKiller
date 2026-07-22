package com.scrollkiller.brain

/**
 * The app's emotional core: how fried your brain is, as a pure function of today's
 * reel count. The brain visibly degrades as the count climbs.
 *
 * Single source of truth for BOTH surfaces — the Compose Home hero and the plain
 * android.view TextView bubble derive their glyph/colour from here, so there is no
 * second mapping to drift. Deliberately free of Android/Compose imports so it stays
 * unit-testable and usable from either render path:
 *   - Compose:  Color(state.accentArgb)
 *   - TextView: state.accentArgb.toInt()
 *
 * @param emoji glyph shown on both surfaces (count stays alongside it).
 * @param accentArgb 0xAARRGGBB accent colour for the state.
 */
enum class BrainState(val emoji: String, val accentArgb: Long) {
    HEALTHY("🧠", 0xFF2E7D32), // green
    CRACKING("🤯", 0xFFF9A825), // amber
    FRIED("💀", 0xFFC62828); // red

    companion object {
        /** Count at which the brain starts CRACKING (inclusive). */
        const val CRACKING_AT = 50

        /** Count at which the brain is FRIED (inclusive). */
        const val FRIED_AT = 150

        /**
         * Map a daily count to a brain state:
         *   0..49 -> HEALTHY, 50..149 -> CRACKING, 150+ -> FRIED.
         */
        fun forCount(count: Int): BrainState = when {
            count >= FRIED_AT -> FRIED
            count >= CRACKING_AT -> CRACKING
            else -> HEALTHY
        }
    }
}
