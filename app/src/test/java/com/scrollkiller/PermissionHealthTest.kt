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
        runtimeDenied: Boolean = false,
    ) = PermissionHealth(accessibility, overlay, notify, runtimeDenied)

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

    @Test
    fun `the D52 state - the permission LIES and the window is refused - is not fully active`() {
        // The MediaTek/Chinese-ROM case. Every permission query answers yes; the window still
        // never appears. If canBlock trusted canDrawOverlays here, the Home banner would show
        // green while the user scrolled past their limit unblocked — which is the D51 failure
        // wearing a disguise.
        val lying = health(runtimeDenied = true)
        assertTrue("Android insists the permission is granted", lying.canDrawOverlays)
        assertTrue("counting is unaffected", lying.canDetect)
        assertFalse("but the window does not exist, so nothing can block", lying.canBlock)
        assertFalse(lying.isFullyActive)
        assertEquals(PermissionGap.OVERLAY_BLOCKED_BY_SYSTEM, lying.firstMissing)
    }

    @Test
    fun `an actually-missing permission outranks an observed refusal`() {
        // Both true means the permission is genuinely off and the refusal is merely its
        // consequence — so the banner must say "grant it", not "your device is blocking us".
        // Pointing a user at the harder explanation when the simple one applies wastes the one
        // message they will read.
        assertEquals(
            PermissionGap.OVERLAY,
            health(overlay = false, runtimeDenied = true).firstMissing,
        )
    }

    @Test
    fun `an observed refusal outranks a missing notification permission`() {
        assertEquals(
            PermissionGap.OVERLAY_BLOCKED_BY_SYSTEM,
            health(notify = false, runtimeDenied = true).firstMissing,
        )
    }

    /* --- the truth table --------------------------------------------------------------- */

    @Test
    fun `canBlock needs accessibility, the permission, AND a window that actually appears`() {
        assertTrue(health().canBlock)
        assertFalse("no overlay window", health(overlay = false).canBlock)
        assertFalse("nothing is running", health(accessibility = false).canBlock)
        assertFalse("the window was refused at runtime", health(runtimeDenied = true).canBlock)
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
            listOf(
                PermissionGap.ACCESSIBILITY,
                PermissionGap.OVERLAY,
                PermissionGap.OVERLAY_BLOCKED_BY_SYSTEM,
                PermissionGap.NOTIFICATIONS,
            ),
            PermissionGap.entries.toList(),
        )
    }

    /* --- exhaustive ------------------------------------------------------------------- */

    @Test
    fun `every combination is self-consistent`() {
        // Cheap to enumerate all sixteen, and it catches a future field being added to one derived
        // property but not another — which is exactly the shape of the D52 edit.
        val bools = listOf(true, false)
        bools.forEach { a ->
            bools.forEach { o ->
                bools.forEach { n ->
                    bools.forEach { denied ->
                        val h = health(a, o, n, denied)
                        val label = "acc=$a overlay=$o notify=$n denied=$denied"
                        val blockable = a && o && !denied
                        assertEquals("$label: canBlock", blockable, h.canBlock)
                        assertEquals("$label: isFullyActive", blockable, h.isFullyActive)
                        assertEquals("$label: isHealthy", blockable && n, h.isHealthy)
                        assertEquals("$label: banner shown", !(blockable && n), h.firstMissing != null)
                        // A healthy app is never degraded, and a degraded one is never healthy.
                        assertFalse("$label: healthy and degraded", h.isHealthy && h.isDegraded)
                    }
                }
            }
        }
    }
}
