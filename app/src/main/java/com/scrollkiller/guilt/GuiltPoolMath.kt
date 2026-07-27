package com.scrollkiller.guilt

/**
 * How much content the cadence actually EATS, and therefore how big each tier's pool has to be
 * for the 7-day no-repeat rule (D47) to hold rather than to fall through to its fallback.
 *
 * ## Why this is code and not a comment
 * The answer changes whenever [GuiltCadence.SCHEDULE] or [GuiltTier]'s thresholds change, and a
 * number written into a doc would be wrong the first time either is tuned. So it is DERIVED, by
 * running the real [GuiltFiring] over a simulated day — the same class the app fires from, not a
 * reimplementation of it. If the two could drift, the whole exercise would be theatre.
 *
 * It is used twice: a test pins the shipped numbers so the pack cannot silently fall behind the
 * cadence, and the runtime quotes it in the warning it logs when a pool is exhausted — so the
 * log says "this tier needs ~126 lines and has 18" instead of "pool exhausted".
 *
 * ## The finding, at the numbers this ships with (post-D48)
 * Tier 4 absorbs essentially the entire cadence, because it starts at 150 and never ends. Fires
 * per day, and in brackets the 7-day pool that implies:
 *
 * | scrolls/day | T1 | T2 |   T3   |     T4     |
 * |-------------|----|----|--------|------------|
 * | 150         |  1 |  1 |  5 (35)|    1    (7)|
 * | 300         |  1 |  1 |  5 (35)|   18  (126)|
 * | 500         |  1 |  1 |  5 (35)|   57  (399)|
 * | 800         |  1 |  1 |  5 (35)|  117  (819)|
 *
 * Tiers 1 and 2 fire ONCE each per day by construction (they have no repeating interval — see
 * [GuiltCadence.intervalFor]), so seven lines each sustains a week and the shipped 18 is ample.
 * Tier 3 spans exactly 100–149 at every 10 scrolls, so it is capped at 5/day forever: 35 lines
 * sustains a week for ANY user, which is why its target is a reachable 40. Tier 4 is unbounded
 * and is where the content problem lives — see [EXPANSION_TARGETS] for what we actually commit to.
 *
 * ## What D48's flattening bought
 * Under the original schedule (tightening to every 2 at 600 and every scroll at 800) tier 4 ate
 * 196/day at 800 scrolls — a 1,372-line week. Flattening to [GuiltCadence.FLOOR_SCROLLS] cuts
 * that to 117/day and 819. Still more than anyone will write, which is the honest finding: the
 * flattening does not make the top of the curve affordable, it makes the MIDDLE of the curve
 * affordable. That is where the users are, and it is why the tier-4 target below is set against
 * a ~320/day user rather than against the ceiling.
 *
 * Pure Kotlin, no Android imports.
 */
object GuiltPoolMath {

    /** The no-repeat window, in days. The rule D47 exists to enforce. */
    const val NO_REPEAT_DAYS = 7

    /**
     * How many lines each tier is COMMITTED to hold. The content bill, as a number the build
     * tracks rather than a sentence in an ADR nobody re-reads.
     *
     * These are targets, not the cadence's demands — [requiredPool] gives those and for tier 4 it
     * is unbounded. A target is the point at which we stop authoring and accept the
     * least-recently-shown fallback above it, so each one is a deliberate choice of WHO is
     * covered:
     *
     *  - **T1/T2 — 18.** Already met. One fire/day means 7 would do; the shipped 18 covers
     *    everyone forever and nobody needs to write another one.
     *  - **T3 — 40.** Closes the tier PERMANENTLY. Consumption is capped at 5/day by the band's
     *    finite width, so 35 sustains a week for any user at any scroll rate and 40 leaves room.
     *    The cheapest complete win in the pack: 22 lines and that tier is never discussed again.
     *  - **T4 — 150.** Sustains a 7-day no-repeat for a user doing ~320 scrolls/day, which is the
     *    realistic heavy user. Above that the fallback engages and [GuiltLines] logs it loudly
     *    with the count that triggered it, so the real distribution of who overruns this is
     *    measured rather than guessed. Chasing 800/day would mean 819 lines; see the class doc.
     *
     * Not enforced as a build failure — see `GuiltPoolMathTest`, which asserts the CURRENT
     * shortfall so it stays green today and fails the moment the pack lands, rather than blocking
     * every unrelated change until the content exists.
     */
    val EXPANSION_TARGETS: Map<GuiltTier, Int> = mapOf(
        GuiltTier.MILD to 18,
        GuiltTier.MEDIUM to 18,
        GuiltTier.STRONG to 40,
        GuiltTier.EXTREME to 150,
    )

    /** [EXPANSION_TARGETS] for [tier]. Separate accessor so call sites cannot get a null. */
    fun targetPool(tier: GuiltTier): Int = EXPANSION_TARGETS.getValue(tier)

    /**
     * How many lines each tier consumes in a day that reaches [scrollsPerDay].
     *
     * Simulated with the real [GuiltFiring], scroll by scroll, with the clock advanced past
     * [GuiltCadence.MIN_GAP_MS] every step — i.e. the display gap never binds. That is the RIGHT
     * assumption for sizing: the gap only trims consumption when someone scrolls faster than one
     * swipe per ~5.5s sustained, so ignoring it gives the worst case a pool must survive, and a
     * pool sized for the worst case is the point.
     */
    fun firesPerDay(scrollsPerDay: Int): Map<GuiltTier, Int> {
        val firing = GuiltFiring()
        val fires = HashMap<GuiltTier, Int>()
        var now = 0L
        for (count in 0..scrollsPerDay) {
            now += GuiltCadence.MIN_GAP_MS + 1
            val tier = GuiltTier.forCount(count)
            if (firing.onCount(count, tier, DAY_KEY, now) && tier != null) {
                fires[tier] = (fires[tier] ?: 0) + 1
            }
        }
        return fires
    }

    /**
     * How many lines [tier] needs for a user who does [scrollsPerDay] to go [days] without
     * seeing a repeat. Zero when the tier never fires at that rate.
     */
    fun requiredPool(tier: GuiltTier, scrollsPerDay: Int, days: Int = NO_REPEAT_DAYS): Int =
        (firesPerDay(scrollsPerDay)[tier] ?: 0) * days

    /**
     * The largest daily scroll count at which a pool of [poolSize] still sustains the full
     * window for [tier] — i.e. what this tier's content is actually good for today.
     *
     * Answers the question the pack author has ("18 lines covers whom?") rather than the one the
     * engine has ("how many do I need?"), which is why it exists alongside [requiredPool].
     * Searched rather than solved because consumption is a step function of the schedule, not a
     * formula. Returns 0 if even a minimal day overruns the pool.
     */
    fun sustainedScrollsPerDay(tier: GuiltTier, poolSize: Int, days: Int = NO_REPEAT_DAYS): Int {
        var best = 0
        var scrolls = 0
        while (scrolls <= SEARCH_CEILING) {
            if (requiredPool(tier, scrolls, days) <= poolSize) best = scrolls else return best
            scrolls += SEARCH_STEP
        }
        return best
    }

    /** Any fixed key: [firesPerDay] simulates ONE day, so the value never matters, only that it
     *  is constant (a change would re-baseline [GuiltFiring] mid-simulation). */
    private const val DAY_KEY = "sim"

    /** Past this the answer is "more than anyone scrolls"; keeps the search bounded. */
    private const val SEARCH_CEILING = 2_000

    /** Coarse enough to stay cheap, fine enough to be a useful figure to quote. */
    private const val SEARCH_STEP = 10
}
