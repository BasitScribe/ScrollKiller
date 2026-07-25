package com.scrollkiller

import com.scrollkiller.service.OverlayPlacement
import com.scrollkiller.service.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The D38 containment rules: the overlay window — compact pill or expanded panel — is always
 * fully inside the usable screen area.
 *
 * These are the cases that were actually broken on the device (expand near an edge) plus the
 * ones that are easy to regress silently (a panel taller than the screen, a rotation that makes
 * the parked position invalid). The on-device run still checks all four edges by hand; what is
 * checked here is that the arithmetic behind it is right, which is not something you can read
 * off a screenshot.
 *
 * A typical 1080×2400 phone: 0..1080 wide, status bar 88px, gesture nav 48px.
 */
class OverlayPlacementTest {

    private val phone = ScreenBounds(left = 0, top = 88, right = 1080, bottom = 2400 - 48)

    /** Every case must satisfy this — it IS the bug report, stated as a predicate. */
    private fun assertContained(bounds: ScreenBounds, x: Int, y: Int, w: Int, h: Int) {
        assertTrue("left edge off-screen: x=$x < ${bounds.left}", x >= bounds.left)
        assertTrue("top edge off-screen: y=$y < ${bounds.top}", y >= bounds.top)
        assertTrue("right edge off-screen: ${x + w} > ${bounds.right}", x + w <= bounds.right)
        assertTrue("bottom edge off-screen: ${y + h} > ${bounds.bottom}", y + h <= bounds.bottom)
    }

    // --- the reported bug: expanding near an edge ---------------------------------------

    @Test
    fun `panel stays on screen when the bubble is parked at the right edge`() {
        // Pill dragged flush right (1080 - 140), panel is 620 wide — anchored to the bubble it
        // would start at x=940 and run 480px off the display. This is the reported bug.
        val placement = OverlayPlacement.expanded(phone, width = 620, height = 520, anchorY = 400)
        assertContained(phone, placement.x, placement.y, 620, 520)
    }

    @Test
    fun `panel stays on screen when the bubble is parked at the bottom edge`() {
        // Pill at the very bottom of the usable area; the panel is ~5x its height and grows down.
        val placement = OverlayPlacement.expanded(phone, width = 620, height = 520, anchorY = 2300)
        assertContained(phone, placement.x, placement.y, 620, 520)
        assertEquals("should sit flush against the bottom inset", phone.bottom - 520, placement.y)
    }

    @Test
    fun `panel stays below the status bar when the bubble is at the top edge`() {
        val placement = OverlayPlacement.expanded(phone, width = 620, height = 520, anchorY = 0)
        assertContained(phone, placement.x, placement.y, 620, 520)
        assertEquals(phone.top, placement.y)
    }

    // --- the panel's own placement rule ---------------------------------------------------

    @Test
    fun `panel is horizontally centred regardless of where the bubble was parked`() {
        val fromLeft = OverlayPlacement.expanded(phone, 620, 520, anchorY = 400)
        val fromRight = OverlayPlacement.expanded(phone, 620, 520, anchorY = 1800)
        assertEquals((1080 - 620) / 2, fromLeft.x)
        assertEquals("x must not depend on the pill's position", fromLeft.x, fromRight.x)
    }

    @Test
    fun `panel keeps the bubble's y when there is room, so the header does not jump`() {
        val placement = OverlayPlacement.expanded(phone, 620, 520, anchorY = 700)
        assertEquals(700, placement.y)
    }

    // --- the compact pill -----------------------------------------------------------------

    @Test
    fun `compact pill can sit flush against an edge but not past one`() {
        // Dragged well past the right/bottom edges: clamped to flush, not rejected.
        val overshot = OverlayPlacement.compact(phone, width = 140, height = 96, x = 5000, y = 5000)
        assertEquals(phone.right - 140, overshot.x)
        assertEquals(phone.bottom - 96, overshot.y)
        assertContained(phone, overshot.x, overshot.y, 140, 96)
    }

    @Test
    fun `compact pill is pushed out from under the status bar and cutout`() {
        val overshot = OverlayPlacement.compact(phone, width = 140, height = 96, x = -300, y = -300)
        assertEquals(phone.left, overshot.x)
        assertEquals(phone.top, overshot.y)
    }

    @Test
    fun `an in-bounds drag position is left exactly as the user placed it`() {
        val placement = OverlayPlacement.compact(phone, width = 140, height = 96, x = 400, y = 900)
        assertEquals(400, placement.x)
        assertEquals(900, placement.y)
    }

    // --- rotation and other resizes -------------------------------------------------------

    @Test
    fun `a position valid in portrait is pulled back in when the screen becomes landscape`() {
        val landscape = ScreenBounds(left = 0, top = 0, right = 2400 - 48, bottom = 1080)
        // Parked near the bottom of the portrait screen — off the bottom of the landscape one.
        val placement = OverlayPlacement.compact(landscape, width = 140, height = 96, x = 900, y = 2300)
        assertContained(landscape, placement.x, placement.y, 140, 96)
    }

    // --- degenerate: the view does not fit --------------------------------------------------

    @Test
    fun `a panel wider than the screen pins to the left edge rather than centring off it`() {
        // Huge font scale / tiny cover display. Centring a 1400px panel in a 1080px screen would
        // put x at -160, hiding the total and the first bar — the part actually worth reading.
        val placement = OverlayPlacement.expanded(phone, width = 1400, height = 520, anchorY = 400)
        assertEquals(phone.left, placement.x)
    }

    @Test
    fun `a panel taller than the screen pins to the top rather than clamping off the top`() {
        val placement = OverlayPlacement.expanded(phone, width = 620, height = 4000, anchorY = 900)
        assertEquals(phone.top, placement.y)
    }

    @Test
    fun `a pill larger than the bounds still pins inside rather than throwing`() {
        // bounds.right - width < bounds.left here, i.e. an inverted clamp range.
        val placement = OverlayPlacement.compact(phone, width = 2000, height = 4000, x = 500, y = 500)
        assertEquals(phone.left, placement.x)
        assertEquals(phone.top, placement.y)
    }
}
