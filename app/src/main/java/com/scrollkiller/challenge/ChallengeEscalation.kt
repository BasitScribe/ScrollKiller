package com.scrollkiller.challenge

/**
 * HOW a challenge gets harder each time it is used to buy time (D83).
 *
 * ## The problem this exists for
 * Five days of real use said it plainly: an addicted user does not respect a static challenge.
 * Walk twenty steps is a genuine interruption the first time and muscle memory by the third block
 * of the day — you stand up, you pace the kitchen without looking up, you sit back down. Since
 * D77 deleted the free "5 more minutes", a completed challenge is the ONLY reprieve, which means
 * the challenge is the entire price of buying time. A price that never moves gets absorbed, and an
 * absorbed price is not a price.
 *
 * So the target escalates: each reprieve a challenge grants makes that challenge harder the next
 * time you reach for it. Nothing else about the mechanic changes — the reward stays flat, the
 * choice stays the user's, and both are load-bearing (see the reward note on [CAP_MULTIPLE]).
 *
 * ## Two curves, because the units are not alike
 * Doubling a count is brutal (jump 10 → 20 → 40 → 80 is a workout by the fourth block) while
 * doubling a hold is the natural shape for a duration. So counts step by [COUNT_STEP] and holds
 * double, which is what the two feel like from the inside rather than what is arithmetically
 * tidier. [EscalationCurve] is a property of the SPEC for the same reason
 * [ChallengeSpec.sensorStrategy] is: it is per-challenge data, not a branch on [ChallengeType].
 *
 * ## Rotation cannot dodge it — see [effectiveUses]
 * Escalation is charged partly to the challenge you used and partly to ALL of them, so cycling
 * through the set still raises the whole ladder while the one you lean on stays hardest.
 *
 * Pure Kotlin, no Android imports — like [ChallengeProgress] and
 * [com.scrollkiller.service.BlockLimits] — so the cap is unit-tested off-device rather than
 * discovered on a phone by a user who cannot finish it.
 */
enum class EscalationCurve {

    /**
     * Multiply by two per use: 30 → 60 → 120. The shape for a HOLD, where the ask is a duration
     * and "twice as long" is how anybody would describe the next rung.
     */
    DOUBLE,

    /**
     * Add [ChallengeEscalation.COUNT_STEP] per use: 20 → 30 → 40. The shape for a COUNT, where
     * doubling compounds far too fast — walk 20 would reach 160 in three reprieves, which is a
     * walk around the block rather than a break from the sofa.
     */
    PLUS_TEN,
}

object ChallengeEscalation {

    /**
     * The hardest a challenge may ever get, as a multiple of the spec's base target. FOUR.
     *
     * ## This constant is an invariant-6 device, not a difficulty knob
     * Uncapped escalation is uncapped in the only direction that matters: within a day of heavy
     * use, ×2 reaches numbers no human completes, and a challenge nobody can finish is a reprieve
     * that does not exist. The block would still be exitable — Exit and Back never read any of
     * this — but "the only way to buy time is now impossible" is the spirit of invariant 6 being
     * failed while its letter is kept, and the block screen is not the place to be clever about
     * that distinction.
     *
     * FOUR, applied uniformly, gives walk 80, jump 40 and both holds 120s. Two reasons for the
     * shared multiple rather than a `maxTarget` per spec: it is one number to reason about instead
     * of five, and a per-spec cap is a second figure that can drift from the base it is supposed
     * to bound (the same argument that keeps [CHALLENGE_GRACE_MS][com.scrollkiller.service.BlockLimits.CHALLENGE_GRACE_MS]
     * derived from its minutes).
     *
     * ## Why the HOLDS cap at two minutes specifically
     * D54's anti-cheat is that a broken hold RESETS rather than pauses. Failure probability
     * therefore compounds with duration — a four-minute face-down hold surrenders everything to
     * one twitch at 3:59, and the user cannot even see it happen because the ring is pointing at a
     * table. That is not strict, it reads as broken, and D9's anti-uninstall principle applies to
     * the mechanic and not just to the copy (D50 said so in as many words). Two minutes is a long
     * time to lie still holding nothing and is still finishable on the first attempt.
     *
     * ## What does NOT escalate
     * The reward. [com.scrollkiller.service.BlockLimits.CHALLENGE_GRACE_MINUTES] stays flat at 15
     * for every challenge at every rung, which is D53's call re-affirmed: pricing an escalated
     * hold above an escalated walk would re-punish precisely the user who picked walking because
     * they cannot jump. Escalation raises the PRICE of buying time over a day; it does not
     * re-rank the challenges against each other.
     */
    const val CAP_MULTIPLE = 4

    /** How much [EscalationCurve.PLUS_TEN] adds per use. */
    const val COUNT_STEP = 10

    /**
     * How many reprieves earned on OTHER challenges count as one use of this one. TWO.
     *
     * The knob that closes the rotation hole. See [effectiveUses] for what it buys and what it
     * deliberately does not.
     */
    const val GLOBAL_DIVISOR = 2

    /** The hardest [spec] may ever get. See [CAP_MULTIPLE]. */
    fun capFor(spec: ChallengeSpec): Int = spec.target * CAP_MULTIPLE

