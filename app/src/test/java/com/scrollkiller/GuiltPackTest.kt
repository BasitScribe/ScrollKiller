package com.scrollkiller

import com.scrollkiller.guilt.GuiltCategory
import com.scrollkiller.guilt.GuiltPack
import com.scrollkiller.guilt.GuiltPackParser
import com.scrollkiller.guilt.GuiltSurface
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

    /* --- the real pack ------------------------------------------------------------- */

    @Test
    fun `the bundled guilt pack parses`() {
        val pack = GuiltPackParser.parse(bundledAsset().readText())
        assertNotNull("bundled guilt_pack.json failed to parse — the app would fall back", pack)
        assertEquals(GuiltPack.SUPPORTED_SCHEMA_VERSION, pack!!.schemaVersion)
        assertTrue("pack looks too small to feel varied", pack.lines.size >= 20)
    }

    @Test
    fun `the bundled pack carries every category, including pride (D9)`() {
        val pack = GuiltPackParser.parse(bundledAsset().readText())!!
        val present = pack.lines.map { it.category }.toSet()
        assertEquals(
            "every category must be represented — PRIDE especially (D9 anti-shame mix)",
            GuiltCategory.entries.toSet(),
            present,
        )
    }

    @Test
    fun `every surface resolves to a usable pool in the bundled pack`() {
        val pack = GuiltPackParser.parse(bundledAsset().readText())!!
        GuiltSurface.entries.forEach { surface ->
            val lines = pack.linesFor(surface)
            assertTrue("$surface has too few lines to avoid feeling repetitive", lines.size >= 5)
        }
    }

    @Test
    fun `the block surface includes pride but the bubble nudges do not`() {
        // Tone policy lives in the pack, so assert the shipped policy is the intended one:
        // the block screen is where someone needs a way out that isn't shame.
        val pack = GuiltPackParser.parse(bundledAsset().readText())!!
        assertTrue(
            pack.linesFor(GuiltSurface.BLOCK).any { it.category == GuiltCategory.PRIDE },
        )
        assertTrue(
            pack.linesFor(GuiltSurface.BUBBLE_FRIED).none { it.category == GuiltCategory.PRIDE },
        )
    }

    /* --- parser contract ------------------------------------------------------------ */

    @Test
    fun `an unknown schema version is rejected outright`() {
        val json = """{"schema_version": 99, "lines": [{"id":"a","category":"roast","text":"x"}]}"""
        assertNull("a future schema must not be half-read", GuiltPackParser.parse(json))
    }

    @Test
    fun `malformed json and empty packs are rejected`() {
        assertNull(GuiltPackParser.parse("not json at all"))
        assertNull(GuiltPackParser.parse("""{"schema_version": 1, "lines": []}"""))
        assertNull(GuiltPackParser.parse("""{"schema_version": 1}"""))
    }

    @Test
    fun `unknown categories are dropped, not fatal (forward compatibility)`() {
        // A newer server pack adds a category this client has never heard of. The known lines
        // must still load — that is what makes the pack safely remote-updatable.
        val json = """
            {
              "schema_version": 1,
              "lines": [
                {"id":"a","category":"roast","weight":2,"text":"keep me"},
                {"id":"b","category":"haiku","weight":2,"text":"drop me"}
              ]
            }
        """.trimIndent()
        val pack = GuiltPackParser.parse(json)!!
        assertEquals(listOf("a"), pack.lines.map { it.id })
    }

    @Test
    fun `blank ids and blank text are dropped`() {
        val json = """
            {
              "schema_version": 1,
              "lines": [
                {"id":"","category":"roast","text":"no id"},
                {"id":"b","category":"roast","text":""},
                {"id":"c","category":"roast","text":"good"}
              ]
            }
        """.trimIndent()
        assertEquals(listOf("c"), GuiltPackParser.parse(json)!!.lines.map { it.id })
    }

    @Test
    fun `duplicate ids keep the first only`() {
        // Ids are the rotation's no-repeat key; a duplicate would take two slots in a cycle.
        val json = """
            {
              "schema_version": 1,
              "lines": [
                {"id":"dup","category":"roast","text":"first"},
                {"id":"dup","category":"pride","text":"second"}
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
              "schema_version": 1,
              "lines": [
                {"id":"a","category":"roast","weight":0,"text":"x"},
                {"id":"b","category":"roast","weight":-4,"text":"y"},
                {"id":"c","category":"roast","text":"z"}
              ]
            }
        """.trimIndent()
        assertTrue(GuiltPackParser.parse(json)!!.lines.all { it.weight >= 1 })
    }

    @Test
    fun `a surface with no usable categories falls back to the whole pack`() {
        val json = """
            {
              "schema_version": 1,
              "surfaces": { "block": ["haiku", "limerick"] },
              "lines": [{"id":"a","category":"roast","text":"x"}]
            }
        """.trimIndent()
        val pack = GuiltPackParser.parse(json)!!
        assertEquals(
            "an unusable surface mapping must not render an empty block screen",
            1,
            pack.linesFor(GuiltSurface.BLOCK).size,
        )
    }

    @Test
    fun `the built-in fallback pack is itself usable`() {
        // It only runs when the asset is broken, so nothing else would catch a bad one.
        GuiltSurface.entries.forEach { surface ->
            assertTrue(GuiltPack.FALLBACK.linesFor(surface).isNotEmpty())
        }
    }
}
