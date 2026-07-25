package com.scrollkiller.brain

/**
 * The app's emotional core: how fried your brain is, as a pure function of today's
 * reel count. The brain visibly degrades as the count climbs.
 *
 * Single source of truth for every surface — the Compose Home hero, the plain android.view
 * TextView bubble, and the block screen all derive their state from here, so there is no
 * second threshold to drift. Deliberately free of Android/Compose imports so it stays
 * unit-testable and usable from either render path:
 *   - Compose:  Color(state.accentArgb)
 *   - TextView: state.accentArgb.toInt()
 *
 * The state's ARTWORK is resolved by [MascotArt], not held here: art needs `R`, and keeping
 * this enum Android-free is what makes it testable on the JVM. This type answers "which
 * state", MascotArt answers "what it looks like".
 *
 * @param accentArgb 0xAARRGGBB accent colour for the state.
 */
enum class BrainState(val accentArgb: Long) {
    HEALTHY(0xFF2E7D32), // green
    CRACKING(0xFFF9A825), // amber
    FRIED(0xFFC62828); // red

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