    /**
     * How many uses a challenge is charged for, given [ownUses] of its own and [otherUses] spread
     * across the rest of the set: its own, plus one for every [GLOBAL_DIVISOR] earned elsewhere.
     *
     * ## The hole this closes
     * Purely per-challenge state is dodgeable. Rotate walk → jump → face-down → forehead and every
     * one of them stays near its base, so the price of buying time stops climbing after the third
     * block of the day — which is precisely the absorption this whole file exists to prevent.
     *
     * Purely GLOBAL state closes it completely and costs too much: with one shared counter, both
     * holds sit pinned at their 120s cap from the SECOND reprieve of the day even if the user has
     * never done a hold, so the middle of every ladder stops existing and escalation collapses back
     * into the static-challenge-at-a-higher-number failure it replaced.
     *
     * The split takes the closure without the collapse. Rotating a full lap of four still raises
     * everything (walk 40, jump 30, both holds 120s) rather than nothing, so cycling is no longer a
     * discount — but the challenge actually being leaned on is always strictly the hardest, which is
     * the signal real use surfaced and the reason to keep per-challenge state at all.
     *
     * ## Why this ALSO fixed an equity mistake
     * Rotation dilution was only ever available to somebody who can physically do several
     * challenges. A user with no pedometer who cannot jump in a first-floor flat at 2am had exactly
     * one option and therefore no discount at all, while a user with all four got a fourfold one.
     * D83's first draft argued per-challenge state PROTECTED that user; it did the opposite, and
     * charging part of every reprieve to the whole set is what makes the rate the same for both.
     *
     * INTEGER division, and it rounds DOWN on purpose: the first reprieve elsewhere must be free
     * here, or a single completion would raise all four challenges at once and the model would be
     * pure-global with extra steps.
     */
    fun effectiveUses(ownUses: Int, otherUses: Int): Int {
        // Long, because Int.MAX_VALUE own plus half of Int.MAX_VALUE other overflows Int — and both
        // are read from persisted preferences, so neither is this code's data to trust.
        val own = ownUses.coerceAtLeast(0).toLong()
        val other = otherUses.coerceAtLeast(0).toLong()
        return (own + other / GLOBAL_DIVISOR).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * [spec]'s target after it has been charged [uses] uses — the EFFECTIVE count from
     * [effectiveUses], not a raw per-challenge tally. Production callers should go through
     * [escalated], which composes the two.
     *
     * Total by construction: [uses] is coerced non-negative and the result is coerced to
     * [capFor], so there is no value of [uses] — including a garbage preference, a restored
     * backup, or [Int.MAX_VALUE] — that can produce a target above the cap or below the base.
     * That totality is the point; the caller is a persisted counter and this is a screen covering
     * somebody else's app.
     *
     * [EscalationCurve.DOUBLE] is computed by MULTIPLYING UP TO THE CAP rather than by
     * `target shl uses`, because the shift overflows to nonsense (and to NEGATIVE nonsense, which
     * `coerceAtMost` would happily accept) at around 27 uses. Stopping at the cap cannot overflow
     * for the same reason [com.scrollkiller.service.BlockLimits.clampLimit] ranges before it
     * snaps: the bounded operation goes first.
     */
    fun targetFor(spec: ChallengeSpec, uses: Int): Int {
        val n = uses.coerceAtLeast(0)
        val cap = capFor(spec)
        return when (spec.escalation) {
            EscalationCurve.PLUS_TEN -> {
                // The multiply is bounded by the cap comparison, but `n` is caller data, so it is
                // done in Long to keep a large `uses` from wrapping before it can be clamped.
                val raised = spec.target + n.toLong() * COUNT_STEP
                raised.coerceAtMost(cap.toLong()).toInt()
            }
            EscalationCurve.DOUBLE -> {
                var value = spec.target
                repeat(n) {
                    if (value >= cap) return cap
                    value *= 2
                }
                value.coerceAtMost(cap)
            }
        }
    }

    /**
     * [spec] as it should be OFFERED right now — the same challenge, at its escalated target.
     *
     * @param ownUses reprieves [spec] itself has granted today.
     * @param otherUses reprieves granted today by every OTHER challenge. Charged at
     *   [GLOBAL_DIVISOR] to one, so rotating the set cannot dodge the ladder. Defaulted to zero so
     *   a test can exercise one curve in isolation; production reads both from the same single
     *   preferences snapshot, which matters — reading own and total separately could straddle a
     *   day rollover and produce an own of 0 against a nonzero total.
     *
     * Returning a whole spec rather than a bare Int is the load-bearing choice here. The target is
     * read in four places — the chooser row's label, the challenge panel's prompt, the ring's
     * label, and the [ChallengeProgress] the sensor counts into — and threading an "effective
     * target" alongside the spec to all four is exactly how the prompt ends up promising sixty
     * seconds while the engine counts thirty. Escalating ONCE, at the point the chooser is built,
     * means every one of those call sites keeps reading `spec.target` and none of them learns that
     * escalation exists. It is the same rule D50 applied to `promptRes` being a format string over
     * `target`, one level up.
     *
     * [ChallengeSpec.id] is deliberately unchanged, so `forId`, the surprise-me exclusion and
     * every log line still key on a stable identifier across rungs.
     */
    fun escalated(spec: ChallengeSpec, ownUses: Int, otherUses: Int = 0): ChallengeSpec =
        spec.copy(target = targetFor(spec, effectiveUses(ownUses, otherUses)))
}
