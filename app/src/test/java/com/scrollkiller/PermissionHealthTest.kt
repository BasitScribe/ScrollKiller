package com.scrollkiller

import com.scrollkiller.permission.PermissionGap
import com.scrollkiller.permission.PermissionHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Can the app actually do its job right now?" — the model that exists because the answer used to
 * be improvised at each call site, and one of those improvisations let the product silently stop
 * working for a whole session (D51).
 *
 * The state that caused the incident gets its own named test: accessibility fine, overlay missing.
 * Everything looked healthy — the counter climbed to 108 — while every block was refused.
 */
class PermissionHealthTest {

    private fun health(
        accessibility: Boolean = true,
        overlay: Boolean = true,
        notify: Boolean = true,
    ) = PermissionHealth(accessibility, overlay, notify)

    /* --- the incident ------------------------------------------------------------------ */

    @Test
    fun `the D51 state - counting fine, block dead - is NOT fully active`() {
        // Detection works perfectly and the block cannot appear. This is what a debug reinstall
        // produced, and what the app used to report as nothing at all.
        val broken = health(accessibility = true, overlay = false)
        assertTrue("counting still works, and saying otherwise would be its own lie", broken.canDetect)
        assertFalse("the block cannot appear", broken.canBlock)
        assertFalse(broken.isFullyActive)
        assertEquals(PermissionGap.OVERLAY, broken.firstMissing)
    }

    /* --- the truth table --------------------------------------------------------------- */

    @Test
    fun `canBlock needs BOTH accessibility and overlay`() {
        assertTrue(health().canBlock)
        assertFalse("no overlay window", health(overlay = false).canBlock)
        assertFalse("nothing is running", health(accessibility = false).canBlock)
        assertFalse(health(accessibility = false, overlay = false).canBlock)
    }

    @Test
    fun `canDetect follows accessibility alone`() {
        assertTrue(health(overlay = false, notify = false).canDetect)
        assertFalse(health(accessibility = false).canDetect)
    }

    @Test
    fun `isFullyActive ignores notifications`() {
        // Notifications are how a failure is REPORTED, not part of the job. An app that called
        // itself "not fully active" over a missing alert channel would be crying wolf about the
        // one banner that has to be believed the first time.
        assertTrue(health(notify = false).isFullyActive)
        assertFalse(health(notify = false).isHealthy)
        assertTrue(health(notify = false).isDegraded)
    }

    @Test
    fun `degraded means working but unable to warn`() {
        assertTrue(health(notify = false).isDegraded)
        // Not degraded when something bigger is broken — that is an outright failure, and the
        // banner must show the error tone rather than the softer one.
        assertFalse(health(overlay = false, notify = false).isDegraded)
        assertFalse(health(accessibility = false, notify = false).isDegraded)
        assertFalse("everything granted is healthy, not degraded", health().isDegraded)
    }

    @Test
    fun `healthy is all three, and shows no banner`() {
        assertTrue(PermissionHealth.HEALTHY.isHealthy)
        assertTrue(PermissionHealth.HEALTHY.isFullyActive)
        assertTrue(PermissionHealth.HEALTHY.canWarnOutOfApp)
        assertNull("a healthy app must render no banner at all", PermissionHealth.HEALTHY.firstMissing)
    }

    /* --- the banner's single Fix button ------------------------------------------------ */

    @Test
    fun `firstMissing returns the WORST gap, not just any gap`() {
        // The banner offers ONE Fix button and routes it from this, so the ordering is the
        // feature. With everything broken it must point at accessibility: fixing the overlay
        // first would change nothing, because nothing is running to use it.
        assertEquals(
            PermissionGap.ACCESSIBILITY,
            health(accessibility = false, overlay = false, notify = false).firstMissing,
        )
        assertEquals(
            PermissionGap.ACCESSIBILITY,
            health(accessibility = false, overlay = true, notify = true).firstMissing,
        )
        assertEquals(
            PermissionGap.OVERLAY,
            health(overlay = false, notify = false).firstMissing,
        )
        assertEquals(PermissionGap.NOTIFICATIONS, health(notify = false).firstMissing)
    }

    @Test
    fun `the gap severity order is the one the banner depends on`() {
        // Asserted explicitly rather than left implicit in the enum: firstMissing is only correct
        // if this order is, and reordering the enum is a one-line edit that would silently point
        // the Fix button at the wrong screen.
        assertEquals(
            listOf(PermissionGap.ACCESSIBILITY, PermissionGap.OVERLAY, PermissionGap.NOTIFICATIONS),
            PermissionGap.entries.toList(),
        )
    }

    /* --- exhaustive ------------------------------------------------------------------- */

    @Test
    fun `every combination is self-consistent`() {
        // Cheap to enumerate all eight, and it catches a future field being added to one derived
        // property but not another.
        listOf(true, false).forEach { a ->
            listOf(true, false).forEach { o ->
                listOf(true, false).forEach { n ->
                    val h = health(a, o, n)
                    val label = "acc=$a overlay=$o notify=$n"
                    assertEquals("$label: canBlock", a && o, h.canBlock)
                    assertEquals("$label: isFullyActive", a && o, h.isFullyActive)
                    assertEquals("$label: isHealthy", a && o && n, h.isHealthy)
                    assertEquals("$label: banner shown", !(a && o && n), h.firstMissing != null)
                    // A healthy app is never degraded, and a degraded one is never healthy.
                    assertFalse("$label: healthy and degraded at once", h.isHealthy && h.isDegraded)
                }
            }
        }
    }
}
