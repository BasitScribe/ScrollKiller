package com.scrollkiller.service

import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.DrawableRes
import com.scrollkiller.R

/**
 * The short-video platforms ScrollKiller knows how to detect.
 *
 * [id] is the wire/storage identifier and MUST match the `daily_counts.platform`
 * enum in docs/SCHEMA.md exactly — emitted events, Room rows, and server sync all
 * key off this string, so Room/sync stay platform-agnostic.
 */
enum class Platform(val id: String) {
    INSTAGRAM("instagram"),
    YOUTUBE("youtube"),
    SNAPCHAT("snapchat"),
    FACEBOOK("facebook"),
    TIKTOK("tiktok"),
}

/**
 * How a platform's "one item advanced" signal is derived from raw accessibility events.
 * Per-platform because the platforms genuinely disagree: what works on Instagram reports
 * nothing at all on YouTube Shorts (D34).
 *
 * - [DELTA_Y_FORWARD]: the platform reports a real vertical scroll delta. A fling's event
 *   burst is collapsed into one forward advance by [SwipeDetector]'s quiet-gap timer.
 *   Instagram, calibrated 49/50 (D11).
 * - [IDENTITY_CHANGE]: the platform reports NO usable scroll delta, but its
 *   `TYPE_WINDOW_CONTENT_CHANGED` events carry a per-item identity (a channel handle /
 *   title) that changes exactly once per advance. Count on the identity CHANGING, never on
 *   the event — the same item re-renders dozens of times while it plays. YouTube Shorts
 *   (D34); see [ReelIdentity] + [IdentityAdvanceDetector].
 * - [EVENT_PULSE]: one advance per marker-matched event, time-debounced. Declared but
 *   UNUSED — it is outcome (A) of the D31 capture decision table, kept named so a future
 *   platform picks its strategy from a written menu rather than inventing one. Only safe
 *   for a surface proven to emit exactly one event per advance, which no platform is today.
 */
enum class AdvanceStrategy { DELTA_Y_FORWARD, IDENTITY_CHANGE, EVENT_PULSE }

/**
 * How aggressively a platform's [PlatformSpec.surfaceMarkers] gate counting. This
 * exists so we can ENCODE candidate (device-unverified) markers without regressing a
 * platform whose detection already works, and without a wrong guess silently zeroing
 * out all counting. See D24.
 *
 * - [PASSTHROUGH]: surface markers are ignored for counting; anything matching the
 *   [PlatformSpec.containerHints] on the tracked package counts. This is the Phase-1
 *   behaviour (Instagram shipped here with empty markers).
 * - [SHADOW]: markers are EVALUATED and LOGGED (`SCROLL_DETECTED` / `SCROLL_IGNORED`),
 *   so we can validate a candidate against a real device without risk — but counting
 *   still happens regardless of the match. Use for a platform whose detection is
 *   already calibrated (Instagram) while we confirm the Reels-only discriminator.
 * - [ENFORCED]: counting happens ONLY when a marker matches. Use for a NEW platform
 *   whose markers are still guesses: a wrong guess then fails toward *undercount*
 *   (never matches → nothing counts) rather than counting the whole app's feed.
 *   REQUIRES non-empty [PlatformSpec.surfaceMarkers] (an ENFORCED spec with empty
 *   markers would pass through and count everything — asserted in tests).
 */
enum class GatingMode { PASSTHROUGH, SHADOW, ENFORCED }

/**
 * How much we trust a platform's *count* — which is a different question from how much we
 * trust its *surface markers* ([GatingMode]). A platform can be perfectly surface-gated
 * (counts only on its Shorts player, never the feed) and still produce a number we know is
 * wrong, because its per-item advance signal is unresolved.
 *
 * - [STABLE]: the count is calibrated against real swipes (Instagram: 49/50, D11). Eligible
 *   to drive a daily limit and the full-screen block.
 * - [BETA]: detected and counted, shown in the UI behind a "Beta" badge, but structurally
 *   INELIGIBLE to drive a limit or a block ([PlatformSpec.blocksAtLimit] is false regardless
 *   of [PlatformSpec.blockEnabled]). Use when the surface is proven but the advance signal
 *   is not — a wrong number must never lock someone out of their phone.
 *
 * Eligibility is DERIVED from this rather than being a separate flag on purpose: "we don't
 * trust the count" and "this count may enforce a limit" are the same decision, and splitting
 * them into two knobs invites a future edit that sets one and forgets the other. If a Beta
 * platform ever legitimately needs to count toward limits, split the flag deliberately and
 * record why. See D32.
 *
 * **That split has now been taken, once, deliberately — see
 * [PlatformSpec.blocksWhileUncalibrated] and D73.** [BETA] therefore no longer means "cannot
 * block"; it means "the count is not calibrated", which is what it always actually measured.
 */
