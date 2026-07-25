package com.scrollkiller.data

import android.os.SystemClock
import android.util.Log
import com.scrollkiller.BuildConfig
import com.scrollkiller.service.Platform

/**
 * DEBUG-only stopwatch across the whole count pipeline, so "the number updates late" is
 * answered with a measurement instead of a guess (D39).
 *
 * It times ONE advance end to end, through the four places latency can hide:
 *
 * ```
 *  DETECT      the detector said "advance" (SwipeDetector / IdentityAdvanceDetector)
 *    ↓ optimistic        in-memory counter bumped, synchronously, on the caller's thread
 *  OPTIMISTIC
 *    ↓ room              two SQLite commits on Dispatchers.IO (increment + raw event)
 *  PERSISTED
 *    ↓ invalidation      Room's InvalidationTracker re-runs the query and the Flow emits
 *  EMITTED               the overlay's collector got a new TodaySummary on the main thread
 *    ↓ layout            one message-loop turn: the TextView is measured and drawn
 *  RENDERED
 * ```
 *
 * The deltas separate the suspects the session brief listed: DETECT is already after the
 * debounce (so a large DETECT→RENDERED with a small everything-else means the debounce is the
 * cost, not this pipeline), OPTIMISTIC→PERSISTED is the DB write, PERSISTED→EMITTED is Room's
 * invalidation round trip, and EMITTED→RENDERED is the view.
 *
 * ## One open trace at a time, on purpose
 * A [begin] supersedes any trace still open. This is a hand-driven diagnostic — you swipe once
 * and read one line — not a metrics system, and keeping a map keyed by sequence would mean
 * threading an id through [TodaySummary] and into the overlay just to serve DEBUG builds. The
 * superseded trace is logged as `SUPERSEDED` so a swipe that never rendered is still visible in
 * the transcript rather than silently vanishing.
 *
 * Zero cost in release: every entry point returns immediately on [BuildConfig.DEBUG], and the
 * only state is four longs.
 *
 * Read it with: `adb logcat -s ScrollKiller | grep LATENCY`
 */
object CountLatency {

    private val enabled = BuildConfig.DEBUG

    /** Monotonic clock — not wall time, so an NTP correction mid-swipe can't produce a negative. */
    private fun now(): Long = SystemClock.elapsedRealtime()

    private var seq = 0
    private var platform: Platform? = null
    private var detectedAt = 0L
    private var optimisticAt = 0L
    private var persistedAt = 0L
    private var emittedAt = 0L

    /** UNSET as a sentinel so "stage never happened" prints as `-` instead of a bogus 0ms. */
    private const val UNSET = 0L

    /**
     * A detector just said "advance" on [platform]. Opens a trace, superseding any open one.
     * Called from the accessibility service's main thread.
     */
    @Synchronized
    fun begin(platform: Platform) {
        if (!enabled) return
        if (detectedAt != UNSET) log("SUPERSEDED")
        seq++
        this.platform = platform
        detectedAt = now()
        optimisticAt = UNSET
        persistedAt = UNSET
        emittedAt = UNSET
    }

    /** The in-memory count moved (the number the user can now be shown). */
    @Synchronized
    fun optimistic() {
        if (!enabled || detectedAt == UNSET) return
        optimisticAt = now()
    }

    /** Both Room writes committed. */
    @Synchronized
    fun persisted() {
        if (!enabled || detectedAt == UNSET) return
        persistedAt = now()
    }

    /** A new summary reached the overlay's collector on the main thread. */
    @Synchronized
    fun emitted() {
        if (!enabled || detectedAt == UNSET) return
        // First emission only: the optimistic and the Room emission are separate arrivals, and
        // the FIRST is the one the user sees the number change on. Overwriting here would report
        // the Room round trip as the user-visible latency even after the optimistic path fixed it.
        if (emittedAt == UNSET) emittedAt = now()
    }

    /** The view was measured/drawn with the new number. Closes and prints the trace. */
    @Synchronized
    fun rendered() {
        if (!enabled || detectedAt == UNSET) return
        log("OK")
        detectedAt = UNSET
    }

    private fun log(outcome: String) {
        val end = now()
        Log.d(
            TAG,
            "LATENCY seq=$seq platform=${platform?.id} $outcome" +
                " detect→optimistic=${delta(optimisticAt)}" +
                " detect→persisted=${delta(persistedAt)}" +
                " detect→emitted=${delta(emittedAt)}" +
                " detect→rendered=${end - detectedAt}ms" +
                " | write=${span(optimisticAt, persistedAt)}" +
                " roomEmit=${span(persistedAt, emittedAt)}" +
                " layout=${span(emittedAt, end)}",
        )
    }

    private fun delta(at: Long): String = if (at == UNSET) "-" else "${at - detectedAt}ms"

    private fun span(from: Long, to: Long): String =
        if (from == UNSET || to == UNSET) "-" else "${to - from}ms"

    private const val TAG = "ScrollKiller"
}
