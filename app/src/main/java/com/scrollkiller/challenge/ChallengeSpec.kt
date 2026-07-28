package com.scrollkiller.challenge

import androidx.annotation.StringRes
import com.scrollkiller.R
import kotlin.random.Random

/**
 * WHAT the user physically does to earn their way past the block.
 *
 * Separate from [SensorStrategy] on purpose, and for exactly the reason
 * [com.scrollkiller.service.PlatformSpec] splits `platform` from `advanceStrategy`: what the user
 * DOES and how we MEASURE it are different questions, and they do not map one-to-one. [WALK]
 * already needs two measurement paths depending on what the device offers (see [StepSensorSource]),
 * and [JUMP] and [FACE_DOWN] both read the accelerometer while being nothing alike to perform.
 *
 * All four are now wired — the suite is complete. The declared-but-unbuilt discipline that got it
 * here (a written menu the next challenge picks from, with [ChallengeRegistry.IMPLEMENTED] stating
 * the gap honestly and a test enforcing it) is still the rule for whatever comes next; the enum is
 * simply, for the moment, fully implemented.
 */
enum class ChallengeType { WALK, JUMP, FACE_DOWN, FOREHEAD }

/**
 * HOW a challenge's progress is measured from sensor data.
 *
 * - [STEP_EVENTS]: one event per step from `TYPE_STEP_DETECTOR`. Counted directly. The preferred
 *   path for [ChallengeType.WALK] — it is low-latency and inherently incremental, which is what a
 *   live progress ring needs.
 * - [STEP_CUMULATIVE]: `TYPE_STEP_COUNTER`, which reports steps since BOOT and is shared with every
 *   other app on the device. Progress is a delta from a baseline taken at challenge start. The
 *   fallback for devices that ship a counter but no detector.
 * - [ACCEL_PEAKS]: `TYPE_ACCELEROMETER` magnitude, counting the free-fall→landing SEQUENCE that
 *   distinguishes a jump from a shake. Needs no runtime permission, unlike the step sensors — see
 *   [JumpDetector] for the decisions and [ChallengeAvailability] for what that changes.
 * - [ORIENTATION_HOLD]: `TYPE_ACCELEROMETER` Z axis, timing how long the screen has faced the floor.
 *   Progress is ELAPSED TIME and it RESETS on a break rather than pausing — see [HoldDetector] for
 *   why that is the anti-cheat and not merely strictness. Needs no runtime permission.
 * - [PROXIMITY_HOLD]: `TYPE_PROXIMITY` **and** `TYPE_ACCELEROMETER` together — covered AND upright.
 *   Its own strategy rather than a flavour of [ORIENTATION_HOLD] deliberately: sharing would have let
 *   the forehead challenge into [ChallengeRegistry.IMPLEMENTED] for free the moment face-down landed,
 *   which is exactly the gate the registry exists to hold shut. Also the only strategy needing two
 *   sensors, so it is the only one whose availability can fail on hardware alone — proximity is
 *   near-universal but not guaranteed. See [ProximityHoldSource].
 *
 * [ChallengeRegistry.IMPLEMENTED] names which of these the engine actually honours, and a test
 * asserts every enabled spec uses one — so a spec added without its sensor code fails the build
 * instead of shipping a challenge that can never complete.
 */
enum class SensorStrategy {
    STEP_EVENTS,
    STEP_CUMULATIVE,
    ACCEL_PEAKS,
    ORIENTATION_HOLD,
    PROXIMITY_HOLD,
}

/**
 * What a challenge's [ChallengeSpec.target] is measured IN, and therefore what the ring's centre
 * label says. Pure Kotlin so the label is unit-testable and lives in exactly one place.
 *
 * The ARC fills in both cases. Only the label differs — a draining arc beside a filling one, for the
 * same reward, would read as a different kind of thing.
 */
enum class ProgressUnit {

    /** Discrete things done. Label counts UP: `7 / 20`. */
    COUNT,

    /**
     * Seconds held. Label counts DOWN — `18s` — because "how much longer" is the only question
     * someone mid-hold is asking, and on a face-down phone they cannot see it anyway; it is what they
     * read the instant they flip up to check.
     */
    SECONDS,
    ;

