package com.scrollkiller.guilt

/**
 * The tone a guilt line is written in. The wire value ([id]) is what appears in
 * guilt_pack.json and must stay stable — a remote pack is authored against these strings.
 *
 * D9: the pack deliberately mixes guilt with PRIDE. An app that only ever mocks you is an
 * app you uninstall out of shame, which fixes nothing. Do not remove [PRIDE].
 */
enum class GuiltCategory(val id: String) {
    /** Playful mockery. The default voice. */
    ROAST("roast"),

    /** The heavier time-is-finite register. Used sparingly — it lands hard. */
    EXISTENTIAL("existential"),

    /** Gives permission to continue, so continuing has to be a conscious choice. */
    REVERSE_PSYCH("reverse_psych"),

    /** Encouragement for stopping. The anti-shame counterweight (D9). */
    PRIDE("pride");

    companion object {
        /** Wire value → category, or null if this client doesn't know it (newer pack). */
        fun fromId(id: String?): GuiltCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A place a guilt line can be rendered. Each surface draws from its own category mix (set in
 * the pack's `surfaces` map) and keeps its own no-repeat rotation, so the block screen and the
 * bubble don't consume each other's lines.
 */
enum class GuiltSurface(val id: String) {
    /** The full-screen block at the daily limit. Draws from all four categories. */
    BLOCK("block"),

    /** Bubble nudge when the brain flips HEALTHY → CRACKING (count hits 50). */
    BUBBLE_CRACKING("bubble_cracking"),

    /** Bubble nudge when the brain flips CRACKING → FRIED (count hits 150). */
    BUBBLE_FRIED("bubble_fried"),
}

/**
 * One line of the pack.
 *
 * @param id stable, unique. This is the rotation's no-repeat key — reusing an id for
 *   different text makes the new text look "already seen" for the rest of the session.
 * @param weight relative odds within the eligible pool; coerced to at least 1 on parse so a
 *   malformed `"weight": 0` can't make a line unpickable (or, worse, make a whole pool's
 *   total weight zero and break the draw).
 */
data class GuiltLine(
    val id: String,
    val category: GuiltCategory,
    val weight: Int,
    val text: String,
)

/**
 * A parsed, validated guilt pack — the in-memory form of assets/guilt_pack.json.
 *
 * Shaped as remote config on purpose (D33): [schemaVersion] lets an old client refuse a pack
 * it can't read, [revision] lets a newer pack supersede an older one, and [surfaces] carries
 * the *tone policy* rather than hard-coding it in Kotlin — so which categories the block screen
 * versus the bubble draw from becomes something a server can tune without an app update.
 *
 * Pure Kotlin (no Android imports) so the selection logic is unit-testable off-device.
 */
data class GuiltPack(
    val packId: String,
    val schemaVersion: Int,
    val revision: Int,
    val locale: String,
    val lines: List<GuiltLine>,
    val surfaces: Map<GuiltSurface, Set<GuiltCategory>>,
) {

    /**
     * The lines [surface] may draw from.
     *
     * Two deliberate fallbacks, both toward "show something" rather than "show nothing" — an
     * empty block screen is a worse failure than an off-tone line:
     *  - a surface absent from the pack (or mapped to categories this client doesn't know)
     *    falls back to ALL categories;
     *  - if that still yields nothing, falls back to the whole pack.
     */
    fun linesFor(surface: GuiltSurface): List<GuiltLine> {
        val categories = surfaces[surface]?.takeIf { it.isNotEmpty() }
            ?: GuiltCategory.entries.toSet()
        return lines.filter { it.category in categories }.ifEmpty { lines }
    }

    companion object {

        /** The schema this client understands. Bump only on a breaking shape change. */
        const val SUPPORTED_SCHEMA_VERSION = 1

        /**
         * Last-resort pack used only if the bundled asset is missing or unparseable — which
         * would mean a broken build, but the block screen still must not render blank text.
         * Kept tiny on purpose: it is a safety net, not a second content source to maintain.
         */
        val FALLBACK = GuiltPack(
            packId = "fallback",
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            revision = 0,
            locale = "en",
            lines = listOf(
                GuiltLine("fb_01", GuiltCategory.ROAST, 1, "That's enough for today."),
                GuiltLine("fb_02", GuiltCategory.EXISTENTIAL, 1, "The feed is infinite. You are not."),
                GuiltLine("fb_03", GuiltCategory.PRIDE, 1, "Closing this is a win. Small, but it counts."),
            ),
            surfaces = emptyMap(),
        )
    }
}