enum class Maturity { STABLE, BETA }

/**
 * Everything the service needs to detect one platform. Adding a platform is data,
 * not code: inspect its short-video view tree, then append a [PlatformSpec] to
 * [PlatformRegistry.enabled].
 *
 * @param packageNames every package that IS this platform. A LIST rather than one string because
 *   modified clients are common among exactly the people this app is for — ReVanced YouTube ships
 *   as `app.revanced.android.youtube` and was invisible to us until D52. All variants share one
 *   [Platform], and therefore one row in `daily_counts`: a user's Shorts habit is one habit whether
 *   they watch it through Google's client or someone else's. The first entry is the CANONICAL one
 *   (see [packageName]). Every entry must be unique across the whole registry, or [forPackage]
 *   becomes order-dependent — asserted in tests.
 * @param containerHints SIMPLE class names (no package) of the scrolled short-video
 *   container, e.g. "RecyclerView", "ViewPager", "ViewPager2". Matched via
 *   [matchesContainer] on the simple name so a legacy support-library widget matches an
 *   AndroidX hint of the same name — YouTube Shorts scrolls an
 *   `android.support.v7.widget.RecyclerView`, which must still hit the `RecyclerView`
 *   hint (D27). Storing fully-qualified names here silently broke YouTube (see D27).
 * @param minAdvanceIntervalMs the debounce interval, whose exact meaning follows
 *   [advanceStrategy]. Under [AdvanceStrategy.DELTA_Y_FORWARD] it is the QUIET GAP — the
 *   minimum time with no DOWN event before the next DOWN is treated as a new advance. Under
 *   [AdvanceStrategy.IDENTITY_CHANGE] it is the FLOOR between two counted advances, so an
 *   identity that flaps can't produce two counts back-to-back (D34).
 * @param surfaceMarkers `viewIdResourceName` substrings that identify the *doom
 *   surface* (the Reels/Shorts viewer), as opposed to the feed/search/profile/DMs
 *   which share the same package. A node is "on the surface" if any node in its
 *   ancestor chain has a `viewIdResourceName` containing one of these. How they gate
 *   counting is controlled by [gating].
 * @param gating how [surfaceMarkers] gate counting for this platform (see [GatingMode]).
 * @param blockEnabled whether the full-screen block screen may fire for this platform.
 *   SEPARATE from [gating] on purpose: a platform can count on its surface long before
 *   we trust its markers enough to blackout the screen at the limit. TRUE for Instagram only
 *   (D49, after the D26 marker tour); false everywhere else, so the block stays dormant on a
 *   platform until its surface is device-verified (preserves D19's "never the feed"). See D24.
 * @param unitNoun what one advance is called for this platform ("reel", "short", …).
 *   Stored on each raw [com.scrollkiller.data.db.ScrollEvent] and shown in the UI.
 * @param displayName human label for the Apps dashboard. Kept here (not resolved via
 *   PackageManager) so we need no QUERY_ALL_PACKAGES permission — the tracked set is a
 *   small known list, so a Play-sensitive package query would be gratuitous.
 * @param shortName 2-letter label for the platform ("IG", "YT"). Separate from [displayName]
 *   because the bubble is a ~40dp pill over someone's video — "Instagram Reels" cannot appear
 *   there. Same no-QUERY_ALL_PACKAGES reasoning. See D35. Still the loggable/accessible short
 *   form; the overlay panel draws [brandName] beside [iconRes].
 * @param brandName the app's plain name ("Instagram", "YouTube") for the overlay panel's bar
 *   rows. A THIRD name field, and each of the three earns its place at a different size:
 *   [displayName] ("Instagram Reels") names the surface on the dashboard, [shortName] ("IG") is
 *   two characters for anywhere text barely fits, and this is what a person calls the app —
 *   which is what the panel needs, because [displayName] does not fit a ~240dp overlay at 11sp
 *   and "IG" proved illegible there (D40). It is a WORD, never a logo: naming an app is
 *   nominative use, reproducing its mark inside its own UI is not (D44).
 * @param iconRes monochrome glyph for the overlay panel's bar row (D40). A GENERIC icon per
 *   platform — a camera, a video player, a music note — NOT the platform's brand mark: the
 *   panel is drawn inside someone else's app, and a reproduced logo is a trademark question
 *   with no upside next to a bar that already carries the count. Tinted at draw time, so it
 *   must be a single-colour shape. 0 means "no icon", which falls back to a generic glyph
 *   rather than to blank space. The slot stays as-is for the day licensed marks arrive.
 * @param dailyLimit advances-per-day before the block screen escalates (when [blocksAtLimit]).
 *   The DEFAULT only — [com.scrollkiller.data.SettingsPrefs.dailyLimit] is what the overlay
 *   actually reads, and this is the value it falls back to before the user has ever touched the
 *   slider. See [BlockLimits] for the number and the range it may be moved within.
 * @param maturity how much the *count* is trusted (see [Maturity]). [Maturity.BETA] makes
 *   the platform ineligible to drive a limit or block no matter what [blockEnabled] says,
 *   unless [blocksWhileUncalibrated] is also set.
 * @param blocksWhileUncalibrated the deliberate override D32 said to add if it was ever needed:
 *   this platform may enforce its limit even though its count is [Maturity.BETA]. **A product
 *   decision that trades accuracy for coverage, and it is only defensible for a platform whose
 *   SURFACE is device-verified** — the risk it accepts is blocking a few items early or late,
 *   NOT blocking on the wrong screen. Set for YouTube by owner decision (D73); YouTube's
 *   `reel_recycler` marker was toured on 2026-07-24 (D26) and it is [GatingMode.ENFORCED], so the
 *   block can still only land on the Shorts player. It stays [isBeta] for the UI badge, because
 *   the count really is uncalibrated and the badge is the honest disclosure of that.
 *
 *   This must NEVER be set on a [GatingMode.SHADOW] platform — TikTok counts app-wide and
 *   Snapchat actively overcounts Chat/Stories/Map as "snaps", so an override there would cover a
 *   screen on a number that is wrong about *what it counted*, not merely by how much.
 *   `PlatformRegistryTest` enforces both halves of that rule.
 * @param advanceStrategy how one advance is derived from events (see [AdvanceStrategy]).
 * @param identityAnchors `viewIdResourceName` substrings naming the PER-ITEM container to
 *   anchor identity extraction on, for [AdvanceStrategy.IDENTITY_CHANGE] platforms. Order is
 *   preference innermost-first; the upward walk in [ReelIdentity] naturally reaches the page
 *   container before the recycler that holds three of them. REQUIRED (asserted in tests) for
 *   an IDENTITY_CHANGE platform: with no anchor nothing is ever extracted, so it would count
 *   a silent zero. See D34.
 * @param identityTitleHints short view-ids (the part after `/`) whose text is allowed to be
 *   used as a FALLBACK identity when no channel handle is found. Deliberately an allowlist,
 *   not "any text": the Shorts player fires ~40 content-changes per item for subtitles, like
 *   counts and "Auto-dubbed" badges, and accepting those as identity is exactly the overcount
 *   the identity check exists to prevent. See D34 and [ReelIdentity.pick].
 */
