package com.scrollkiller.guilt

import org.json.JSONObject

/**
 * Parses guilt_pack.json into a [GuiltPack]. Uses `org.json` (Android framework, zero
 * dependencies) rather than kotlinx.serialization — one small hand-rolled parse is cheaper
 * than a serialization plugin, and CLAUDE.md's ₹0/low-complexity constraint applies to build
 * complexity too. `org.json` is on the unit-test classpath as a test-only dependency, so this
 * is testable off-device.
 *
 * PARSE POSTURE: total. Every failure mode returns null or drops the offending line rather
 * than throwing, because the same parser will one day be fed bytes off the network (D33). A
 * malformed remote pack must degrade to "keep the pack we have", never to a crash inside the
 * accessibility service.
 */
object GuiltPackParser {

    /**
     * Parse [json], or return null if it's unusable. Null means "reject this pack" — the
     * caller keeps whatever pack it already had.
     *
     * Rejected outright:
     *  - unparseable JSON;
     *  - a [GuiltPack.SUPPORTED_SCHEMA_VERSION] mismatch (a future breaking shape — an old
     *    client must not guess at it);
     *  - a pack with no usable lines (nothing to show is not a pack).
     *
     * Tolerated (dropped/defaulted, pack still accepted) — this is the forward-compatibility
     * that lets a newer server pack run on an older client:
     *  - a line with an unknown `category`, blank `text`, or missing `id`;
     *  - a duplicate `id` (first wins — ids are the rotation's no-repeat key, so a duplicate
     *    would let one line occupy two slots in a cycle, and would collide in the daily deck);
     *  - a missing or out-of-range `intensity` (defaulted/clamped, see [parseLines]);
     *  - a missing `lang`/`region` (inherited from the pack's own defaults);
     *  - an unknown surface key, or a surface listing categories this client doesn't know.
     */
    fun parse(json: String): GuiltPack? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }

        val schemaVersion = root.optInt("schema_version", -1)
        if (schemaVersion != GuiltPack.SUPPORTED_SCHEMA_VERSION) return null

        val defaultLocale = GuiltLocale(
            lang = root.optString("lang").takeIf { it.isNotBlank() } ?: GuiltLocale.DEFAULT.lang,
            region = root.optString("region").takeIf { it.isNotBlank() } ?: GuiltLocale.DEFAULT.region,
        )

        val lines = parseLines(root, defaultLocale)
        if (lines.isEmpty()) return null

        return GuiltPack(
            packId = root.optString("pack_id", "unknown"),
            schemaVersion = schemaVersion,
            revision = root.optInt("revision", 0),
            defaultLocale = defaultLocale,
            lines = lines,
            surfaces = parseSurfaces(root),
        )
    }

    private fun parseLines(root: JSONObject, defaultLocale: GuiltLocale): List<GuiltLine> {
        val array = root.optJSONArray("lines") ?: return emptyList()
        val out = ArrayList<GuiltLine>(array.length())
        val seenIds = HashSet<String>(array.length())

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (!seenIds.add(id)) continue                                  // duplicate id — first wins
            val category = GuiltCategory.fromId(obj.optString("category")) ?: continue
            val text = obj.optString("text").takeIf { it.isNotBlank() } ?: continue
            // A line that bakes a number into its text is WRONG at every count but the one it was
            // written for (D47) — and the cadence re-shows lines all day, so it will be wrong. Drop
            // it rather than ship it: a missing line is invisible, a line insisting you are at
            // "one-fifty" when the counter beside it reads 312 is not. Same posture as an unknown
            // category — the pack still loads. A bad LOCAL pack is caught harder, by a test.
            GuiltText.offendingNumber(text)?.let { offender ->
                android.util.Log.w(
                    "ScrollKiller",
                    "guilt line '$id' dropped: text contains the baked-in number '$offender'. " +
                        "Use ${GuiltText.COUNT_TOKEN} or ${GuiltText.MINUTES_TOKEN} instead.",
                )
                continue
            }
            // Coerce here so a bad weight can never zero out a pool's total (see GuiltRotation).
            val weight = obj.optInt("weight", 1).coerceAtLeast(1)
            // CLAMPED, not dropped. A line with no intensity is the mildest thing we can safely
            // do with it, and one claiming an intensity this client has never heard of (a newer
            // pack with a fifth tier) becomes the harshest tier we DO have rather than a line no
            // tier can ever draw — which would be an invisible content hole, not an error.
            val intensity = obj.optInt("intensity", GuiltTier.INTENSITY_RANGE.first)
                .coerceIn(GuiltTier.INTENSITY_RANGE)
            out += GuiltLine(
                id = id,
                category = category,
                intensity = intensity,
                weight = weight,
                text = text,
                lang = obj.optString("lang").takeIf { it.isNotBlank() } ?: defaultLocale.lang,
                region = obj.optString("region").takeIf { it.isNotBlank() } ?: defaultLocale.region,
            )
        }
        return out
    }

    /**
     * The tone policy: which categories each surface may draw from. A surface whose listed
     * categories are all unknown to this client is omitted entirely, which makes [GuiltPack.pool]
     * fall back to all categories — better an off-tone line than none.
     */
    private fun parseSurfaces(root: JSONObject): Map<GuiltSurface, Set<GuiltCategory>> {
        val obj = root.optJSONObject("surfaces") ?: return emptyMap()
        val out = HashMap<GuiltSurface, Set<GuiltCategory>>()

        for (surface in GuiltSurface.entries) {
            val array = obj.optJSONArray(surface.id) ?: continue
            val categories = (0 until array.length())
                .mapNotNull { GuiltCategory.fromId(array.optString(it)) }
                .toSet()
            if (categories.isNotEmpty()) out[surface] = categories
        }
        return out
    }
}
