package com.scrollkiller

import com.scrollkiller.service.Platform
import com.scrollkiller.service.PlatformRegistry
import com.scrollkiller.service.SurfaceMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Surface discrimination, tested off-device with representative `viewIdResourceName`
 * ancestor chains for each surface. This documents the EXPECTED ids per surface and guards
 * the "Reels/Shorts count, feed/stories don't" contract at the pure-logic level (the on-
 * device tour then confirms the real ids — see SurfaceDiagnostics / D24).
 */
class SurfaceMatcherTest {

    private val instagram = PlatformRegistry.specFor(Platform.INSTAGRAM)
    private val youtube = PlatformRegistry.specFor(Platform.YOUTUBE)

    // --- Instagram: only Reels ("clips") should match ---

    @Test
    fun `instagram reels matches`() {
        val reels = listOf(
            "android:id/content",
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
        )
        assertEquals("com.instagram.android:id/clips_viewer_view_pager", SurfaceMatcher.matchedMarkerId(reels, instagram))
    }

    @Test
    fun `instagram home feed is ignored`() {
        val feed = listOf(
            "android:id/content",
            "com.instagram.android:id/feed_recyclerview",
            "com.instagram.android:id/row_feed_photo_imageview",
        )
        assertNull(SurfaceMatcher.matchedMarkerId(feed, instagram))
    }

    @Test
    fun `instagram stories are ignored (reel_viewer must not false-match)`() {
        // Stories are internally "reels" and use reel_viewer-style ids. We key on "clips_viewer"
        // ONLY, so these must NOT match — the reason reel_viewer was dropped from the markers.
        val stories = listOf(
            "android:id/content",
            "com.instagram.android:id/reel_viewer_root",
            "com.instagram.android:id/reel_viewer_media_container",
        )
        assertNull(SurfaceMatcher.matchedMarkerId(stories, instagram))
    }

    // --- YouTube: only Shorts should match, not the home/search feed ---

    @Test
    fun `youtube shorts matches`() {
        val shorts = listOf(
            "android:id/content",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/shorts_player",
        )
        assertEquals("com.google.android.youtube:id/reel_recycler", SurfaceMatcher.matchedMarkerId(shorts, youtube))
    }

    @Test
    fun `youtube feed is ignored`() {
        val feed = listOf(
            "android:id/content",
            "com.google.android.youtube:id/results",
            "com.google.android.youtube:id/watch_list",
        )
        assertNull(SurfaceMatcher.matchedMarkerId(feed, youtube))
    }
}
