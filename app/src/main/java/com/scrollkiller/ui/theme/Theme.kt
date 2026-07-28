package com.scrollkiller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Light scheme: cobalt primary, coral accent, faintly cool canvas.
 *
 * Coral is the SECONDARY rather than the primary on purpose — see [Brand.COBALT]. Material uses
 * `primary` for filled buttons and large emphasis areas, and coral at that scale makes the whole app
 * shout; it is reserved for the hero numeral and single CTAs, where it should be the first thing the
 * eye lands on.
 */
private val LightColors = lightColorScheme(
    primary = BrandCobalt,
    onPrimary = BrandOnDark,
    primaryContainer = BrandCobaltSoft,
    onPrimaryContainer = BrandCobaltDeep,
    secondary = BrandCoralDeep,
    onSecondary = BrandOnDark,
    secondaryContainer = BrandCoral,
    onSecondaryContainer = BrandOnLight,
    tertiary = BrandBrim,
    onTertiary = BrandOnDark,
    background = BrandCanvas,
    onBackground = BrandOnLight,
    surface = BrandCanvas,
    onSurface = BrandOnLight,
    surfaceVariant = BrandCobaltSoft,
    onSurfaceVariant = BrandCobaltDeep,
    error = BrandCoralDeep,
    onError = BrandOnDark,
)

/**
 * Dark scheme. Primary steps DOWN to a lighter cobalt rather than up: full [Brand.COBALT] against a
 * dark ground is where saturated blue starts to glare, and this app is used at 2am by design.
 */
private val DarkColors = darkColorScheme(
    primary = BrandCobaltSoft,
    onPrimary = BrandCobaltDeep,
    primaryContainer = BrandCobaltDeep,
    onPrimaryContainer = BrandCobaltSoft,
    secondary = BrandCoral,
    onSecondary = BrandOnLight,
    secondaryContainer = BrandCoralDeep,
    onSecondaryContainer = BrandOnDark,
    tertiary = BrandBrim,
    onTertiary = BrandOnLight,
    background = BrandCanvasDark,
    onBackground = BrandOnDark,
    surface = BrandCanvasDark,
    onSurface = BrandOnDark,
    surfaceVariant = BrandInk,
    onSurfaceVariant = BrandOnDark,
    error = BrandCoral,
    onError = BrandOnLight,
)

/**
 * The app-wide theme. Every Compose screen sits inside `ScrollKillerTheme { }`.
 *
 * ## There is no dynamicColor any more, and that is the point (D58)
 * This used to take `dynamicColor: Boolean = true`, which on Android 12+ derives the entire palette
 * from the user's WALLPAPER. That is the opposite of having a brand: the app looked different on every
 * phone, the mascot's cobalt-and-coral art sat against colours chosen by someone's lock screen, and
 * "kill the template purple" was not even the right fix — the purple was just what a default wallpaper
 * happened to produce. The palette is now fixed and hand-specified, and the PARAMETER IS REMOVED
 * rather than defaulted to false, so it cannot be switched back without a deliberate edit here.
 *
 * This theme reaches only the Compose surfaces. The bubble and block screen draw over other apps,
 * outside any theme, and read [Brand] directly — same values, different path. See Brand.kt.
 */
@Composable
fun ScrollKillerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
