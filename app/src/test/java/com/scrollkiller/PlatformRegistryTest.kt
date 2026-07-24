package com.scrollkiller

import com.scrollkiller.service.GatingMode
import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the multi-platform registry's safety invariants (D24). The most important one:
 * an ENFORCED platform MUST have non-empty surface markers — otherwise the matcher passes
 * through and it would count the whole app's feed, the exact thing enforcement prevents.
 */
class PlatformRegistryTest {

    @Test
    fun `every enabled platform has a display name and unit noun`() {
        PlatformRegistry.enabled.forEach { spec ->
            assertTrue("displayName blank for ${spec.platform}", spec.displayName.isNotBlank())
            assertTrue("unitNoun blank for ${spec.platform}", spec.unitNoun.isNotBlank())
        }
    }

    @Test
    fun `enforced platforms have non-empty surface markers (never gate the feed)`() {
        PlatformRegistry.enabled
            .filter { it.gating == GatingMode.ENFORCED }
            .forEach { spec ->
                assertTrue(
                    "ENFORCED ${spec.platform} must have markers or it counts everything",
                    spec.surfaceMarkers.isNotEmpty(),
                )
            }
    }

    @Test
    fun `block stays dormant for every platform until markers are verified (D19-D24)`() {
        PlatformRegistry.enabled.forEach { spec ->
            assertTrue(
                "blockEnabled must ship false for ${spec.platform} (unverified markers)",
                !spec.blockEnabled,
            )
        }
    }

    @Test
    fun `detectPlatform maps a tracked package and rejects an untracked one`() {
        assertEquals(Platform.INSTAGRAM, PlatformRegistry.detectPlatform(null, "com.instagram.android"))
        assertEquals(Platform.YOUTUBE, PlatformRegistry.detectPlatform(null, "com.google.android.youtube"))
        assertNull(PlatformRegistry.detectPlatform(null, "com.whatsapp"))
        assertNull(PlatformRegistry.detectPlatform(null, null))
    }

    @Test
    fun `container match is package-agnostic - support-library and androidx RecyclerView both hit (D27)`() {
        val youtube = PlatformRegistry.specFor(Platform.YOUTUBE)
        // The bug: YouTube Shorts scrolls the LEGACY support-library RecyclerView. A
        // fully-qualified endsWith check missed it, so Shorts counted zero.
        assertTrue(youtube.matchesContainer("android.support.v7.widget.RecyclerView"))
        assertTrue(youtube.matchesContainer("androidx.recyclerview.widget.RecyclerView"))
        // Instagram's ViewPager v1 vs ViewPager2 must stay distinct (simple names differ).
        val instagram = PlatformRegistry.specFor(Platform.INSTAGRAM)
        assertTrue(instagram.matchesContainer("androidx.viewpager.widget.ViewPager"))
        assertTrue(instagram.matchesContainer("androidx.viewpager2.widget.ViewPager2"))
        // Non-containers must not match.
        assertFalse(youtube.matchesContainer("android.widget.LinearLayout"))
        assertFalse(youtube.matchesContainer(null))
        assertFalse(youtube.matchesContainer(""))
    }

    @Test
    fun `only toured platforms enforce - untoured stay in SHADOW (D26-D28)`() {
        // Proven by the 2026-07-24 surface tour → ENFORCED.
        assertEquals(GatingMode.ENFORCED, PlatformRegistry.specFor(Platform.INSTAGRAM).gating)
        assertEquals(GatingMode.ENFORCED, PlatformRegistry.specFor(Platform.YOUTUBE).gating)
        // Not toured → SHADOW (never ships a guessed ENFORCED marker).
        assertEquals(GatingMode.SHADOW, PlatformRegistry.specFor(Platform.TIKTOK).gating)
        assertEquals(GatingMode.SHADOW, PlatformRegistry.specFor(Platform.SNAPCHAT).gating)
    }

    @Test
    fun `platform ids are stable wire values (must match SCHEMA daily_counts)`() {
        // These strings are persisted + synced; changing one silently orphans data.
        assertEquals("instagram", Platform.INSTAGRAM.id)
        assertEquals("youtube", Platform.YOUTUBE.id)
        assertEquals("tiktok", Platform.TIKTOK.id)
        assertEquals("snapchat", Platform.SNAPCHAT.id)
    }
}
