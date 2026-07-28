package com.scrollkiller

import com.scrollkiller.service.AdvanceStrategy
import com.scrollkiller.service.GatingMode
import com.scrollkiller.service.Maturity
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
    fun `every enabled platform has a display name, short name and unit noun`() {
        PlatformRegistry.enabled.forEach { spec ->
            assertTrue("displayName blank for ${spec.platform}", spec.displayName.isNotBlank())
            assertTrue("unitNoun blank for ${spec.platform}", spec.unitNoun.isNotBlank())
            // The bubble's breakdown line renders shortName directly; a blank one would print
            // a bare number with no app next to it (D35).
            assertTrue("shortName blank for ${spec.platform}", spec.shortName.isNotBlank())
        }
    }

    @Test
    fun `short names are distinct - the bubble breakdown must be unambiguous`() {
        val names = PlatformRegistry.enabled.map { it.shortName }
        assertEquals("duplicate shortName in $names", names.size, names.toSet().size)
    }

    @Test
    fun `an IDENTITY_CHANGE platform must have identity anchors (D34)`() {
        // Same shape of invariant as the ENFORCED/markers one below, and the same failure mode
        // it prevents: with no anchor, ReelIdentity finds nothing, every read is UNREADABLE,
        // and the platform silently counts ZERO instead of loudly breaking.
        PlatformRegistry.enabled
            .filter { it.advanceStrategy == AdvanceStrategy.IDENTITY_CHANGE }
            .forEach { spec ->
                assertTrue(
                    "IDENTITY_CHANGE ${spec.platform} must have identityAnchors or it counts nothing",
                    spec.identityAnchors.isNotEmpty(),
                )
            }
    }

    @Test
    fun `each platform uses the advance strategy its capture supports (D34)`() {
        // Instagram reports a real scrollDeltaY (calibrated 49/50, D11).
        assertEquals(
            AdvanceStrategy.DELTA_Y_FORWARD,
            PlatformRegistry.specFor(Platform.INSTAGRAM).advanceStrategy,
        )
        // YouTube Shorts reports deltaY=0 on every scroll; its per-Short signal is the identity
        // on CONTENT_CHANGED. Reverting this to DELTA_Y_FORWARD returns YT to counting ~zero.
        assertEquals(
            AdvanceStrategy.IDENTITY_CHANGE,
            PlatformRegistry.specFor(Platform.YOUTUBE).advanceStrategy,
        )
        assertTrue(PlatformRegistry.specFor(Platform.YOUTUBE).usesIdentityAdvance)
        assertFalse(PlatformRegistry.specFor(Platform.INSTAGRAM).usesIdentityAdvance)
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
    fun `exactly one platform may cover a screen, and it is Instagram (D49)`() {
        // THE safety net for the whole block feature. `blocksAtLimit` is the only thing standing
        // between a platform and a full-screen overlay on someone's phone, so what may hold it is
        // pinned here by name rather than left to whoever edits the registry next.
        val blocking = PlatformRegistry.enabled.filter { it.blocksAtLimit }
        assertEquals(
            "only Instagram may block; found ${blocking.map { it.platform }}",
            listOf(Platform.INSTAGRAM),
            blocking.map { it.platform },
        )
    }

    @Test
    fun `a platform may only block once its surface is device-verified (D19-D24-D49)`() {
        // The precondition the block was dormant on from D19 until D49. ENFORCED with real
        // markers means the block can land ONLY on the reel player — never the home feed, the
        // profile grid, DMs or Stories. Enabling the block on a PASSTHROUGH or SHADOW platform,
        // or on an ENFORCED one with empty markers, is how the app ends up covering a feed.
        PlatformRegistry.enabled
            .filter { it.blockEnabled }
            .forEach { spec ->
                assertEquals(
                    "${spec.platform} may not block without ENFORCED surface gating",
                    GatingMode.ENFORCED,
                    spec.gating,
                )
                assertTrue(
                    "${spec.platform} may not block with empty surface markers",
                    spec.surfaceMarkers.isNotEmpty(),
                )
            }
    }

    @Test
    fun `Instagram blocks on the toured clips_viewer marker and nothing else (D26-D49)`() {
        // The specific string the 2026-07-24 tour proved, kept explicit because the near-miss is
        // real: Stories are internally "reels" and emit reel_viewer_*, so a marker of
        // "reel_viewer" — or a broadened "reel" — would put the block over Stories. Every
        // sibling IG surface was NO_MATCH against this exact value.
        val instagram = PlatformRegistry.specFor(Platform.INSTAGRAM)
        assertTrue(instagram.blocksAtLimit)
        assertEquals(listOf("clips_viewer"), instagram.surfaceMarkers)
    }

    @Test
    fun `a BETA platform can never drive a limit or block, whatever blockEnabled says (D32)`() {
        // The invariant the whole Maturity flag exists for: we do not lock someone's screen on
        // a count we've admitted is wrong. This must hold even if a future edit flips
        // blockEnabled on a Beta platform, which is exactly the mistake it guards against.
        PlatformRegistry.enabled
            .filter { it.maturity == Maturity.BETA }
            .forEach { spec ->
                assertFalse(
                    "BETA ${spec.platform} must not be eligible to block",
                    spec.blocksAtLimit,
                )
                assertTrue("BETA ${spec.platform} should be badged in the UI", spec.isBeta)
            }
        // Stated positively too, so the day someone promotes YouTube they have to come here and
        // mean it: the three unmeasured platforms are false on BOTH counts, not just derived-false.
        listOf(Platform.YOUTUBE, Platform.TIKTOK, Platform.SNAPCHAT).forEach { platform ->
            val spec = PlatformRegistry.specFor(platform)
            assertFalse("$platform must not have blockEnabled set", spec.blockEnabled)
            assertFalse("$platform must not be eligible to block", spec.blocksAtLimit)
        }
    }

    @Test
    fun `only Instagram is calibrated - everything else ships BETA (D32)`() {
        // Instagram is the one platform calibrated against real swipes (49/50, D11).
        assertEquals(Maturity.STABLE, PlatformRegistry.specFor(Platform.INSTAGRAM).maturity)
        // YouTube: surface proven (D26) AND advance signal now resolved (IDENTITY_CHANGE, D34),
        // but not yet CALIBRATED — promotion to STABLE is gated on the two on-device acceptance
        // runs (15 swipes → 15 ±2, and 30s idle → no movement). Until those pass, the number is
        // unmeasured, and an unmeasured number does not get to lock someone's screen.
        assertEquals(Maturity.BETA, PlatformRegistry.specFor(Platform.YOUTUBE).maturity)
        // TikTok / Snapchat: never toured, SHADOW counts app-wide (Snapchat overcounts).
        assertEquals(Maturity.BETA, PlatformRegistry.specFor(Platform.TIKTOK).maturity)
        assertEquals(Maturity.BETA, PlatformRegistry.specFor(Platform.SNAPCHAT).maturity)
    }

    @Test
    fun `detectPlatform maps a tracked package and rejects an untracked one`() {
        assertEquals(Platform.INSTAGRAM, PlatformRegistry.detectPlatform(null, "com.instagram.android"))
        assertEquals(Platform.YOUTUBE, PlatformRegistry.detectPlatform(null, "com.google.android.youtube"))
        assertNull(PlatformRegistry.detectPlatform(null, "com.whatsapp"))
        assertNull(PlatformRegistry.detectPlatform(null, null))
    }

    @Test
    fun `a modified client resolves to the same platform (D52)`() {
        // ReVanced YouTube ships under its own package and was silently untracked until a capture
        // showed it going by. It is the SAME platform — one Shorts habit, one daily_counts row —
        // so both packages must land on Platform.YOUTUBE and share one spec.
        assertEquals(
            Platform.YOUTUBE,
            PlatformRegistry.detectPlatform(null, "app.revanced.android.youtube"),
        )
        assertEquals(
            PlatformRegistry.specFor(Platform.YOUTUBE),
            PlatformRegistry.forPackage("app.revanced.android.youtube"),
        )
        // The canonical package is unchanged, so aggregate rows and existing data are untouched.
        assertEquals("com.google.android.youtube", PlatformRegistry.specFor(Platform.YOUTUBE).packageName)
    }

    @Test
    fun `package names are unique across the whole registry`() {
        // Two specs claiming one package would make forPackage's firstOrNull order-dependent —
        // the same class of silent wrongness as a duplicated shortName, and much harder to see
        // now that a spec can declare several.
        val all = PlatformRegistry.enabled.flatMap { it.packageNames }
        assertEquals("duplicate package across specs in $all", all.size, all.toSet().size)
    }

    @Test
    fun `every spec declares at least one package`() {
        // packageName reads packageNames.first(); an empty list would throw at the first event
        // from any app, inside an AccessibilityService callback.
        PlatformRegistry.enabled.forEach { spec ->
            assertTrue("${spec.platform} has no packages", spec.packageNames.isNotEmpty())
            assertTrue(
                "${spec.platform} has a blank package",
                spec.packageNames.all { it.isNotBlank() },
            )
        }
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
