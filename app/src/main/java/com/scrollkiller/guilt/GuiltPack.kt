package com.scrollkiller.guilt

/**
 * The tone a guilt line is written in. The wire value ([id]) is what appears in
 * guilt_pack.json and must stay stable — a remote pack is authored against these strings.
 *
 * Category is the line's VOICE; [GuiltLine.intensity] is how hard it hits. They are independent
 * on purpose: there is a gentle roast and a savage one, an early-permission reverse-psych and a
 * late one. Collapsing them would mean "the app gets meaner" could only be expressed as "the app
 * changes personality", which is not the same product.
 *
 * D9: the pack deliberately mixes guilt with PRIDE. An app that only ever mocks you is an
 * app you uninstall out of shame, which fixes nothing. Do not remove [PRIDE], and note that it
 * carries lines at EVERY intensity — at 200 reels the way out still has to exist.
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
 * the pack's `surfaces` map) and keeps its own no-repeat rotation.
 *
 * ## Why there are only two (D41)
 * There used to be three — `block`, `bubble_cracking`, `bubble_fried` — with the two bubble
 * entries encoding escalation as SEPARATE SURFACES. That was the wrong axis: escalation is now
 * [GuiltTier], which applies to every surface at once, so a per-intensity surface would be a
 * second, competing escalation model. What is left is the genuine surface distinction: the
 * ambient line the app is currently thinking, versus the full-screen block's one-off.
 */
enum class GuiltSurface(val id: String) {

    /**
     * The line the app is CURRENTLY saying — Home's header, the bubble's threshold nudge, and
     * the bubble's expanded panel. All three read the same pinned line at any given moment
     * ([GuiltSelector.current]), so the app never says two different things at once.
     */
    AMBIENT("ambient"),

    /** The full-screen block at the daily limit. A distinct moment, so it draws its own line. */
    BLOCK("block"),
}

/**
 * Whether a line is included with the app or reserved for a paying user (D85).
 *
 * ## The wire value is `access`, deliberately NOT `tier`
 * "Tier" already means something load-bearing in this package — [GuiltTier] is the INTENSITY band
 * (1..4) that decides how hard a line is allowed to hit. A second, unrelated "tier" on the line
 * itself would collide with it in every conversation, every log line and every future reader's
 * head, and the two are genuinely independent axes: there are premium lines at every intensity and
 * free lines at every intensity. So the monetisation axis is `access`, and [GuiltTier] keeps the
 * word it had first.
 *
 * ## Missing vs unrecognised are treated DIFFERENTLY, and that is the point
 * A line with NO `access` is [FREE]: every pack written before this field existed is entirely free
 * content, so that is not a guess, it is a fact about the corpus, and it means the 72 lines that
 * shipped before D85 did not have to be touched.
 *
 * A line with an access value this client does not KNOW (`"trial"`, from some future pack) is
 * [PREMIUM] instead. The asymmetry is deliberate: an unknown tier is one this client cannot verify
 * entitlement for, and the safe failure is to withhold it rather than to give paid content away to
 * everybody. Defaulting both cases the same way would have to pick one of those two mistakes for
 * both, and they are not the same size.
 */
enum class GuiltAccess(val id: String) {

    /** Shipped with the app. Everyone sees these. */
    FREE("free"),

    /** Reserved for a paying user. Withheld unless entitlement says otherwise. */
    PREMIUM("premium");

    companion object {

        /** Wire value → access. See the enum doc for why the two null cases differ. */
        fun fromId(id: String?): GuiltAccess =
            when {
                id.isNullOrBlank() -> FREE
                else -> entries.firstOrNull { it.id == id } ?: PREMIUM
            }
    }
}