data class PlatformSpec(
    val platform: Platform,
    val packageNames: List<String>,
    val containerHints: List<String>,
    val minAdvanceIntervalMs: Long,
    val surfaceMarkers: List<String> = emptyList(),
    val gating: GatingMode = GatingMode.PASSTHROUGH,
    val blockEnabled: Boolean = false,
    val unitNoun: String = "reel",
    val displayName: String = "",
    val shortName: String = "",
    val brandName: String = "",
    @DrawableRes val iconRes: Int = 0,
    val dailyLimit: Int = BlockLimits.DEFAULT_DAILY_LIMIT,
    val advanceStrategy: AdvanceStrategy = AdvanceStrategy.DELTA_Y_FORWARD,
    val identityAnchors: List<String> = emptyList(),
    val identityTitleHints: List<String> = emptyList(),
    val maturity: Maturity = Maturity.STABLE,
    val blocksWhileUncalibrated: Boolean = false,
) {
    /**
     * The CANONICAL package for this platform — the first entry, and the one recorded on aggregate
     * rows. Kept so the many call sites that only need "which app is this, roughly" did not all
     * have to learn about variants when [packageNames] became a list (D52).
     */
    val packageName: String get() = packageNames.first()

    /** True when this platform drops counts off-surface (a wrong marker undercounts). */
    val enforcesSurface: Boolean get() = gating == GatingMode.ENFORCED

    /**
     * True when advances come from [AdvanceStrategy.IDENTITY_CHANGE] — i.e. from
     * `TYPE_WINDOW_CONTENT_CHANGED` identity transitions, not from scroll deltas.
     *
     * Two things in the service key off this, and both must stay in step: the content-changed
     * counting path only runs for such a platform, and D29's landing-item entry credit is
     * SUPPRESSED for it (the identity path already counts the item you land on, so crediting
     * again would double it). See D34.
     */
    val usesIdentityAdvance: Boolean get() = advanceStrategy == AdvanceStrategy.IDENTITY_CHANGE

    /**
     * May this platform's count actually trigger the full-screen block? This — NOT the raw
     * [blockEnabled] field — is what the overlay must ask.
     *
     * Two independent things have to be true: the block is switched on for the platform, AND the
     * number is either trusted ([Maturity.STABLE]) or explicitly cleared to enforce while
     * uncalibrated ([blocksWhileUncalibrated], D73).
     *
     * The override is written as a SECOND named condition rather than by flipping [maturity],
     * because the two questions genuinely came apart here: "is this count calibrated" (no — it
     * drives the Beta badge, and saying otherwise in the UI would be a false claim) and "may this
     * count enforce a limit" (yes — the owner decided the coverage is worth the error bar). What
     * D32 forbade was one knob silently answering both; what it prescribed, if they ever diverged,
     * was exactly this — split them and record why.
     *
     * A BETA platform with no override still counts and still shows in the UI, and still can never
     * lock the screen on a number we've admitted is wrong. See D32 and D73.
     */
    val blocksAtLimit: Boolean
        get() = blockEnabled && (maturity == Maturity.STABLE || blocksWhileUncalibrated)

    /** True when the UI should badge this platform as Beta (its count isn't calibrated). */
    val isBeta: Boolean get() = maturity == Maturity.BETA

    /**
     * Does [className] name one of this platform's scroll containers? Compared on the SIMPLE
     * class name (after the last '.') so a legacy support-library widget matches an AndroidX
     * hint of the same name. Fully-qualified `endsWith` matching (the old approach) silently
     * failed YouTube Shorts, whose container is `android.support.v7.widget.RecyclerView` — it
     * never `endsWith("androidx.recyclerview.widget.RecyclerView")`. See D27.
     */
    fun matchesContainer(className: CharSequence?): Boolean {
        val simple = className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            ?: return false
        return containerHints.any { it == simple }
    }
}

