# Challenges

← [[ScrollKiller]]

The block is never just a wall — there is always a way to earn past it. Four physical challenges,
all worth the same 15 minutes, and the user picks.

## Mind map

```mermaid
mindmap
  root((Challenges))
    Enabled all four
      walk_20 STEP_EVENTS
      jump_10 ACCEL_PEAKS
      face_down_30 ORIENTATION_HOLD
      forehead_30 PROXIMITY_HOLD
    Chooser
      User picks not us
      Surprise me no repeat
      Only rows this device can run
      Own Exit listed first
    Counts vs holds
      Counts rise only
      Holds are elapsed seconds
      Break RESETS never pauses
      Ring counts down arc fills
    Reward
      Challenge 15 minutes
      Free tap 5 minutes
      Flat not effort scaled
    Anti cheat
      Jump needs free fall then landing
      Shake alone never counts
      Forehead needs covered AND upright
      Thumb on a table fails
    Holds needed extra
      KEEP_SCREEN_ON 30s timeout
      Haptics because ring is face down
    Availability per spec
      Only steps need permission
      No sensor no row
```

## Why each one exists

| Challenge | Its job in the set |
|-----------|--------------------|
| **Walk 20** | Gets you off the sofa. Needs a room and a pedometer |
| **Jump 10** | Genuinely aerobic. Needs no permission and four square feet — works where walk can't |
| **Face down 30s** | Asks for nothing physical. Works in a lecture, on a bus, at 2am. The only one you can't do while still watching the reel |
| **Forehead 30s** | Deliberately absurd. A moment of "what am I doing" interrupts a trance harder than counting does |

Choice is about **accessibility, not optimisation** — which is why the reward is flat. Someone who
picks the easy one because they physically cannot do the others must not be paid less for it.

## Shape

`ChallengeSpec` (data) → `ChallengeSensors.sourceFor` → a `ChallengeSensorSource`, with a **pure
detector** beside each thin sensor half (`JumpDetector`, `HoldDetector`). `ChallengeRegistry.IMPLEMENTED`
is the gate: a spec naming an unbuilt strategy fails the build rather than shipping a challenge that
can never complete.

Holds cost two things counts did not — the screen must be held awake (a default 30s timeout is
exactly the hold length, and a sleeping screen stops the sensor) and haptics are the only feedback,
because a face-down ring points at the table.

Related: [[Overlay and Block]] · full audit in `docs/PROJECT_MAP.md`

#challenges