/**
 * One line of the pack.
 *
 * @param id stable, unique. This is the rotation's no-repeat key AND the daily deck's shuffle
 *   key — reusing an id for different text makes the new text look "already seen" for the rest
 *   of the session.
 * @param intensity 1..4, matched against [GuiltTier]'s band. Governs WHEN the line may appear:
 *   a 4 is never shown to someone at 55 reels. Coerced into [GuiltTier.INTENSITY_RANGE] on
 *   parse, so a typo'd `"intensity": 9` becomes the harshest known tier rather than a line that
 *   no tier can ever draw.
 * @param weight relative odds within the eligible pool; coerced to at least 1 on parse so a
 *   malformed `"weight": 0` can't make a line unpickable (or, worse, make a whole pool's
 *   total weight zero and break the draw).
 * @param lang/@param region the audience this line was written for (see [GuiltLocale]).
 *   Defaulted from the pack's own top-level `lang`/`region` at parse time, so a single-audience
 *   pack does not repeat them on all eighty lines.
 * @param access free or paid (D85). Defaults to [GuiltAccess.FREE] so a pack written before the
 *   field existed — and any hand-authored line that simply omits it — is free content, which is
 *   both true and the fail-safe direction for a file people edit by hand.
 */
data class GuiltLine(
    val id: String,
    val category: GuiltCategory,
    val intensity: Int,
    val weight: Int,
    val text: String,
    val lang: String = GuiltLocale.DEFAULT.lang,
    val region: String = GuiltLocale.DEFAULT.region,
    val access: GuiltAccess = GuiltAccess.FREE,
) {
    /** The audience this line is for. */
    val locale: GuiltLocale get() = GuiltLocale(lang, region)
}

/**
 * A parsed, validated guilt pack — the in-memory form of assets/guilt_pack.json.
 *
 * Shaped as remote config on purpose (D33): [schemaVersion] lets an old client refuse a pack
 * it can't read, [revision] lets a newer pack supersede an older one, and [surfaces] carries
 * the *tone policy* rather than hard-coding it in Kotlin — so which categories the block screen
 * versus the ambient line draw from becomes something a server can tune without an app update.
 *
 * ONE pack object can hold MANY audiences (D43): lines carry [GuiltLine.lang]/[GuiltLine.region]
 * and [pool] filters by them. Shipping several locales as one file rather than one file each is
 * what makes "add Hinglish" a JSON drop with no loader change — the alternative, a file per
 * locale, would need asset-name resolution, a per-locale revision, and a second install path.
 *
 * Pure Kotlin (no Android imports) so the selection logic is unit-testable off-device.
 *
 * @param defaultLocale the audience a line inherits when it doesn't name its own.
 */
