package com.scrollkiller.ui.theme

/**
 * Every brand colour in the app, once.
 *
 * ## Why this is raw `Long`s and not `Color`
 * ScrollKiller draws through TWO render paths and only one of them can see a Compose theme:
 *  - **Compose** — Home and the dashboard, which read `MaterialTheme.colorScheme`.
 *  - **Plain `android.view` over other apps** — the bubble, the block screen and the progress ring.
 *    These are deliberately OUTSIDE the Compose theme (D19/D30): they are added straight to the
 *    WindowManager on top of Instagram, so there is no `MaterialTheme` in scope and never will be.
 *
 * A palette expressed as `androidx.compose.ui.graphics.Color` is unreachable from the second path, so
 * before this file existed those surfaces hardcoded ARGB literals — five separate lists that had
 * already drifted. Raw `0xAARRGGBB` longs are the one representation BOTH paths can consume:
 *   - Compose:  `Color(Brand.COBALT)`
 *   - View:     `Brand.INK.toInt()`
 *
 * This is the same reasoning that already keeps [com.scrollkiller.brain.BrainState.accentArgb] a
 * `Long` rather than a `Color`, and it keeps this file free of Compose AND Android imports so it can
 * be read from anywhere, including unit tests.
 *
 * ## The rule
 * A brand colour is written HERE and nowhere else. The overlay XML layouts carry no brand colours at
 * all — they are applied in code at inflation from these constants, which is why `overlay_block.xml`
 * has no `android:background` hex any more. The ONE documented exception is the pre-Compose window
 * background in `values/themes.xml`, which must be an XML resource because it paints before any of our
 * code runs; `values/colors.xml` names this file in a comment, and vice versa below.
 *
 * ## Where the values come from (D58)
 * Sampled from the shipped mascot art (`art/mascot/healthy.png`) rather than invented, so the palette
 * and the character cannot drift apart. The mascot IS the brand: a pink brain in a cobalt backwards
 * cap with an orange brim and red sneakers.
 */
object Brand {

    /* --- Core identity ------------------------------------------------------------------ */

    /**
     * The cap. Primary brand colour and the one that does the heavy lifting.
     *
     * Cobalt leads rather than the brain's coral because this is a CALMING app that happens to be
     * energetic, not the reverse. A coral-led palette on a screen whose job is to tell you that you
     * have watched 180 reels reads as alarm; a calm ground with energetic punctuation reads as a
     * friend pointing something out.
     */
    const val COBALT = 0xFF0050B0

    /** Cap shadow. Primary containers, and the primary tone in dark theme where full cobalt glares. */
    const val COBALT_DEEP = 0xFF00337A

    /** Light cobalt wash for containers and the Home hero surface. */
    const val COBALT_SOFT = 0xFFD6E4F7

    /**
     * The brain. Accent — the hero numeral, primary CTAs, anything that should be looked at first.
     *
     * Used as PUNCTUATION, deliberately never as a large fill: coral at scale reads juvenile, and this
     * screen is already emotionally loud without the colour shouting too.
     */
    const val CORAL = 0xFFF08090

    /** Brain shadow. Pressed states and emphasis where [CORAL] is too light to carry text. */
    const val CORAL_DEEP = 0xFFE03050

    /** The brim. Warnings, and the CRACKING state — see [STATE_CRACKING]. */
    const val BRIM = 0xFFF06000

    /* --- Grounds ------------------------------------------------------------------------- */

    /**
     * Branded near-black for every surface drawn OVER another app.
     *
     * Not `#000000` and not the old neutral `#0D0D0D`: a trace of the cap's blue means the block
     * screen and the bubble read as belonging to this app rather than to the system. Kept very dark
     * because it sits on top of someone's video and must not compete with it.
     */
    const val INK = 0xFF0B1020

    /** [INK] at ~95% — the block screen's ground, opaque enough to fully cover the reel beneath. */
    const val INK_BLOCK = 0xF20B1020

    /** [INK] at ~90% — the bubble pill, which should feel present without hiding the video. */
    const val INK_BUBBLE = 0xE60B1020

    /** Light-theme app background. Faintly cool rather than pure white, to sit under cobalt. */
    const val CANVAS = 0xFFF7F9FC

    /** Dark-theme app background. A step up from [INK] so cards can sit on it and be seen. */
    const val CANVAS_DARK = 0xFF121826

    /* --- On-colours ---------------------------------------------------------------------- */

    /** Primary text/icon colour on any dark ground. */
    const val ON_DARK = 0xFFFFFFFF

    /** Secondary text on a dark ground — ~76% white, recedes without becoming unreadable. */
    const val ON_DARK_MUTED = 0xC2FFFFFF

    /** Hairline borders and progress tracks on a dark ground. */
    const val ON_DARK_FAINT = 0x33FFFFFF

    /** Primary text on a light ground. Ink rather than black, matching [INK]'s hue. */
    const val ON_LIGHT = 0xFF11182B

    /* --- Brain states (D36/D58) ---------------------------------------------------------- */

    /*
     * The count-driven mascot states. Retuned from the Material-2 defaults they shipped with
     * (#2E7D32 / #F9A825 / #C62828) but the green→amber→red SEMANTICS are kept on purpose: this is
     * the one number the user reads every day, and severity has to be legible before it is stylish.
     * What made the old values feel clinical was the flat Material palette, not the traffic-light
     * idea — so two of the three are now the mascot's own colours and the green is a fresh mint.
     *
     * Read through [com.scrollkiller.brain.BrainState.accentArgb], which is what both Home and the
     * bubble tint from. THRESHOLDS ARE NOT COLOURS: 50 and 150 live in BrainState and are untouched.
     */

    /** HEALTHY. Mint, not forest — alive rather than institutional. */
    const val STATE_HEALTHY = 0xFF17B87A

    /** CRACKING. The mascot's own brim orange, so the warning colour is already in the art. */
    const val STATE_CRACKING = BRIM

    /** FRIED. The sneaker red — hot, but the character's red rather than a generic error red. */
    const val STATE_FRIED = 0xFFF04040

    /* --- Shape + spacing ----------------------------------------------------------------- */

    /*
     * One radius scale and one spacing rhythm, here rather than in the Compose theme, for the same
     * reason as the colours: the block screen and the bubble need them too and cannot read a theme.
     * This is what makes the over-app surfaces feel like the same product as Home.
     */

    /** Corner radius for cards and panels, in dp. */
    const val RADIUS_CARD_DP = 20

    /** Corner radius for buttons and the bubble pill, in dp. */
    const val RADIUS_PILL_DP = 24

    /** Base spacing step in dp. Layouts use multiples of this so the rhythm is shared. */
    const val SPACE_DP = 8
}
