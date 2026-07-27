package com.scrollkiller.guilt

import androidx.annotation.StringRes
import com.scrollkiller.R

/**
 * One selectable guilt pack, as Settings shows it.
 *
 * @param locale what the loader filters on.
 * @param labelRes how it's named to the user. A string resource rather than a literal because
 *   this label is chrome, and chrome is translated — unlike the LINES, which are not translated
 *   and never will be (see [GuiltLocale]).
 */
data class GuiltLocaleOption(
    val locale: GuiltLocale,
    @param:StringRes val labelRes: Int,
)

/**
 * The audiences a user can pick between in Settings.
 *
 * ONE entry today, and that is the point: the selector, the loader, the parser and the pack
 * format are all already multi-audience (D43), so shipping a second pack is a JSON drop plus one
 * line here. No engine change, no schema bump, no migration. A single-option list is what
 * "the structure supports it now" looks like when the content doesn't exist yet — the
 * alternative, hard-coding en-IN throughout and generalising later, is the change that never
 * happens.
 *
 * Settings still renders the row when there is one option: it tells the user what voice they are
 * hearing, which is worth a line on its own, and it means the control does not appear from
 * nowhere the day a second pack lands.
 */
object GuiltLocaleCatalog {

    /** India Gen-Z English — the pack bundled in assets/guilt_pack.json. */
    val INDIA_EN = GuiltLocaleOption(GuiltLocale("en", "IN"), R.string.guilt_pack_en_in)

    /** Every pack a user may choose, in display order. */
    val options: List<GuiltLocaleOption> = listOf(INDIA_EN)

    /** The option for [locale], falling back to the default pack for an unknown stored value. */
    fun optionFor(locale: GuiltLocale): GuiltLocaleOption =
        options.firstOrNull { it.locale == locale }
            ?: options.firstOrNull { it.locale == GuiltLocale.DEFAULT }
            ?: INDIA_EN
}