/**
 * The single source of truth for which platforms are active.
 *
 * Instagram is fully calibrated (Phase 1). YouTube Shorts / TikTok / Snapchat are
 * SCAFFOLDED here in Phase 2: their specs are wired end-to-end but ship [GatingMode.ENFORCED]
 * with *candidate* (device-unverified) markers, so before the on-device tour confirms
 * them they can only ever UNDERcount — never count a home feed. Fill/replace the marker
 * strings from a labeled SurfaceDiagnostics tour, then flip [PlatformSpec.blockEnabled].
 *
 * The service is NOT scoped by a `packageNames` allowlist in
 * res/xml/accessibility_service_config.xml (removed so window-state-changed events for
 * the app the user switches TO are delivered — how the overlay bubble hides on leaving
 * a tracked app; see D16). [forPackage] is therefore the SOLE gate: events from
 * untracked apps return null here and are dropped.
 */
object PlatformRegistry {

    private val instagram = PlatformSpec(
        platform = Platform.INSTAGRAM,
        packageNames = listOf("com.instagram.android"),
        // The reel pager. Field-observed on the shipping IG build: the real advance signal
        // (non-zero scrollDeltaY) comes from the ViewPager v1 (`ViewPager`, simple name). The
        // inner RecyclerView fires alongside but always reports deltaY=0 (settle noise). See D15.
        containerHints = listOf("ViewPager", "ViewPager2", "RecyclerView"),
        minAdvanceIntervalMs = 200L,
        // VERIFIED marker (surface tour, 2026-07-24 — D26). Reels emit
        // srcId=clips_viewer_view_pager → MATCH(clips_viewer); every sibling surface is
        // NO_MATCH: home feed (swipeable_tab_view_pager), profile grid (clips_grid_recyclerview),
        // DMs (sticky_header_list / bottom_sheet_container), Stories (reel_viewer_* — internally
        // "reels", which is exactly why we key on "clips_viewer" ONLY, not "reel_viewer"). The
        // discriminator is proven and non-colliding, so IG is ENFORCED: it counts ONLY on the
        // reel surface.
        surfaceMarkers = listOf("clips_viewer"),
        gating = GatingMode.ENFORCED,
        // LIVE as of D49 — the first and only platform allowed to cover someone's screen. Both
        // preconditions the block has been dormant on since D19 are met, and BOTH are required:
        //   1. the surface marker above is device-TOURED and locked (D26), so the block can only
        //      ever land on the reel player — never the feed, DMs, profile grid or Stories; and
        //   2. the count is CALIBRATED 49/50 (D11), which is what keeps `maturity` at STABLE and
        //      therefore what makes [blocksAtLimit] true rather than just [blockEnabled].
        // Flipping this for a platform missing either one is the mistake `PlatformRegistryTest`
        // exists to catch. YouTube/TikTok/Snapchat are BETA and stay false on both counts.
        blockEnabled = true,
        unitNoun = "reel",
        displayName = "Instagram Reels",
        shortName = "IG",
        brandName = "Instagram",
        iconRes = R.drawable.ic_platform_instagram,
        // IG reports a real scrollDeltaY, so the calibrated D11 quiet-gap debounce stands.
        // Untouched by D34 — that change is scoped to platforms whose delta is dead.
        advanceStrategy = AdvanceStrategy.DELTA_Y_FORWARD,
    )

