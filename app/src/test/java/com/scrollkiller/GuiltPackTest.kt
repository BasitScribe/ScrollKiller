package com.scrollkiller

import com.scrollkiller.guilt.GuiltCategory
import com.scrollkiller.guilt.GuiltLocale
import com.scrollkiller.guilt.GuiltPack
import com.scrollkiller.guilt.GuiltPackParser
import com.scrollkiller.guilt.GuiltSurface
import com.scrollkiller.guilt.GuiltText
import com.scrollkiller.guilt.GuiltTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the parser's tolerance rules and — importantly — parses the REAL bundled asset, so a
 * typo in guilt_pack.json fails the build instead of silently degrading every user to the
 * three-line fallback at runtime.
 *
 * The pack is CONTENT, and content has invariants a reviewer will not catch by eye at eighty
 * lines: every tier populated, no tier so thin the daily deck can't rotate, no tier accidentally
 * escalating past its band. Those are asserted here.
 */
class GuiltPackTest {

    /**
     * The shipped asset. Unit tests run with the module dir as CWD; the fallbacks cover a
     * runner that uses the repo root instead.
     */
    private fun bundledAsset(): File =
        listOf(
            "src/main/assets/guilt_pack.json",
            "app/src/main/assets/guilt_pack.json",
        ).map(::File).firstOrNull { it.exists() }
            ?: error("guilt_pack.json not found from CWD ${File("").absolutePath}")

    private fun pack(): GuiltPack = GuiltPackParser.parse(bundledAsset().readText())!!

    /* --- the real pack ------------------------------------------------------------- */

    @Test
    fun `the bundled guilt pack parses`() {
        val pack = GuiltPackParser.parse(bundledAsset().readText())
        assertNotNull("bundled guilt_pack.json failed to parse — the app would fall back", pack)
        assertEquals(GuiltPack.SUPPORTED_SCHEMA_VERSION, pack!!.schemaVersion)
        assertTrue("pack looks too small to feel varied", pack.lines.size >= 60)
    }

    @Test
    fun `the bundled pack carries every category, including pride (D9)`() {
        val present = pack().lines.map { it.category }.toSet()
        assertEquals(
            "every category must be represented — PRIDE especially (D9 anti-shame mix)",
            GuiltCategory.entries.toSet(),
            present,
        )
    }

