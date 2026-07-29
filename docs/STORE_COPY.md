# Store copy — claims checklist

What the Play listing, screenshots, and any marketing page are **not allowed to say**, and why.

This is not a style guide. Every entry here exists because the code cannot deliver the claim, or
because making the claim would put the listing at odds with a Play policy the app otherwise
complies with. An overclaim in store copy is a refund request at best and a takedown at worst.

**Process:** run this list before submitting the listing, and append to it from any ADR that
constrains what we may promise. Cite the ADR.

---

## ❌ Never claim the block is unbypassable

Do not say — or imply through screenshots, taglines, or feature bullets — that ScrollKiller
*cannot* be got around. Banned phrasings include "unbypassable", "unskippable", "can't be
dismissed", "locks you out", "forces you to stop", "no way around it".

**Why it is false, three ways over:**

1. **The user can always leave.** CLAUDE.md invariant 6 — Exit and Back leave the blocked app from
   every state, by design. This is the deliberate product decision, not a gap.
2. **`setHideOverlayWindows` (Android 12+).** Any app may call it and the system will hide our
   `TYPE_APPLICATION_OVERLAY`. If Instagram ever does, the block silently does not appear at all,
   and we would have no recourse (D49).
3. **The overlay permission is optional.** Revoke "Display over other apps" and the block no-ops.
   Detection and the in-app counter keep working, which is the intended fail-soft behaviour.
4. **A sanctioned way past it ships on purpose** — a physical challenge (D50). The free "5 more
   minutes" that used to sit beside it was deleted at **D77** (strict mode: challenge or exit), so
   do not describe two reprieves. See the challenge entry below.
5. **It depends on a permission the user can revoke at any moment.** Revoking "Display over other
   apps" kills the block instantly, and a debug reinstall did exactly that during testing (D51).
   So the block cannot be described as *always* working either — words like "always", "guaranteed",
   "never fails" are out for the same reason "unbypassable" is. The app now warns loudly when this
   happens; that is honesty about a real limitation, not a claim it cannot occur.

**Say instead:** it *interrupts* you, *makes you choose*, *puts your number in front of you before
the next reel*. The honest pitch is friction, not a cage — and friction is what actually works.

*Source: D49.*

---

## ❌ Never claim the challenge cannot be skipped

Do not say the physical unlock is *required*, *mandatory*, or that you *have to* walk to get past
the block. It is one of three ways off that screen, and the other two are a free tap and Exit —
both deliberately kept there (invariant 6, D49).

Banned phrasings: "you must walk to unlock", "no way past without moving", "forces you off the
couch". Also do not imply the challenge is always available: it needs a step sensor and the motion
permission, and on a device or grant state without them the button is not shown at all.

**Say instead:** it *offers* you a way to earn more time — 15 minutes for 20 steps against 5 for a
tap. The pitch is that the better deal costs you something, not that the app has taken your choices
away.

*Source: D50.*

---

## ❌ Never claim we read, store, or can show you what you watched

The AccessibilityService reads scroll events, the foreground package, and — on identity-advance
platforms only — the minimum node text needed to tell one item from the next, compared and
discarded in the same call. No captions, no video, no messages. Nothing you view leaves the device;
only counts are stored.

Copy must not imply content awareness ("knows what you're watching", "analyses your feed"). Beyond
being false, it invites exactly the Play Accessibility API scrutiny the disclosure exists to
satisfy.

*Source: D34 (privacy invariant), CLAUDE.md architecture note.*

---

## ❌ Never claim ScrollKiller blocks YouTube (or TikTok, or Snapchat)

**v1 blocks Instagram only. This is a decision, not a gap (D57).**

YouTube Shorts is `Maturity.BETA`: it **counts** — the number is real, it feeds the daily total and
the bubble — but it is structurally barred from driving a limit or a block
(`blocksAtLimit = blockEnabled && STABLE`). The advance-detection strategy for Shorts is built and
reasoned (D34) and ReVanced is supported (D52); what was never produced is the on-device acceptance
capture, so the count is not calibrated and the app will not act on it. TikTok and Snapchat are
further away still — both `BETA` *and* never toured, with Snapchat a known overcount.

Banned phrasings: "blocks Instagram and YouTube", "works on all short-video apps", "stops you on
Reels, Shorts and TikTok", any feature bullet or screenshot that shows a block screen over YouTube,
and any app-icon row implying parity between the four platforms.

**Say instead:** *blocks Instagram Reels; counts YouTube Shorts, TikTok and Snapchat.* The
distinction between counting and blocking is the honest one and it is legible to users — the app
itself shows a BETA badge next to the uncalibrated platforms for exactly this reason.

If YouTube is ever promoted to STABLE (a one-line change once the capture exists — the criteria are
written in D57), update this entry and the one below in the same commit as the promotion.

*Source: D57, D32, D34.*

---

## ⚠️ Per-platform accuracy claims must match `Maturity`

Only Instagram is calibrated (49/50, D11) and only Instagram can enforce a limit. YouTube, TikTok
and Snapchat are `Maturity.BETA` — they count and display, but are structurally barred from driving
a block, and Snapchat is a known overcount.

Copy must not present blocking as working across "all your apps", and any accuracy figure must name
Instagram. If a platform is promoted to STABLE later, update this entry with it.

*Source: D32, D49, D57.*
