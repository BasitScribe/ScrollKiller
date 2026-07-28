package com.scrollkiller.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Compose-typed views of the palette. Every value comes from [Brand] — nothing is DEFINED here.
 *
 * This file used to hold the Compose template's Purple80 / PurpleGrey40 / Pink40 placeholders. They
 * are gone (D58). If you are looking for where a brand colour is defined, it is Brand.kt, and that is
 * deliberately the only place — the over-app surfaces cannot read a Compose theme, so the source has
 * to be plain Kotlin that both render paths can consume.
 */

val BrandCobalt = Color(Brand.COBALT)
val BrandCobaltDeep = Color(Brand.COBALT_DEEP)
val BrandCobaltSoft = Color(Brand.COBALT_SOFT)

val BrandCoral = Color(Brand.CORAL)
val BrandCoralDeep = Color(Brand.CORAL_DEEP)
val BrandBrim = Color(Brand.BRIM)

val BrandInk = Color(Brand.INK)
val BrandCanvas = Color(Brand.CANVAS)
val BrandCanvasDark = Color(Brand.CANVAS_DARK)

val BrandOnLight = Color(Brand.ON_LIGHT)
val BrandOnDark = Color(Brand.ON_DARK)