    private val youtube = PlatformSpec(
        platform = Platform.YOUTUBE,
        // Stock YouTube first (canonical), then ReVanced — a modified client that is common
        // among exactly our target users and was silently untracked until a capture showed
        // `pkg=app.revanced.android.youtube` going by (D52). Same markers, same IDENTITY_CHANGE
        // strategy, same spec: it IS YouTube Shorts, just built by someone else.
        //
        // The ReVanced client's Shorts markers are NOT separately device-verified. That is safe to
        // ship untoured precisely because YT is ENFORCED — an unmatched `reel_recycler`
        // UNDERCOUNTS rather than counting a home feed. Note this is now load-bearing in a way it
        // was not before D73: YouTube CAN cover the screen, so a marker miss on ReVanced means the
        // block never fires there, never that it fires on the wrong screen.
        packageNames = listOf("com.google.android.youtube", "app.revanced.android.youtube"),
        // Simple names (D27). YouTube Shorts scrolls the LEGACY support-library RecyclerView
        // (event class `android.support.v7.widget.RecyclerView`) — the old fully-qualified
        // `endsWith` hint never matched it, so Shorts counted ZERO (D27). Simple-name matching
        // fixes that. The follow-up question D27 raised — does Shorts emit a usable scroll
        // DIRECTION? — is now ANSWERED, and the answer is no: every Shorts scroll reports
        // deltaY=0 → SAME (D34). These hints therefore no longer drive counting for YT; they
        // are kept because the scroll path still runs (surface/overlay signal) and because a
        // future YT build could start reporting a real delta.
        containerHints = listOf("ViewPager2", "RecyclerView"),
        // FLOOR, not a quiet gap: under IDENTITY_CHANGE this is the minimum time between two
        // COUNTED advances, so an identity that flaps can't produce two counts back-to-back.
        // ~500ms is well under a realistic swipe cadence and well over a render flap (D34).
        minAdvanceIntervalMs = 500L,
        // VERIFIED marker (surface tour, 2026-07-24 — D26): the full-screen Shorts player
        // recycler emits srcId=reel_recycler → MATCH(reel_recycler). Narrowed to this ONE
        // proven id: the unverified `shorts_*` guesses were dropped because the home feed
        // carries a Shorts SHELF whose ids could false-match under ENFORCED (D28). Kept
        // ENFORCED so a miss undercounts, never counts the home/subscriptions feed.
        surfaceMarkers = listOf("reel_recycler"),
        gating = GatingMode.ENFORCED,
        // LIVE as of D73 — the second platform allowed to cover someone's screen, by owner
        // decision. D57 had closed YouTube at BETA for v1 on the grounds that the 15±2-swipe and
        // 30s-idle acceptance runs were never produced; that is still true, and the decision is to
        // ship the block anyway rather than leave a self-declared doomscrolling surface unenforced
        // for want of a calibration run. See `blocksWhileUncalibrated` below for what that trades.
        blockEnabled = true,
        unitNoun = "short",
        displayName = "YouTube Shorts",
        shortName = "YT",
        brandName = "YouTube",
        iconRes = R.drawable.ic_platform_youtube,
        // D34: the D31 capture landed and it was conclusive — outcome (C). SCROLLED is dead
        // (deltaY=0 on every Shorts event), but CONTENT_CHANGED on the Shorts player carries a
        // per-Short identity (channel handle in the node's contentDescription, plus the title)
        // that changes exactly ONCE per advance. So YT counts on identity CHANGE, not on events.
        advanceStrategy = AdvanceStrategy.IDENTITY_CHANGE,
        // Anchor innermost-first. `reel_player_page_container` is the ONE-Short container and is
        // the right anchor; `reel_recycler` is the last-resort fallback and is deliberately
        // second — it holds ~3 pages (prev/current/next), so anchoring there is ambiguous and
        // only acceptable when the page container isn't in the ancestry.
        identityAnchors = listOf("reel_player_page_container", "reel_recycler"),
        // Fallback ONLY — the handle (`@SagarsKitchen`) is the primary identity and is what the
        // capture proved. These title ids are NOT device-verified; that is tolerable precisely
        // because handle-first means they are consulted only when no handle exists at all.
        identityTitleHints = listOf("reel_title", "reel_video_title"),
        // STILL BETA, and that is not a formality (D32/D34/D73). IDENTITY_CHANGE is implemented but
        // has never been calibrated on a device: the two acceptance runs — 15 swipes landing 15 ±2,
        // and 30s idle on one Short moving the count by zero — remain unrun. So the count carries
        // an unmeasured error bar, the UI keeps its Beta badge saying so, and promotion to STABLE
        // still waits on those runs. What CHANGED at D73 is only that BETA no longer implies "may
        // not block"; see the override directly below.
        maturity = Maturity.BETA,
        // The D32 escape hatch, taken deliberately (D73). YouTube enforces its limit despite the
        // uncalibrated count. What this risks is bounded and worth naming: the block may land a few
        // Shorts early or late, and an identity that flaps could in principle double-count. What it
        // does NOT risk is the block landing on the wrong screen — that is held by ENFORCED gating
        // on the toured `reel_recycler` marker (D26), which is unchanged. If the idle-flap case
        // turns out to be real on a device, the fix is the calibration run, not removing this flag.
        blocksWhileUncalibrated = true,
    )

