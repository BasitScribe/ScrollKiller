package com.scrollkiller.service

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.scrollkiller.BuildConfig

/**
 * DEBUG-only YouTube Shorts capture probe. Pure instrumentation: it observes and prints, it
 * NEVER decides anything — every `branch=` it reports is threaded IN from the service, never
 * re-derived here, so the transcript cannot drift from the logic it describes.
 *
 * Its first job (D31) was discovery — make YouTube's event behaviour self-evident in logcat so
 * the strategy was chosen from numbers rather than a guess, the same evidence-first loop that
 * solved Instagram in D11/D26/D27. That worked: the capture picked
 * [AdvanceStrategy.IDENTITY_CHANGE] (D34). Its job now is ACCEPTANCE — proving the shipped
 * identity logic counts 15 swipes as 15 and a 30s idle as 0. It stays for the next platform.
 *
 * ## What it prints
 * One `YTPROBE` line per observed YouTube event, carrying:
 *  - `type=` SCROLLED | CONTENT_CHANGED | WINDOW_STATE
 *  - `eventTime=` the raw `AccessibilityEvent.getEventTime()` (plus `dtMs=` since the previous
 *    probe line, as a convenience — the raw stamp is there so gaps can be measured independently)
 *  - the full YTFIELDS set ([SurfaceDiagnostics.logScrollFields]): from/to/itemCount, scroll/
 *    maxScroll, deltaX/Y, src text/description, and `identity=` (candidate per-Short identity text)
 *  - `branch=` the ACTUAL debounce decision this event took in
 *    [ReelScrollAccessibilityService.onScrolled], and `why=` the condition that produced it
 *  - `ytEventsSeen=N ytCounted=N` — monotonic counters, reset per capture, so both numbers can be
 *    watched moving (or failing to move) in real time.
 *
 * ## Which events are probed (and why it isn't literally "marker-matched only")
 * The ask was "every event where pkg==YouTube AND the marker matches reel_recycler". Taken
 * literally, two of the four branches could never appear: `markerMatched` is computed as
 * `isContainer && SurfaceMatcher.matchesSurface(...)`, so a marker-matched event is by
 * construction never `rejected-container`, and never `rejected-surface` either. To keep the
 * branch column meaningful the filter is widened by exactly as much as that requires:
 *  - SCROLLED — EVERY YouTube scroll is probed (they only fire while something actually scrolls,
 *    so there is no flood risk), with `marker=MATCH(...)|NO_MATCH` on the line. Grep
 *    `marker=MATCH` to get the literal marker-matched subset back. These no longer COUNT for YT
 *    (deltaY is dead), but they are still worth watching — a future YT build could revive them.
 *  - CONTENT_CHANGED — probed ONLY when the marker matches, and only for the events that
 *    survived the service's own rate limit (it returns before probing otherwise). This is now
 *    YT's real counting path, so these lines are the acceptance evidence.
 *  - WINDOW_STATE — every YouTube window-state change (rare; gives surface transitions context).
 *
 * ## Cost control
 * `identity=` normally costs nothing now: the shipping path already computed it and passes it
 * in. The bounded subtree DISCOVERY scan (`id:text` pairs) only runs for event types the
 * shipping path never touches, and stays throttled to [IDENTITY_THROTTLE_MS] there — an
 * unthrottled scan risks slowing the service enough to DROP events, which would corrupt the
 * very evidence being collected. Throttled lines print `identity=(throttled)`.
 *
 * Privacy/Play: [BuildConfig.DEBUG]-gated, exactly like [SurfaceDiagnostics] — the whole class is
 * compiled out of release. `identity=` is the one place we read node text, and it is a temporary,
 * developer-run discovery tool on the operator's own device (same posture recorded on
 * [SurfaceDiagnostics.logScrollFields]). Shipping detection still reads structural metadata only.
 */
@Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle() is correct on API 26–32; a no-op on 33+.
internal object YtProbe {

    /** Same tag as everything else, so one filter catches the whole transcript. */
    private const val TAG = "ScrollKiller"

    /** Minimum gap between `identity=` subtree scans on CONTENT_CHANGED events. */
    private const val IDENTITY_THROTTLE_MS = 200L