    /** The ring's centre text for [progress] of [target]. */
    fun ringLabel(progress: Int, target: Int): String = when (this) {
        COUNT -> "$progress / $target"
        SECONDS -> "${(target - progress).coerceAtLeast(0)}s"
    }
}

/**
 * One physical challenge, as DATA. Adding the next one is a spec here plus a sensor strategy —
 * not a rewrite — which is the whole point of the shape (D50).
 *
 * Modelled on [com.scrollkiller.service.PlatformSpec] deliberately, down to the registry beneath
 * it: that file proved the pattern works for a thing whose per-instance details are genuinely
 * different but whose plumbing is identical.
 *
 * @param id stable identifier. Used for logs and, later, for remembering which challenge a user
 *   prefers; it is NOT a wire value today, but keep it stable anyway.
 * @param type what the user physically does. See [ChallengeType].
 * @param target how many of the thing (steps, jumps, seconds) completes it. Always > 0.
 * @param sensorStrategy how [target] is measured. See [SensorStrategy]. For [ChallengeType.WALK]
 *   this is the PREFERENCE — [StepSensorSource] downgrades to [SensorStrategy.STEP_CUMULATIVE] at
 *   runtime on a device with no step detector, because the spec cannot know the hardware.
 * @param promptRes the instruction shown on the challenge screen, with [target] as its single
 *   format argument. A format string rather than baked copy so the sentence cannot drift from the
 *   number — the same rule D49 applied to the snooze button's minutes.
 * @param unit what [target] counts, and therefore what the ring's label says. Defaults to
 *   [ProgressUnit.COUNT] because that is what every counting challenge wants and it keeps the two
 *   original specs unchanged.
 */
data class ChallengeSpec(
    val id: String,
    val type: ChallengeType,
    val target: Int,
    val sensorStrategy: SensorStrategy,
    @StringRes val promptRes: Int,
    val unit: ProgressUnit = ProgressUnit.COUNT,
)

/**
 * The single source of truth for which challenges exist.
 *
 * Two today. The registry shipped with one anyway — for the same reason the guilt-pack picker
 * shipped with a single option (D43): a list that grows is a much better introduction than a control
 * that appears from nowhere the day a second entry lands. That bet paid off here; adding JUMP was a
 * spec plus a sensor source, and the chooser it feeds needed no new plumbing.
 */
object ChallengeRegistry {

    /**
     * Walk twenty steps.
     *
     * Twenty is a deliberate figure. It is far enough to require standing up and leaving the sofa —
     * which is the ENTIRE mechanism, since the point is to break the scroll trance rather than to
     * exercise anyone — and short enough that it stays a reprieve rather than a punishment. A
     * challenge people resent is a challenge people uninstall (D9's anti-uninstall principle
     * applies to the mechanic, not just the copy).
     */
    private val walk = ChallengeSpec(
        id = "walk_20",
        type = ChallengeType.WALK,
        target = 20,
        sensorStrategy = SensorStrategy.STEP_EVENTS,
        promptRes = R.string.challenge_walk_prompt,
    )

    /**
     * Jump ten times.
     *
     * Ten, against WALK's twenty, because the two are not the same kind of effort. Twenty steps is a
     * stroll to the kitchen; ten jumps is genuinely aerobic, and the point of both is to break the
     * scroll trance rather than to exercise anyone. A challenge people resent is a challenge people
     * uninstall (D9's anti-uninstall principle applies to the mechanic, not just the copy), and
     * twenty jumps would be resented.
     *
     * It exists mainly to be the option WALK is not: it needs no permission, no pedometer, and about
     * four square feet — so it works on the train, in a queue, and on the many devices with no step
     * sensor at all.
     */
    private val jump = ChallengeSpec(
        id = "jump_10",
        type = ChallengeType.JUMP,
        target = 10,
        sensorStrategy = SensorStrategy.ACCEL_PEAKS,
        promptRes = R.string.challenge_jump_prompt,
    )