    @Test
    fun `no line bakes a number into its text (D47)`() {
        // THE regression guard for D47. A hardcoded number is wrong at every count but the one it
        // was written for, and the D46 cadence re-shows lines all day, so it WILL be wrong. The
        // parser drops such a line from a remote pack; here, in the pack we ship, it fails the
        // build — a silently shorter pack is not an acceptable outcome for our own content.
        val offenders = pack().lines.mapNotNull { line ->
            GuiltText.offendingNumber(line.text)?.let { "${line.id} contains '$it': ${line.text}" }
        }
        assertTrue(
            "guilt lines must use ${GuiltText.COUNT_TOKEN}/${GuiltText.MINUTES_TOKEN}, " +
                "never a literal number: " + offenders.joinToString(" | "),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the pack actually uses the count token`() {
        // The guard above passes trivially on a pack that never mentions the count at all, which
        // would be a duller app. Assert the tokens are genuinely in use.
        val tokenised = pack().lines.count {
            it.text.contains(GuiltText.COUNT_TOKEN) || it.text.contains(GuiltText.MINUTES_TOKEN)
        }
        assertTrue("only $tokenised lines reference the live count", tokenised >= 20)
    }

    @Test
    fun `every tier has enough lines to be worth rotating`() {
        // A floor, not the target. What each tier ACTUALLY needs to sustain the 7-day no-repeat
        // rule is computed in GuiltPoolMathTest, and tiers 3 and 4 do not currently meet it —
        // that shortfall is reported in D47 rather than asserted here, because failing the build
        // on "the pack is too small" would block every unrelated change until it is written.
        val pack = pack()
        GuiltTier.entries.forEach { tier ->
            val pool = pack.pool(GuiltSurface.AMBIENT, tier, GuiltLocale.DEFAULT)
            assertTrue("$tier has only ${pool.size} ambient lines", pool.size >= 15)
        }
    }

    @Test
    fun `pride is represented at every intensity, including the harshest (D9)`() {
        // At 200 reels the way out still has to exist. A pack that is pure roast at the top is
        // the shame machine D9 exists to prevent, and it would not fail any other assertion.
        val pack = pack()
        GuiltTier.entries.forEach { tier ->
            val pool = pack.pool(GuiltSurface.AMBIENT, tier, GuiltLocale.DEFAULT)
            assertTrue(
                "$tier has no PRIDE line — the top of the curve must still offer a way out",
                pool.any { it.category == GuiltCategory.PRIDE },
            )
        }
    }

    @Test
    fun `no tier can ever draw a line harsher than its own band`() {
        // The whole promise of D41: someone at 52 reels never sees an EXTREME line. This is the
        // assertion that catches a pack thin enough to trigger GuiltPack.pool's last-resort
        // upward widening — which is documented, deliberate, and must never actually fire.
        val pack = pack()
        GuiltSurface.entries.forEach { surface ->
            GuiltTier.entries.forEach { tier ->
                pack.pool(surface, tier, GuiltLocale.DEFAULT).forEach { line ->
                    assertTrue(
                        "$surface/$tier drew ${line.id} at intensity ${line.intensity}, " +
                            "above the tier ceiling ${tier.intensityCeiling}",
                        line.intensity <= tier.intensityCeiling,
                    )
                }
            }
        }
    }

    @Test
    fun `the block surface never gives permission to keep scrolling`() {
        // Tone policy lives in the pack, so assert the shipped policy is the intended one. A
        // reverse-psych line ("go on, one more") on a screen that is physically blocking the app
        // reads as broken, not as wit — so the block draws roast/existential/pride only, and
        // pride above all: the block is where someone needs a way out that isn't shame (D9).
        val pack = pack()
        GuiltTier.entries.forEach { tier ->
            val pool = pack.pool(GuiltSurface.BLOCK, tier, GuiltLocale.DEFAULT)
            assertTrue(
                "block/$tier offers a reverse_psych line while the screen is locked",
                pool.none { it.category == GuiltCategory.REVERSE_PSYCH },
            )
            assertTrue(
                "block/$tier has no PRIDE line",
                pool.any { it.category == GuiltCategory.PRIDE },
            )
        }
    }

    @Test
    fun `every line is short enough for the overlay panel`() {
        // These render in a ~240dp panel floating over someone's video. A long line wraps to
        // four rows and pushes the bars off the bottom of the pill.
        pack().lines.forEach { line ->
            assertTrue(
                "${line.id} is ${line.text.length} chars — too long for the bubble panel",
                line.text.length <= 90,
            )
        }
    }

    @Test
    fun `the bundled pack declares one audience and it is the default`() {
        assertEquals(setOf(GuiltLocale.DEFAULT), pack().locales)
    }

    /* --- audience filtering (D43) ---------------------------------------------------- */

    @Test
    fun `lines inherit the pack's lang and region when they don't declare their own`() {
        val json = """
            {
              "schema_version": 2, "lang": "hi", "region": "IN",
              "lines": [
                {"id":"a","category":"roast","intensity":1,"text":"inherited"},
                {"id":"b","category":"roast","intensity":1,"text":"own","lang":"en","region":"US"}
              ]
            }
        """.trimIndent()
        val pack = GuiltPackParser.parse(json)!!
        assertEquals(GuiltLocale("hi", "IN"), pack.lines.first { it.id == "a" }.locale)
        assertEquals(GuiltLocale("en", "US"), pack.lines.first { it.id == "b" }.locale)
    }

    @Test
    fun `an audience with no lines falls back rather than showing nothing`() {
        // A user whose region has no pack gets the India pack, not silence.
        val pack = pack()
        assertEquals(
            pack.forLocale(GuiltLocale.DEFAULT),
            pack.forLocale(GuiltLocale("fr", "FR")),
        )
    }

    @Test
    fun `a locale is parsed case-insensitively and defaults when unusable`() {
        assertEquals(GuiltLocale("en", "IN"), GuiltLocale.parse("EN-in"))
        assertEquals(GuiltLocale("en", "IN"), GuiltLocale.parse("en_IN"))
        assertEquals(GuiltLocale.DEFAULT, GuiltLocale.parse(null))
        assertEquals(GuiltLocale.DEFAULT, GuiltLocale.parse("garbage"))
    }

    /* --- parser contract ------------------------------------------------------------ */

    @Test
    fun `an unknown schema version is rejected outright`() {
        // v1 is now the "old" schema: a v1 pack has no intensity field, so a v2 client reading
        // it would default every line to the mildest tier and the escalation would vanish.
        val v1 = """{"schema_version": 1, "lines": [{"id":"a","category":"roast","text":"clean line"}]}"""
        assertNull("a superseded schema must not be half-read", GuiltPackParser.parse(v1))
        val future = """{"schema_version": 99, "lines": [{"id":"a","category":"roast","text":"clean line"}]}"""
        assertNull("a future schema must not be half-read", GuiltPackParser.parse(future))
    }

    @Test
    fun `malformed json and empty packs are rejected`() {
        assertNull(GuiltPackParser.parse("not json at all"))
        assertNull(GuiltPackParser.parse("""{"schema_version": 2, "lines": []}"""))
        assertNull(GuiltPackParser.parse("""{"schema_version": 2}"""))
    }

    @Test
    fun `unknown categories are dropped, not fatal (forward compatibility)`() {
        // A newer server pack adds a category this client has never heard of. The known lines
        // must still load — that is what makes the pack safely remote-updatable.
        val json = """
            {
              "schema_version": 2,
              "lines": [
                {"id":"a","category":"roast","intensity":1,"weight":2,"text":"keep me"},
                {"id":"b","category":"haiku","intensity":1,"weight":2,"text":"drop me"}
              ]
            }
        """.trimIndent()
        assertEquals(listOf("a"), GuiltPackParser.parse(json)!!.lines.map { it.id })
    }

    @Test
    fun `blank ids and blank text are dropped`() {
        val json = """
            {
              "schema_version": 2,
              "lines": [
                {"id":"","category":"roast","intensity":1,"text":"no id"},
                {"id":"b","category":"roast","intensity":1,"text":""},
                {"id":"c","category":"roast","intensity":1,"text":"good"}
              ]
            }
        """.trimIndent()
        assertEquals(listOf("c"), GuiltPackParser.parse(json)!!.lines.map { it.id })
    }

    @Test
    fun `duplicate ids keep the first only`() {
        // Ids key the no-repeat rotation AND the daily deck; a duplicate would take two slots.
        val json = """
            {
              "schema_version": 2,
              "lines": [
                {"id":"dup","category":"roast","intensity":1,"text":"first"},
                {"id":"dup","category":"pride","intensity":1,"text":"second"}
              ]
            }
        """.trimIndent()
        val pack = GuiltPackParser.parse(json)!!
        assertEquals(1, pack.lines.size)
        assertEquals("first", pack.lines.single().text)
    }

    @Test
    fun `weights are coerced to at least one`() {
        val json = """
            {
              "schema_version": 2,
              "lines": [
                {"id":"a","category":"roast","intensity":1,"weight":0,"text":"clean line"},
                {"id":"b","category":"roast","intensity":1,"weight":-4,"text":"another clean line"},
                {"id":"c","category":"roast","intensity":1,"text":"a third clean line"}
              ]
            }
        """.trimIndent()
        assertTrue(GuiltPackParser.parse(json)!!.lines.all { it.weight >= 1 })
    }

    @Test
    fun `intensity is clamped into the known range, never dropped`() {
        // A missing intensity is the mildest thing we can safely do with the line; one from a
        // newer pack with a fifth tier becomes the harshest tier we DO have. Dropping either
        // would be an invisible content hole rather than a visible error.
        val json = """
            {
              "schema_version": 2,
              "lines": [
                {"id":"missing","category":"roast","text":"clean line"},
                {"id":"high","category":"roast","intensity":9,"text":"another clean line"},
                {"id":"low","category":"roast","intensity":0,"text":"a third clean line"}
              ]
            }
        """.trimIndent()
        val byId = GuiltPackParser.parse(json)!!.lines.associateBy { it.id }
        assertEquals(3, byId.size)
        assertEquals(GuiltTier.MILD.level, byId.getValue("missing").intensity)
        assertEquals(GuiltTier.EXTREME.level, byId.getValue("high").intensity)
        assertEquals(GuiltTier.MILD.level, byId.getValue("low").intensity)
    }

    @Test
    fun `a surface with no usable categories falls back to the whole pack`() {
        val json = """
            {
              "schema_version": 2,
              "surfaces": { "block": ["haiku", "limerick"] },
              "lines": [{"id":"a","category":"roast","intensity":1,"text":"clean line"}]
            }
        """.trimIndent()
        val pack = GuiltPackParser.parse(json)!!
        assertEquals(
            "an unusable surface mapping must not render an empty block screen",
            1,
            pack.pool(GuiltSurface.BLOCK, GuiltTier.MILD, GuiltLocale.DEFAULT).size,
        )
    }

    @Test
    fun `an empty intensity band widens DOWNWARD, never upward`() {
        // A pack thin at the top shows a milder line to someone at 160 — merely underwhelming.
        // The opposite (a savage line at 52) is the failure the tiers exist to prevent.
        val json = """
            {
              "schema_version": 2,
              "lines": [
                {"id":"a","category":"roast","intensity":1,"text":"mild"},
                {"id":"b","category":"roast","intensity":2,"text":"medium"}
              ]
            }
        """.trimIndent()
        val pack = GuiltPackParser.parse(json)!!
        assertEquals(
            listOf("b"),
            pack.pool(GuiltSurface.AMBIENT, GuiltTier.EXTREME, GuiltLocale.DEFAULT).map { it.id },
        )
    }

    /* --- the no-hardcoded-lines rule -------------------------------------------------- */

    @Test
    fun `guilt lines exist only in the pack, never in Kotlin`() {
        // The hard rule of this feature: content is DATA. A hardcoded line is invisible to the
        // tier system, the daily rotation, the audience filter and the remote hot-swap, and it
        // will not look wrong in review — it will look like a perfectly ordinary string. So the
        // build checks it: GuiltLine may only be constructed in GuiltPack.kt (the three-line
        // crash fallback) and GuiltPackParser.kt (which builds them FROM the pack — it is the
        // data path, so exempting it is not a hole).
        val allowed = setOf("GuiltPack.kt", "GuiltPackParser.kt")
        val sourceRoot = listOf("src/main/java", "app/src/main/java")
            .map(::File).first { it.isDirectory }
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { it.readText().contains("GuiltLine(") }
            .map { it.name }
            .toList()

        assertTrue(
            "guilt lines are hardcoded in: $offenders — they belong in guilt_pack.json",
            offenders.isEmpty(),
        )
        assertEquals(
            "the crash fallback must stay minimal; it is a safety net, not a content source",
            3,
            GuiltPack.FALLBACK.lines.size,
        )
    }

    @Test
    fun `the built-in fallback pack is itself usable at every tier`() {
        // It only runs when the asset is broken, so nothing else would catch a bad one.
        GuiltSurface.entries.forEach { surface ->
            GuiltTier.entries.forEach { tier ->
                assertTrue(
                    "FALLBACK yields nothing for $surface/$tier",
                    GuiltPack.FALLBACK.pool(surface, tier, GuiltLocale.DEFAULT).isNotEmpty(),
                )
            }
        }
    }
}