    /**
     * The decision the event actually took in the service. These map 1:1 onto the real branches of
     * [ReelScrollAccessibilityService.onScrolled] — if that method changes, these must change with
     * it, or the transcript starts lying about the logic it claims to describe.
     */
    enum class Branch(val label: String, val counts: Boolean = false) {
        /** `detector.onScroll(...)` returned true → `repository.record(...)` ran. */
        COUNTED("counted", counts = true),

        /** Container + surface both passed, but the DOWN fell inside the quiet gap. */
        DEBOUNCED_QUIET_GAP("debounced-quiet-gap"),

        /** `spec.matchesContainer(className)` was false — never reached the detector. */
        REJECTED_CONTAINER("rejected-container"),

        /** ENFORCED gating and the surface marker did not match — never reached the detector. */
        REJECTED_SURFACE("rejected-surface"),

        /** IDENTITY_CHANGE: the identity differed from the last counted one → Room incremented. */
        IDENTITY_COUNTED("identity-counted", counts = true),

        /** IDENTITY_CHANGE: same identity as last counted — the Short is just re-rendering. */
        IDENTITY_UNCHANGED("identity-unchanged"),

        /** IDENTITY_CHANGE: nothing provably per-Short in the tree → ignored (never counted). */
        IDENTITY_UNREADABLE("identity-unreadable"),

        /** IDENTITY_CHANGE: identity changed but landed inside the [PlatformSpec.minAdvanceIntervalMs] floor. */
        IDENTITY_FLOORED("identity-floored"),

        /** Event type that drives no counting for this platform (SCROLLED on YT / WINDOW_STATE). */
        PROBE_ONLY("probe-only-no-counting-path"),
    }

    enum class Kind { SCROLLED, CONTENT_CHANGED, WINDOW_STATE }

    /** Every probe line emitted since the last [reset]. */
    private var eventsSeen = 0L

    /** Every probe line that actually incremented a Room count (advance or landing-Short entry). */
    private var counted = 0L

    /** `eventTime` of the previous probe line, for the `dtMs=` convenience column. */
    private var lastEventTime = 0L

    /** Last time `identity=` was scanned, for the CONTENT_CHANGED throttle. */
    private var lastIdentityAtMs = 0L

    /**
     * Start a capture: zero the counters and print the decision table. Triggered by the existing
     * DEBUG tour broadcast when the label starts with `YTPROBE` (see the capture commands), so the
     * table is stamped into the same transcript being read — the logic is right there next to the
     * numbers instead of being remembered.
     */
    fun reset(label: String) {
        if (!BuildConfig.DEBUG) return
        eventsSeen = 0
        counted = 0
        lastEventTime = 0
        lastIdentityAtMs = 0
        DECISION_TABLE.lineSequence().forEach { Log.d(TAG, "YTPROBE| $it") }
        Log.d(TAG, "YTPROBE| CAPTURE: $label — counters zeroed. Every line below belongs to this capture.")
        Log.d(TAG, "YTPROBE| ============================================================")
    }

    /**
     * Emit one probe line.
     *
     * @param marker whether this event's node ancestry matched a `surfaceMarkers` entry.
     * @param branch the decision the service ACTUALLY took for this event.
     * @param why the concrete condition behind [branch], in the service's own vocabulary.
     * @param entryCredited true when this event also triggered the D29 landing-Short entry credit
     *   (a second, separate `repository.record` that is NOT the debounce path) — surfaced because
     *   otherwise `ytCounted` would move by one for no visible reason. Always false for an
     *   [AdvanceStrategy.IDENTITY_CHANGE] platform, where the entry credit is suppressed (D34).
     * @param identity the identity [ReelIdentity] ACTUALLY extracted for this event, when the
     *   shipping identity path ran. Printed in preference to the discovery scan below, so the
     *   transcript shows the value the detector saw rather than a second, different scan.
     * @param source owned by the CALLER and NOT recycled here.
     */
    fun log(
        kind: Kind,
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo?,
        spec: PlatformSpec,
        marker: Boolean,
        branch: Branch,
        why: String,
        entryCredited: Boolean = false,
        identity: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return

        eventsSeen++
        if (branch.counts) counted++
        if (entryCredited) counted++

        val dt = if (lastEventTime == 0L) -1 else event.eventTime - lastEventTime
        lastEventTime = event.eventTime

        Log.d(
            TAG,
            "YTPROBE type=$kind eventTime=${event.eventTime} dtMs=$dt " +
                "ytEventsSeen=$eventsSeen ytCounted=$counted " +
                "branch=${branch.label} why=\"$why\" entryCredit=$entryCredited " +
                "marker=${if (marker) "MATCH(${spec.surfaceMarkers.joinToString("|")})" else "NO_MATCH"} " +
                "container=${spec.matchesContainer(event.className)} " +
                "src=${event.className?.toString()?.substringAfterLast('.') ?: "?"} " +
                "srcId=${source?.viewIdResourceName?.substringAfterLast('/') ?: "—"} " +
                "changeTypes=${changeTypes(kind, event)} " +
                fields(kind, event, source, identity),
        )
    }