    /**
     * Put the phone face down for thirty seconds.
     *
     * The one challenge that asks for NOTHING physical — no space, no noise, no standing up — which is
     * exactly its job in the set. Walk needs a room, jump needs a floor nobody lives under; this
     * works in a lecture, on a bus, and at 2am. It is the option that means "I can't do the others"
     * never equals "I can't earn my way out".
     *
     * Thirty seconds because the mechanic is breaking the trance, not endurance: it is long enough
     * that you stop scrolling and look at the room, short enough that it stays a reprieve. And it is
     * the ONLY challenge you cannot do while still watching the reel, which is worth more than its
     * effort suggests.
     *
     * Breaking it RESETS to zero rather than pausing (see [HoldDetector]) — otherwise it would be six
     * five-second flips with a peek between each, which is not a break at all.
     */
    private val faceDown = ChallengeSpec(
        id = "face_down_30",
        type = ChallengeType.FACE_DOWN,
        target = 30,
        sensorStrategy = SensorStrategy.ORIENTATION_HOLD,
        promptRes = R.string.challenge_face_down_prompt,
        unit = ProgressUnit.SECONDS,
    )

    /**
     * Hold the phone against your forehead for thirty seconds.
     *
     * The deliberately absurd one, and that is its job. Walk, jump and face-down are all things you
     * might plausibly be doing anyway; standing with a phone pressed to your head for half a minute
     * is not, and it is the only challenge that makes you feel faintly ridiculous. That is the
     * mechanism — a moment of "what am I doing" is a sharper interruption of a scroll trance than any
     * amount of counting, and it is the one the guilt pack cannot deliver.
     *
     * Requires proximity AND upright ([ProximityHoldSource]): covered alone is a thumb on a table.
     * Same thirty seconds and the same reset-on-break as face-down.
     */
    private val forehead = ChallengeSpec(
        id = "forehead_30",
        type = ChallengeType.FOREHEAD,
        target = 30,
        sensorStrategy = SensorStrategy.PROXIMITY_HOLD,
        promptRes = R.string.challenge_forehead_prompt,
        unit = ProgressUnit.SECONDS,
    )

    /** Challenges offered today. Order is the order the chooser lists them in. */
    val enabled: List<ChallengeSpec> = listOf(walk, jump, faceDown, forehead)

    /**
     * The strategies the engine actually implements. The gap between this and [SensorStrategy]'s
     * full set is the honest statement of what is declared but not yet built, and
     * `ChallengeRegistryTest` asserts every enabled spec falls inside it.
     */
    val IMPLEMENTED: Set<SensorStrategy> = setOf(
        SensorStrategy.STEP_EVENTS,
        SensorStrategy.STEP_CUMULATIVE,
        SensorStrategy.ACCEL_PEAKS,
        SensorStrategy.ORIENTATION_HOLD,
        SensorStrategy.PROXIMITY_HOLD,
    )

    /**
     * The first enabled spec.
     *
     * No longer what the block screen offers — the user picks now (D53), and picking for them was
     * only ever defensible while there was one option. Kept for callers that need *a* spec without
     * an opinion, and for the tests that pin the list's order.
     */
    val default: ChallengeSpec? get() = enabled.firstOrNull()

    fun forId(id: String): ChallengeSpec? = enabled.firstOrNull { it.id == id }

    /**
     * Pick one at random from [candidates] — the chooser's "Surprise me".
     *
     * @param avoid the id chosen by the previous surprise, excluded so two consecutive surprises are
     *   never the same challenge. Yielded to when it is the only candidate, because repeating beats
     *   returning nothing (the same precedence [com.scrollkiller.guilt.GuiltRotation.pick] applies to
     *   its own `avoid`).
     *
     * Deliberately NOT [com.scrollkiller.guilt.GuiltRotation]: that class carries weighted draw and
     * cycle bookkeeping tuned for a server-swappable pack of dozens of lines. Challenges have no
     * weights and there are a handful, so "don't repeat the last one" is the whole requirement, and
     * borrowing the rotation would have meant generifying a well-tested class to gain nothing.
     */
    fun surpriseMe(
        candidates: List<ChallengeSpec>,
        avoid: String? = null,
        random: Random = Random.Default,
    ): ChallengeSpec? {
        if (candidates.isEmpty()) return null
        val pool = candidates.filterNot { it.id == avoid }.ifEmpty { candidates }
        return pool.random(random)
    }
}