    private val tiktok = PlatformSpec(
        platform = Platform.TIKTOK,
        // Global TikTok. NOTE: the spec's "com.ss.android.ugc.tiktok" is not a real
        // package — global is com.zhiliaoapp.musically; regional variants are
        // com.ss.android.ugc.trill / com.ss.android.ugc.aweme (add specs if targeting them).
        packageNames = listOf("com.zhiliaoapp.musically"),
        containerHints = listOf("ViewPager2", "RecyclerView"),
        minAdvanceIntervalMs = 200L,
        // NOT TOURED (D28). TikTok ids are heavily obfuscated and these markers are pure
        // guesses. Left in SHADOW rather than shipping a guessed ENFORCED marker: SHADOW
        // evaluates + LOGS the marker check on a real device (so a future tour can confirm the
        // discriminator) while counting still happens, so we don't silently zero TikTok on a
        // wrong guess. TRADEOFF: SHADOW counts app-wide container scrolls (TikTok is ~all FYP,
        // so this is largely fine); blockEnabled stays false so nothing is enforced on the user.
        surfaceMarkers = listOf("feed_container", "feed_recyclerview", "feed_pager"),
        gating = GatingMode.SHADOW,
        blockEnabled = false,
        unitNoun = "short",
        displayName = "TikTok",
        shortName = "TT",
        brandName = "TikTok",
        iconRes = R.drawable.ic_platform_tiktok,
        // BETA (D32): never toured, SHADOW counts app-wide. Mostly-FYP so it's roughly right,
        // but "roughly right" is not a number we lock a screen on.
        maturity = Maturity.BETA,
    )

