package com.scrollkiller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant 6, asserted against the SHIPPING layout file: the block screen is always exitable.
 *
 * ## Why this test reads XML instead of driving the controller
 * [com.scrollkiller.service.BlockScreenController] needs a real `Context`, `LayoutInflater` and
 * `WindowManager`, and the unit-test `android.jar` is a throwing stub — so the controller itself
 * cannot be exercised here without Robolectric. But the property that matters most is STRUCTURAL:
 * an Exit exists on every panel, it is never hidden, and the root is the class that makes hardware
 * Back focus-independent. All of that lives in the layout, so the layout is what gets asserted.
 *
 * The precedent is [GuiltPackTest], which parses the real bundled `guilt_pack.json` for the same
 * reason: a resource that only fails at runtime, on a device, is a resource nothing is guarding.
 *
 * ## Why this exists NOW (D77)
 * Strict mode deleted the free "5 more minutes" reprieve, which means that on a device with no
 * usable challenge sensor the block panel is **Exit and nothing else**. That is intended and is a
 * strictly harder block — but it also means Exit is now the *only* control on that panel, so an
 * edit that hid, renamed or reordered it would turn "strict" into "trapped" with nothing to catch
 * it. A snooze was never an exit, and removing it must never have moved invariant 6; this is the
 * assertion that keeps that true.
 */
class BlockEscapeTest {

    private fun layout(): String =
        listOf("src/main/res/layout/overlay_block.xml", "app/src/main/res/layout/overlay_block.xml")
            .map(::File)
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("overlay_block.xml not found from CWD ${File("").absolutePath}")

    /** Every `@+id/...` declared in the file, in document order. */
    private fun ids(xml: String): List<String> =
        Regex("""android:id="@\+id/(\w+)"""").findAll(xml).map { it.groupValues[1] }.toList()

    /** The chunk of XML declaring the view with this id, up to the tag's closing `/>`. */
    private fun declarationOf(xml: String, id: String): String {
        val at = xml.indexOf("""android:id="@+id/$id"""")
        assertTrue("no view declares @+id/$id", at >= 0)
        val start = xml.lastIndexOf('<', at)
        val end = xml.indexOf("/>", at)
        assertTrue("declaration of $id is not self-closing; this test needs updating", end > start)
        return xml.substring(start, end)
    }

    @Test
    fun `every panel carries its own Exit`() {
        // A chooser or challenge screen offering only "Back" would be a second screen to escape
        // before you can escape — precisely what invariant 6 forbids. Adding the chooser (D53) is
        // what made this a real risk rather than a hypothetical one.
        val ids = ids(layout())
        listOf("block_exit", "chooser_exit", "challenge_exit").forEach { exit ->
            assertTrue("$exit is missing — a panel with no way out is a P0", exit in ids)
        }
    }

    @Test
    fun `no Exit is hidden or disabled in the layout`() {
        // The way out must not be a consequence of the UI being in a good state (D71). A default
        // visibility of gone/invisible would mean some code path has to remember to reveal it.
        val xml = layout()
        listOf("block_exit", "chooser_exit", "challenge_exit").forEach { exit ->
            val decl = declarationOf(xml, exit)
            assertFalse("$exit ships with a visibility attribute — it must always be visible", decl.contains("android:visibility"))
            assertFalse("$exit ships disabled", decl.contains("""android:enabled="false""""))
        }
    }

    @Test
    fun `Exit comes before the challenge button on the block panel`() {
        // Ordering is load-bearing twice: it makes leaving the visually primary action, and it is
        // the TalkBack traversal order, so a screen-reader user reaches the way OUT before either
        // way to keep scrolling.
        val ids = ids(layout())
        val exit = ids.indexOf("block_exit")
        val challenge = ids.indexOf("block_challenge")
        assertTrue("block_challenge missing", challenge >= 0)
        assertTrue("Exit must be reached before the challenge button", exit < challenge)
    }

    @Test
    fun `on a device with no usable challenge, Exit is the only control left and it survives`() {
        // The sensorless case, which after D77 is the strictest state the block can be in. The
        // challenge button ships `visibility="gone"` and is only revealed when
        // ChallengeAvailability finds something this device can run — so the DEFAULT rendering of
        // block_panel is exactly what a sensorless device sees. Assert that in that state Exit is
        // still present, still visible, and still first.
        val xml = layout()
        assertTrue(
            "block_challenge must default to gone, so a device that cannot run one never sees it",
            declarationOf(xml, "block_challenge").contains("""android:visibility="gone""""),
        )
        val decl = declarationOf(xml, "block_exit")
        assertFalse("Exit must not be conditional on anything", decl.contains("android:visibility"))
    }

    @Test
    fun `the free snooze is gone from the layout (D77 strict mode)`() {
        // Pins the removal so it cannot come back by accident. If it is ever restored deliberately,
        // delete this assertion in the same change that restores the button, the string AND the
        // handler — a dead control on a screen covering another app is worse than any one of them.
        assertFalse(
            "block_snooze is back; strict mode (D77) says challenge or exit, no free bail",
            "block_snooze" in ids(layout()),
        )
    }

    @Test
    fun `the root is BlockRootView, so hardware Back does not depend on focus`() {
        // D71's second defect: Back was an OnKeyListener on the root, which only fires while that
        // ViewGroup is ITSELF focused. TalkBack, a keyboard or a dpad moving focus onto any Button
        // silently disabled the escape hatch. BlockRootView overrides dispatchKeyEvent instead,
        // which ViewRootImpl calls before any focus-based routing.
        val xml = layout()
        assertTrue(
            "the block root must be BlockRootView — a plain layout makes Back focus-dependent",
            xml.contains("<com.scrollkiller.service.BlockRootView"),
        )
    }

    @Test
    fun `the panel stack scrolls, so Exit is reachable at any font scale`() {
        // Every panel is wrap_content inside a centred stack. At a large system font scale or on a
        // short screen the content overflows and Exit is laid out past the bottom of the display.
        // An Exit that exists but cannot be touched is the same trap with a nicer cause.
        val xml = layout()
        assertTrue("the panel stack must sit in a ScrollView", xml.contains("<ScrollView"))
        assertTrue(
            "the ScrollView needs fillViewport, or short content stops being centred",
            xml.contains("""android:fillViewport="true""""),
        )
    }

    @Test
    fun `exactly three panels exist, each with an exit`() {
        // Guards the shape the other assertions assume: if a fourth panel is added, it must bring
        // its own Exit and this test must be updated deliberately rather than silently passing.
        val ids = ids(layout())
        val panels = ids.filter { it.endsWith("_panel") }
        assertEquals(
            "panel set changed — every new panel needs its own Exit (invariant 6)",
            listOf("block_panel", "chooser_panel", "challenge_panel"),
            panels,
        )
    }
}