    /**
     * The YTFIELDS payload. `getScrollDelta{X,Y}()` are API 28+, guarded (`n/a` on 26/27).
     *
     * `identity=` prints [shipped] — the value [ReelIdentity] actually produced — whenever the
     * shipping identity path ran. It falls back to the old bounded DISCOVERY scan (`id:text`
     * pairs) only for event types that path never touches, where it is still the tool for
     * finding a new platform's identifier. The discovery scan is throttled because it can fire
     * many times a second; the shipped value costs nothing, it is already computed.
     */
    private fun fields(
        kind: Kind,
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo?,
        shipped: String?,
    ): String {
        val delta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "deltaX=${event.scrollDeltaX} deltaY=${event.scrollDeltaY}"
        } else {
            "deltaX=n/a deltaY=n/a"
        }
        val now = System.currentTimeMillis()
        val identity = when {
            shipped != null -> shipped
            kind == Kind.CONTENT_CHANGED && now - lastIdentityAtMs < IDENTITY_THROTTLE_MS -> "(throttled)"
            else -> {
                lastIdentityAtMs = now
                collectIdentity(source)
            }
        }
        return "from=${event.fromIndex} to=${event.toIndex} itemCount=${event.itemCount} " +
            "scrollX=${event.scrollX} scrollY=${event.scrollY} " +
            "maxScrollX=${event.maxScrollX} maxScrollY=${event.maxScrollY} $delta " +
            "srcDesc=\"${trim(source?.contentDescription)}\" srcText=\"${trim(source?.text)}\" " +
            "identity=\"$identity\""
    }

    /**
     * Decode `contentChangeTypes` — the field that says WHAT changed (subtree vs text vs
     * description). If CONTENT_CHANGED turns out to be YouTube's per-Short signal, this is what
     * separates "the video advanced" from "the like count re-rendered". Only the API-26 constants
     * are decoded by name; anything else is reported as a raw mask so nothing is silently lost.
     */
    private fun changeTypes(kind: Kind, event: AccessibilityEvent): String {
        if (kind != Kind.CONTENT_CHANGED) return "—"
        val mask = event.contentChangeTypes
        if (mask == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) return "UNDEFINED(0)"
        val names = buildList {
            if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0) add("SUBTREE")
            if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) add("TEXT")
            if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0) add("CONTENT_DESC")
        }
        return if (names.isEmpty()) "raw=0x${Integer.toHexString(mask)}" else "${names.joinToString("+")}(0x${Integer.toHexString(mask)})"
    }

    // --- identity scan (bounded) -----------------------------------------------------------

    private const val MAX_IDENTITY_NODES = 60
    private const val MAX_IDENTITY_DEPTH = 6
    private const val MAX_IDENTITY_TEXTS = 4

    /**
     * Bounded DFS collecting up to [MAX_IDENTITY_TEXTS] non-blank text/contentDescription strings
     * from the event's subtree — the candidate "which Short is this" fingerprint. Each string is
     * prefixed with the id of the node carrying it (`id:text`), because the outcome that matters
     * is not the text but WHICH NODE holds a stable per-Short identifier. Children allocated here
     * are recycled; [source] belongs to the caller.
     */
    private fun collectIdentity(source: AccessibilityNodeInfo?): String {
        if (source == null) return "—"
        val out = ArrayList<String>(MAX_IDENTITY_TEXTS)
        collect(source, intArrayOf(MAX_IDENTITY_NODES), depth = 0, out = out)
        return if (out.isEmpty()) "—" else out.joinToString(" | ")
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        budget: IntArray,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (budget[0] <= 0 || depth > MAX_IDENTITY_DEPTH || out.size >= MAX_IDENTITY_TEXTS) return
        budget[0]--
        val label = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString()?.takeIf { it.isNotBlank() }
        if (label != null) {
            val id = node.viewIdResourceName?.substringAfterLast('/') ?: "—"
            out.add("$id:${trim(label)}")
        }
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0 || out.size >= MAX_IDENTITY_TEXTS) break
            val child = node.getChild(i) ?: continue
            collect(child, budget, depth + 1, out)
            child.recycle()
        }
    }

    private fun trim(cs: CharSequence?): String {
        val s = cs?.toString() ?: return "—"
        return if (s.length > 40) s.take(40) + "…" else s
    }

    /**
     * The read-it-against-the-logic decision table, printed at the top of every capture. Written
     * here (not in a doc) on purpose: the operator reads the outcome against the shipped branches
     * in the same transcript, so no one has to trust memory about what the numbers mean.
     */
    private val DECISION_TABLE = """
        ============================================================
        YTPROBE — YouTube Shorts, IDENTITY_CHANGE acceptance capture

        The earlier capture round settled it: outcome (C). TYPE_VIEW_SCROLLED is DEAD on
        Shorts (deltaY=0 on every event -> SAME -> debounced), and the real per-Short signal
        is TYPE_WINDOW_CONTENT_CHANGED, which carries a channel handle that changes exactly
        once per advance while firing ~40x per Short.

        SHIPPED LOGIC NOW UNDER TEST (D34) — YT runs advanceStrategy=IDENTITY_CHANGE:
          1. CONTENT_CHANGED from pkg=YouTube only; rate-limited to one evaluation per
             IDENTITY_SCAN_MIN_MS before ANY node work is done.
          2. must still match surfaceMarkers ("reel_recycler") on the node ancestry.
          3. ReelIdentity anchors on identityAnchors (reel_player_page_container, then
             reel_recycler) and takes the leading @handle, else the text of an
             identityTitleHints node, else NOTHING.
          4. IdentityAdvanceDetector counts only when that identity DIFFERS from the last
             counted one, and only outside the minAdvanceIntervalMs floor (500ms).
          5. D29's landing-Short entry credit is SUPPRESSED for YT — step 4 already counts
             the Short you land on, so crediting again would double it.

        COLUMNS
          type          SCROLLED | CONTENT_CHANGED | WINDOW_STATE
          eventTime     raw AccessibilityEvent.getEventTime(); dtMs = gap vs previous probe line
          identity      the value ReelIdentity ACTUALLY produced (not a separate scan)
          branch        the decision the service ACTUALLY took for this event:
                          identity-counted          -> identity changed, Room incremented
                          identity-unchanged        -> same Short re-rendering (expected: the bulk)
                          identity-unreadable       -> nothing provably per-Short -> ignored
                          identity-floored          -> changed, but inside the 500ms floor
                          counted / debounced-quiet-gap / rejected-container / rejected-surface
                                                    -> the DELTA_Y_FORWARD scroll path (IG's)
                          probe-only-no-counting-path-> this event drives no counting for YT
          entryCredit   D29 landing credit. MUST be false everywhere for YT now.
          ytEventsSeen  every probe line in this capture
          ytCounted     every probe line that incremented Room

        TWO ACCEPTANCE RUNS — both must pass before YT leaves BETA:
        (1) YTPROBE_SWIPE — swipe 15 Shorts. ytCounted must land 15 +/-2, with roughly one
            identity-counted per swipe and identity-unchanged for everything between.
        (2) YTPROBE_IDLE  — sit on ONE Short for 30s, no swiping. ytCounted must NOT move.
            Expect a stream of identity-unchanged; the like-count and subtitle content
            changes must NOT produce identity-counted. This is the exact overcount the
            identity check exists to prevent.

        FAILURE READING
          count too HIGH in (2) -> identity is not stable: read the identity= column for what
            it flapped to, and tighten ReelIdentity (usually a title hint matching too much).
          count too LOW in (1)  -> identity-unreadable dominates: the anchor was not found, or
            the handle is not where the capture said. Check srcId= and the identity= values.
        ============================================================
    """.trimIndent()
}