    private val snapchat = PlatformSpec(
        platform = Platform.SNAPCHAT,
        packageNames = listOf("com.snapchat.android"),
        containerHints = listOf("ViewPager2", "RecyclerView"),
        minAdvanceIntervalMs = 200L,
        // NOT TOURED (D28). "spotlight" is a plausible but unverified guess. Left in SHADOW
        // (see TikTok note). KNOWN TRADEOFF: unlike TikTok, Snapchat is NOT mostly-Spotlight —
        // SHADOW will also count Chat/Stories/Map container scrolls as "snaps" (overcount)
        // until a device tour confirms the Spotlight discriminator and this flips to ENFORCED.
        // Acceptable for now: blockEnabled is false, so nothing is enforced on the user.
        surfaceMarkers = listOf("spotlight"),
        gating = GatingMode.SHADOW,
        blockEnabled = false,
        unitNoun = "snap",
        displayName = "Snapchat Spotlight",
        shortName = "SC",
        brandName = "Snapchat",
        iconRes = R.drawable.ic_platform_snapchat,
        // BETA (D32): never toured, and unlike TikTok it's NOT mostly-Spotlight — SHADOW also
        // counts Chat/Stories/Map scrolls as "snaps", a known OVERcount. The worst possible
        // input to a limit, so it is barred from driving one.
        maturity = Maturity.BETA,
    )

    /** Platforms detected today. Instagram is calibrated; the rest are scaffolded (D24). */
    val enabled: List<PlatformSpec> = listOf(instagram, youtube, tiktok, snapchat)

    /** Spec whose package produced this event, or null if it's not a tracked app. */
    fun forPackage(packageName: CharSequence?): PlatformSpec? {
        val pkg = packageName?.toString() ?: return null
        return enabled.firstOrNull { pkg in it.packageNames }
    }

    /**
     * The tracked [Platform] this event's package belongs to, or null if untracked.
     *
     * This is the spec's `detectPlatform(node, packageName)` in the codebase's own
     * vocabulary: package identity is the authoritative, cheap platform signal. [node]
     * is accepted for parity with the spec and future content heuristics (e.g. if a
     * package hosts more than one detectable surface) — unused today.
     */
    @Suppress("UNUSED_PARAMETER")
    fun detectPlatform(node: AccessibilityNodeInfo?, packageName: CharSequence?): Platform? =
        forPackage(packageName)?.platform

    /** Spec for a known [Platform]. Every enabled platform has exactly one spec. */
    fun specFor(platform: Platform): PlatformSpec =
        enabled.first { it.platform == platform }

    /**
     * Spec for [platform], or null when it is declared in the [Platform] enum but not [enabled]
     * — Facebook today. Use this anywhere a [Platform] arrives from data rather than from the
     * registry: [specFor] throws there, and a rendering path is never the right place to crash.
     */
    fun specOrNull(platform: Platform): PlatformSpec? =
        enabled.firstOrNull { it.platform == platform }

    /** Tracked packages. Kept for diagnostics and any future re-scoping. */
    val packageNames: List<String> get() = enabled.flatMap { it.packageNames }
}

/**
 * Decides whether a node tree is currently showing a platform's *doom surface*
 * (its Reels/Shorts viewer) using [PlatformSpec.surfaceMarkers]. This is the single
 * predicate behind "user is doomscrolling right now"; how its result gates counting
 * depends on [PlatformSpec.gating] (the service applies the mode, not this object).
 *
 * Pass-through when a spec has no markers yet (pre-evidence): returns `true` so the
 * app keeps its prior behaviour until markers are filled in.
 *
 * All walks are bounded and recycle the nodes they allocate (correct on API 26–32;
 * `recycle()` is a harmless no-op on 33+). Nodes passed IN are owned by the caller.
 */
object SurfaceMatcher {

