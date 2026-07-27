package com.scrollkiller.guilt

/**
 * Which language + region a guilt line is written for.
 *
 * Guilt does not translate. "Your thumb has done more cardio than you have" is a joke in one
 * English and a shrug in another, and the India Gen-Z pack we ship reads as noise to someone who
 * has never heard "scene" used as a noun. So a line carries its audience with it, the loader
 * filters by the user's choice, and adding a pack for a new audience is a JSON drop plus one
 * entry in [GuiltLocaleCatalog] — never an engine change. See D43.
 *
 * NOT [java.util.Locale]: that type carries scripts, variants, extensions and a
 * platform-dependent matching algorithm, none of which we want deciding whose jokes someone
 * sees. Two plain lowercase/uppercase strings and an explicit fallback chain
 * ([GuiltPack.pool]) is the whole requirement, and it stays Android-free and unit-testable.
 *
 * @param lang ISO-639 language code, lowercase ("en").
 * @param region ISO-3166 region code, uppercase ("IN"). The region is what actually carries the
 *   voice — Indian English and American English are the same [lang] and completely different
 *   packs.
 */
data class GuiltLocale(val lang: String, val region: String) {

    /** BCP-47-ish tag ("en-IN"). The stored form in prefs and in the pack's wire format. */
    val tag: String get() = "$lang-$region"

    companion object {

        /**
         * The fallback audience, and the one pack that ships today: Indian English.
         *
         * It is the DEFAULT rather than "en-US" deliberately — the app's first market is India,
         * so an unknown/unset preference should land on the pack that was actually written for
         * the people using it, not on a pack that does not exist yet.
         */
        val DEFAULT = GuiltLocale(lang = "en", region = "IN")

        /**
         * Parse a stored tag ("en-IN"), falling back to [DEFAULT] for anything unusable — a
         * null preference on first run, a tag written by a newer build, a corrupted pref.
         * Normalises case so "EN-in" and "en-IN" are the same audience.
         */
        fun parse(tag: String?): GuiltLocale {
            val parts = tag?.split('-', '_')?.filter { it.isNotBlank() } ?: return DEFAULT
            val lang = parts.getOrNull(0)?.lowercase() ?: return DEFAULT
            val region = parts.getOrNull(1)?.uppercase() ?: return DEFAULT
            return GuiltLocale(lang, region)
        }
    }
}
