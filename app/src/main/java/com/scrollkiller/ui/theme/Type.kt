package com.scrollkiller.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's type scale (D58).
 *
 * ## Why this file is now fully specified
 * It previously defined ONE style (`bodyLarge`) and let the other fourteen fall through to Material
 * defaults. That is what "no typography" looks like in practice: correct, neutral, and completely
 * anonymous. Every style is now stated, because a brand is as much the type as the colour and the
 * defaults were doing none of that work.
 *
 * ## Three deliberate choices
 *  - **Display sizes carry negative tracking and heavy weight.** The count on Home is the emotional
 *    core of the whole product — the number the app exists to put in your face. Default tracking makes
 *    a large numeral look like body text that got bigger; `-1.5.sp` at 64sp Black makes it look SET.
 *  - **Small labels get POSITIVE tracking.** Under ~14sp, letters crowd; opening them up is what keeps
 *    the BETA badge and nav labels legible instead of dense.
 *  - **Body keeps generous line height** (1.5×). The guilt lines are one or two sentences of Gen-Z
 *    copy read at a glance, mid-scroll, and tight leading makes that work harder than it should.
 *
 * ## Still the system face, on purpose
 * `FontFamily.Default` (Roboto). A bundled display face is where this scale would gain the most —
 * specifically the hero numeral, which is doing brand work a system font can only partly carry — but
 * choosing and licensing one is a product decision and it costs APK size, so it is not being slipped
 * into a code change. The scale is built so that swapping it later is ONE edit: define a
 * `FontFamily` here and set it on the display/headline styles.
 */
private val Sans = FontFamily.Default

val Typography = Typography(

    /* --- Display: the hero count, and nothing else -------------------------------------- */

    displayLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Black,
        fontSize = 64.sp,
        lineHeight = 64.sp,     // 1.0 — a single numeral needs no leading
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1.0).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),

    /* --- Headline: screen titles ("Reels are Locked") ----------------------------------- */

    headlineLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),

    /* --- Title: card headings, section labels ------------------------------------------- */

    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),

    /* --- Body: guilt lines, explanations, permission copy ------------------------------- */

    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp,
    ),

    /* --- Label: buttons, nav, badges. Positive tracking at every size ------------------- */

    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        // Widest tracking in the scale: this carries the BETA badge, which is all-caps at 11sp and
        // becomes an unreadable clump without it.
        letterSpacing = 0.9.sp,
    ),
)
