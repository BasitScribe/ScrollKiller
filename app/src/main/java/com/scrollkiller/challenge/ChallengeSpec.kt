package com.scrollkiller.challenge

import androidx.annotation.StringRes
import com.scrollkiller.R

/**
 * WHAT the user physically does to earn their way past the block.
 *
 * Separate from [SensorStrategy] on purpose, and for exactly the reason
 * [com.scrollkiller.service.PlatformSpec] splits `platform` from `advanceStrategy`: what the user
 * DOES and how we MEASURE it are different questions, and they do not map one-to-one. [WALK]
 * already needs two measurement paths depending on what the device offers (see [StepSensorSource]),
 * and a future [JUMP] and [FACE_DOWN] would both read the accelerometer while being nothing alike
 * to perform.
 *
 * Only [WALK] is wired today. The rest are DECLARED and unused, the same way
 * [com.scrollkiller.service.AdvanceStrategy.EVENT_PULSE] is — so the next challenge picks its
 * shape from a written menu instead of inventing one, and so the gap between "declared" and
 * "implemented" is visible rather than discovered.
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
 * - [ACCEL_PEAKS]: declared, UNUSED. For a future jump challenge.
 * - [ORIENTATION_HOLD]: declared, UNUSED. For a future face-down / forehead challenge.
 *
 * [ChallengeRegistry.IMPLEMENTED] names which of these the engine actually honours, and a test
 * asserts every enabled spec uses one — so a spec added without its sensor code fails the build
 * instead of shipping a challenge that can never complete.
 */
enum class SensorStrategy { STEP_EVENTS, STEP_CUMULATIVE, ACCEL_PEAKS, ORIENTATION_HOLD }

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
 */
data class ChallengeSpec(
    val id: String,
    val type: ChallengeType,
    val target: Int,
    val sensorStrategy: SensorStrategy,
    @StringRes val promptRes: Int,
)

/**
 * The single source of truth for which challenges exist.
 *
 * One today. The registry ships anyway — for the same reason the guilt-pack picker shipped with a
 * single option (D43): a list that grows is a much better introduction than a control that appears
 * from nowhere the day a second entry lands.
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

    /** Challenges offered today. */
    val enabled: List<ChallengeSpec> = listOf(walk)

    /**
     * The strategies the engine actually implements. The gap between this and [SensorStrategy]'s
     * full set is the honest statement of what is declared but not yet built, and
     * `ChallengeRegistryTest` asserts every enabled spec falls inside it.
     */
    val IMPLEMENTED: Set<SensorStrategy> =
        setOf(SensorStrategy.STEP_EVENTS, SensorStrategy.STEP_CUMULATIVE)

    /** The default challenge offered on the block screen, or null if none are enabled. */
    val default: ChallengeSpec? get() = enabled.firstOrNull()

    fun forId(id: String): ChallengeSpec? = enabled.firstOrNull { it.id == id }
}