data class GuiltPack(
    val packId: String,
    val schemaVersion: Int,
    val revision: Int,
    val defaultLocale: GuiltLocale,
    val lines: List<GuiltLine>,
    val surfaces: Map<GuiltSurface, Set<GuiltCategory>>,
) {

    /**
     * Identity of this exact content. Anything holding derived state (decks, rotations, the
     * pinned line) compares this and throws its state away when it changes — a hot-swapped
     * pack's ids may not overlap the old one's, and stale no-repeat entries then suppress
     * nothing at all. See [GuiltSelector].
     */
    val identity: String get() = "$packId@$revision"

    /**
     * The lines [surface] may draw from for [tier], in [locale].
     *
     * Four filters, applied in this order, each with a documented fallback. The order matters:
     * AUDIENCE first (a line in the wrong language is unusable, not merely off-tone), then ACCESS,
     * then TONE, then INTENSITY.
     *
     * 1. **Audience** — exact `lang-region`, else the same language at the default region, else
     *    [GuiltLocale.DEFAULT], else every line. A user whose region has no pack gets the India
     *    pack rather than silence.
     * 1b. **Access** — premium lines are dropped unless [includePremium] (D85). This sits SECOND,
     *    above tone and intensity, and it is the ONE filter with no fallback: every other step
     *    widens when it would return nothing, and this one must not, because a fallback here would
     *    hand paid content to a free user in exactly the situation where the free pool is thin —
     *    i.e. constantly. A pack whose free set is too small is a CONTENT bug, caught by a test
     *    over the bundled asset, not something to paper over at render time.
     * 2. **Tone** — the categories [surface] is mapped to in the pack. A surface absent from the
     *    pack (or mapped only to categories this client doesn't know) falls back to ALL
     *    categories; if that yields nothing, to the audience pool. An off-tone line beats a
     *    blank header.
     * 3. **Intensity** — [GuiltTier.covers]. If the band is empty, widen DOWNWARD to the highest
     *    intensity present below the tier's ceiling: a pack that is thin at the top shows a
     *    tier-3 line to someone at 160, which is merely underwhelming. Widening UPWARD is the
     *    absolute last resort and only reachable when a surface has NO line at or below the
     *    tier's ceiling — i.e. a broken pack. It is guarded against by a test over the bundled
     *    asset, because showing an EXTREME line to someone at 52 is precisely the failure the
     *    tiers exist to prevent.
     */
    fun pool(
        surface: GuiltSurface,
        tier: GuiltTier,
        locale: GuiltLocale,
        includePremium: Boolean = false,
    ): List<GuiltLine> {
        val audience = forLocale(locale)
        // No `.ifEmpty` here, and that asymmetry with every line below it is the whole point —
        // see the doc. Defaults to false so a call site that forgets the argument withholds paid
        // content rather than leaking it.
        val entitled =
            if (includePremium) audience else audience.filter { it.access == GuiltAccess.FREE }
        val categories = surfaces[surface]?.takeIf { it.isNotEmpty() }
            ?: GuiltCategory.entries.toSet()
        val toned = entitled.filter { it.category in categories }.ifEmpty { entitled }

        toned.filter { tier.covers(it.intensity) }.let { if (it.isNotEmpty()) return it }

        val belowCeiling = toned.filter { it.intensity <= tier.intensityCeiling }
        if (belowCeiling.isNotEmpty()) {
            val nearest = belowCeiling.maxOf { it.intensity }
            return belowCeiling.filter { it.intensity == nearest }
        }
        return toned   // last resort: a pack with nothing mild enough. See the doc above.
    }

    /** The lines written for [locale], via the documented audience fallback chain. */
    fun forLocale(locale: GuiltLocale): List<GuiltLine> {
        lines.filter { it.locale == locale }.let { if (it.isNotEmpty()) return it }
        lines.filter { it.lang == locale.lang && it.region == GuiltLocale.DEFAULT.region }
            .let { if (it.isNotEmpty()) return it }
        lines.filter { it.locale == GuiltLocale.DEFAULT }
            .let { if (it.isNotEmpty()) return it }
        return lines
    }

    /** Every audience this pack can serve. Diagnostics and pack-authoring tests. */
    val locales: Set<GuiltLocale> get() = lines.map { it.locale }.toSet()

    companion object {

        /**
         * The schema this client understands.
         *
         * Bumped 1 → 2 for the D41/D43 shape: lines gained a required `intensity` and optional
         * `lang`/`region`, and the `surfaces` keys changed. A v1 client reading a v2 pack would
         * silently ignore intensity and show EXTREME lines at 50, which is exactly the kind of
         * half-read a schema version exists to refuse.
         */
        const val SUPPORTED_SCHEMA_VERSION = 2

        /**
         * Last-resort pack used only if the bundled asset is missing or unparseable — which
         * would mean a broken build, but the block screen still must not render blank text.
         *
         * Kept tiny on purpose: it is a safety net, not a second content source to maintain,
         * and it is the ONLY place a line's text appears in Kotlin (D41 — everything else is
         * data). Its three lines sit at intensities 1/2/3 rather than 1/2/3/4: [pool]'s
         * downward widening covers a tier-4 count from the 3, and a fourth string here would be
         * a fourth string to keep in sync with a pack nobody should ever see.
         */
        val FALLBACK = GuiltPack(
            packId = "fallback",
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            revision = 0,
            defaultLocale = GuiltLocale.DEFAULT,
            lines = listOf(
                GuiltLine("fb_01", GuiltCategory.ROAST, 1, 1, "That's enough for today."),
                GuiltLine("fb_02", GuiltCategory.EXISTENTIAL, 2, 1, "The feed is infinite. You are not."),
                GuiltLine("fb_03", GuiltCategory.PRIDE, 3, 1, "Closing this is a win. Small, but it counts."),
            ),
            surfaces = emptyMap(),
        )
    }
}