    /** Depth cap on the ancestor walk from a scrolled node. */
    private const val MAX_ANCESTORS = 30

    /** Node-count cap on the window scan so a huge tree can't stall the main thread. */
    private const val MAX_SCAN_NODES = 400

    /**
     * Is [node] (a scrolled view) inside the doom surface? Checks the node itself and
     * every ancestor for a `viewIdResourceName` containing any marker. The scrolled
     * node's ancestry is the cheap, authoritative signal — we already hold the node.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun matchesSurface(node: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean {
        if (spec.surfaceMarkers.isEmpty()) return true   // no markers → not gating yet
        if (node == null) return false

        if (idMatches(node.viewIdResourceName, spec)) return true

        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTORS) {
            if (idMatches(current.viewIdResourceName, spec)) {
                current.recycle()
                return true
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        return false
    }

    /**
     * Does the active-window [root] tree contain the doom surface anywhere? Used on
     * window-STATE changes (rare — activity/fragment transitions) to catch entering
     * the viewer without scrolling. Bounded DFS; NOT run per content-change.
     *
     * This is the spec's `isSurfaceCountable(rootNode, platform)`.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun windowMatchesSurface(root: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean {
        if (spec.surfaceMarkers.isEmpty()) return true   // no markers → not gating yet
        if (root == null) return false

        // Iterative DFS over freshly-allocated child nodes; recycle each after use.
        // The root belongs to the caller, so we never recycle it here.
        var scanned = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        if (idMatches(root.viewIdResourceName, spec)) return true
        pushChildren(root, stack)
        while (stack.isNotEmpty() && scanned < MAX_SCAN_NODES) {
            val n = stack.removeLast()
            scanned++
            if (idMatches(n.viewIdResourceName, spec)) {
                n.recycle()
                stack.forEach { it.recycle() }
                return true
            }
            pushChildren(n, stack)
            n.recycle()
        }
        stack.forEach { it.recycle() }
        return false
    }

    /** Alias matching the Phase-2 spec's name; delegates to [windowMatchesSurface]. */
    fun isSurfaceCountable(root: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean =
        windowMatchesSurface(root, spec)

    /** Alias for the spec's `findResourceIdInHierarchy`: does [node]'s ancestry contain a marker? */
    fun findResourceIdInHierarchy(node: AccessibilityNodeInfo?, spec: PlatformSpec): Boolean =
        matchesSurface(node, spec)

    /**
     * The actual `viewIdResourceName` (self or nearest ancestor) that matched a marker, or
     * null. Same ancestry walk as [matchesSurface] — this is purely for the DEBUG decision
     * log ("which container triggered it"), so we can name the id we keyed on.
     */
    @Suppress("DEPRECATION") // recycle() is correct on API 26–32; a no-op on 33+.
    fun matchedMarker(node: AccessibilityNodeInfo?, spec: PlatformSpec): String? {
        if (node == null || spec.surfaceMarkers.isEmpty()) return null
        firstMatchingId(node.viewIdResourceName, spec)?.let { return it }

        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTORS) {
            val hit = firstMatchingId(current.viewIdResourceName, spec)
            val parent = current.parent
            current.recycle()
            if (hit != null) return hit
            current = parent
            depth++
        }
        return null
    }

    /**
     * Pure string-level predicate: the first id in [ids] that contains one of [spec]'s
     * markers, or null. Android-free so the surface-discrimination logic (Reels vs Home vs
     * Stories, Shorts vs feed) can be unit-tested off-device with representative id lists.
     */
    fun matchedMarkerId(ids: List<String>, spec: PlatformSpec): String? =
        ids.firstOrNull { id -> spec.surfaceMarkers.any { id.contains(it) } }

    private fun firstMatchingId(id: CharSequence?, spec: PlatformSpec): String? {
        val value = id?.toString() ?: return null
        return if (spec.surfaceMarkers.any { value.contains(it) }) value else null
    }

    private fun pushChildren(node: AccessibilityNodeInfo, stack: ArrayDeque<AccessibilityNodeInfo>) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { stack.addLast(it) }
        }
    }

    private fun idMatches(id: CharSequence?, spec: PlatformSpec): Boolean {
        val value = id?.toString() ?: return false
        return spec.surfaceMarkers.any { value.contains(it) }
    }
}
