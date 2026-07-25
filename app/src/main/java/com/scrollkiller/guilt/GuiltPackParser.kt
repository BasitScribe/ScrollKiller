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
     *    would let one line occupy two slots in a cycle);
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

        val lines = parseLines(root)
        if (lines.isEmpty()) return null

        return GuiltPack(
            packId = root.optString("pack_id", "unknown"),
            schemaVersion = schemaVersion,
            revision = root.optInt("revision", 0),
            locale = root.optString("locale", "en"),
            lines = lines,
            surfaces = parseSurfaces(root),
        )
    }

    private fun parseLines(root: JSONObject): List<GuiltLine> {
        val array = root.optJSONArray("lines") ?: return emptyList()
        val out = ArrayList<GuiltLine>(array.length())
        val seenIds = HashSet<String>(array.length())

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (!seenIds.add(id)) continue                                  // duplicate id — first wins
            val category = GuiltCategory.fromId(obj.optString("category")) ?: continue
            val text = obj.optString("text").takeIf { it.isNotBlank() } ?: continue
            // Coerce here so a bad weight can never zero out a pool's total (see GuiltRotation).
            val weight = obj.optInt("weight", 1).coerceAtLeast(1)
            out += GuiltLine(id = id, category = category, weight = weight, text = text)
        }
        return out
    }

    /**
     * The tone policy: which categories each surface may draw from. A surface whose listed
     * categories are all unknown to this client is omitted entirely, which makes
     * [GuiltPack.linesFor] fall back to all categories — better an off-tone line than none.
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
