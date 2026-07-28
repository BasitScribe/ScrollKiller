#!/usr/bin/env python3
"""
Import the mascot art masters into pre-scaled Android density buckets.

    python tools/mascot_import.py

Reads one PNG master per mascot state from `art/mascot/` and writes pre-scaled
PNG drawables into `app/src/main/res/drawable-{m,h,xh,xxh,xxxh}dpi/`. Nothing is
scaled at runtime — see FAMILIES below for why there are two size families.

## Re-running after art changes
This script is the ONLY way mascot drawables get into res/. To swap art, overwrite
the master in `art/mascot/` keeping the same filename and re-run. Generated files are
overwritten in place, so there is no stale-drawable cleanup step. Do not hand-edit
anything under res/drawable-*dpi/.

## Backgrounds: keyed here, by flood fill (D37)
The generated masters arrive with a dark vignette-plus-glow background baked in as
OPAQUE RGB (three of the four did — only `healthy` had been hand-keyed). An opaque
master renders as a dark box over the app, and it makes the shared crop below a no-op,
which is what left the bubble mascot small inside dead canvas.

`key_background()` removes it WITHOUT a colour key. A colour/chroma key is what the
earlier revision of this file correctly refused to do: the background is a gradient and
any key wide enough to catch it also eats the character's dark outline. This instead
does a BORDER-SEEDED GEODESIC FLOOD — a pixel is background if it can be reached from
the canvas border without ever crossing a neighbour-to-neighbour colour step larger
than `KEY_TOLERANCE`. It keys on smoothness, not on colour, which is exactly the
property that separates a rendered vignette (neighbour deltas ≤4 across the whole
frame) from a cartoon outline (deltas of 60+ at the silhouette).

KEY_TOLERANCE=8 is not a taste value, it was picked off a stability plateau: sweeping
4→48 on all three masters, the keyed area sits flat at ~64% of canvas for 4..8 and then
jumps (fried 64%→85% at 10, guardian 65%→70% at 12, cracking 64%→92% at 24) as the
flood punches through a thin outline and drains the character's interior. Flat means
the segmentation is finding the real silhouette; the jump is the leak. 8 is the top of
the range that is flat for EVERY master, and `assert_no_leak()` fails the import if a
future master leaks anyway (a leak shows up as an implausibly small kept area).

Masters that already carry real alpha are left alone — `needs_keying()` only fires on a
master whose alpha channel is absent or fully opaque.

## Cropping
After keying, every master has alpha, so `union_trim()` always runs: ONE shared crop box
(the union of each master's alpha bbox, in relative coords) applied identically to all
four. Shared and not per-image on purpose — a per-image trim scales each state to fill
its own canvas, and the mascot would visibly change size when the count crossed a
threshold. The union is what buys the pixels back at bubble size: the crop takes the
masters from a 100%-of-canvas box to ~72%x86%, so the character actually fills the frame
it is drawn in.

## Output is NOT square
The union box is taller than it is wide, so the old `resize((px, px))` would have
stretched the character horizontally the moment the crop stopped being the full square
canvas. Output is emitted at the crop's natural ASPECT with its longer side at
`base_dp`, so there is no distortion and no transparent padding to pay for. The bubble's
ImageView is therefore height-bounded (40dp tall, width follows) — see OverlayController.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "art" / "mascot"
RES = REPO / "app" / "src" / "main" / "res"

# Mascot state -> master filename. The three count-driven states plus GUARDIAN,
# which is block-screen only and never derived from a count (see MascotArt.kt).
STATES = {
    "healthy": "healthy.png",
    "cracking": "cracking.png",
    "fried": "fried.png",
    "guardian": "guardian.png",
}

# Android density buckets and their scale factor relative to mdpi (160dpi).
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# Two size families, because one drawable cannot serve both surfaces cheaply.
#
#   hero   120dp — Home hero and the block screen.
#   bubble  40dp — the overlay bubble's mascot ImageView. It gets its own
#                  pre-scaled family so the bubble never runtime-downscales a
#                  480px hero bitmap on every state swap. At 40dp the character
#                  must stay readable as a silhouette, which is what the size is
#                  picked for.
#
# `base_dp` is the LONGER side of the output (the crop is not square — see the
# module doc). GUARDIAN is intentionally absent from the bubble family: the bubble
# has no blocked state, so generating it would ship a drawable nothing references.
#
# `sub` (optional) is a further relative crop applied AFTER the shared union trim,
# for a family that needs part of the character rather than all of it. See `head`.
FAMILIES = {
    "hero": {"base_dp": 120, "states": list(STATES)},
    "bubble": {"base_dp": 40, "states": ["healthy", "cracking", "fried"]},
    # The nav-bar glyph (D58). A 24dp full-body mascot is unreadable mush — which is
    # precisely why a 🧠 emoji placeholder survived in the bottom nav for so long — so
    # this family crops to cap + face + hands.
    #
    # The box was MEASURED, not guessed: candidates were rendered at true 24dp and
    # compared, and this one lands at aspect 1.03, so it fills a square nav slot
    # instead of sitting in it as a short wide sliver. Cropping tighter (to the face
    # alone) loses the cap, which is most of the character's silhouette at this size.
    #
    # healthy only: a nav icon is NAVIGATION, not state. Swapping the tab glyph as the
    # count climbed would make the bottom bar flicker between arts and read as a bug.
    "head": {
        "base_dp": 24,
        "states": ["healthy"],
        "sub": (0.06, 0.00, 0.94, 0.70),
    },
}

# Largest neighbour-to-neighbour colour step (max over R/G/B) the background flood may
# cross. See the module doc for why this is 8 and not a matter of preference.
KEY_TOLERANCE = 8

# A keyed master whose remaining opaque area is below this fraction of the canvas has
# almost certainly had its interior drained through a thin outline. Every current master
# lands at ~35% kept, so 20% is a floor that catches a leak without being touchy.
MIN_KEPT_AREA = 0.20


def out_name(family: str, state: str) -> str:
    """`mascot_fried` (hero), `mascot_fried_bubble`, `mascot_head`."""
    if family == "hero":
        return f"mascot_{state}"
    if family == "head":
        return "mascot_head"
    return f"mascot_{state}_bubble"


def sub_crop(im: Image.Image, box: tuple[float, float, float, float]) -> Image.Image:
    """Crop `im` to a relative (l, t, r, b) box. Used by the `head` family."""
    w, h = im.size
    left, top, right, bottom = box
    return im.crop((round(left * w), round(top * h), round(right * w), round(bottom * h)))


def needs_keying(im: Image.Image) -> bool:
    """True when a master has no usable alpha: no alpha channel, or one that is all 255."""
    if im.mode not in ("RGBA", "LA") and "transparency" not in im.info:
        return True
    return im.convert("RGBA").getchannel("A").getextrema()[0] == 255


def key_background(im: Image.Image, tol: int = KEY_TOLERANCE) -> Image.Image:
    """
    Make the studio background transparent by flooding inward from the canvas border,
    stepping between adjacent pixels only while their colour difference stays within
    `tol`. Returns a new RGBA image; the input is not modified.

    This is a geodesic (connectivity-respecting) fill, NOT a colour key — background
    that happens to match a colour inside the character is never reached, because the
    character's outline is a wall the flood cannot step over. See the module doc.
    """
    rgb = np.asarray(im.convert("RGB")).astype(np.int16)
    h, w, _ = rgb.shape

    # Per-edge passability, precomputed once: may the flood step between these two
    # horizontally / vertically adjacent pixels?
    ok_h = np.abs(np.diff(rgb, axis=1)).max(axis=2) <= tol   # (h, w-1)
    ok_v = np.abs(np.diff(rgb, axis=0)).max(axis=2) <= tol   # (h-1, w)

    bg = np.zeros((h, w), bool)
    bg[0, :] = bg[-1, :] = True
    bg[:, 0] = bg[:, -1] = True

    # Iterate the 4-neighbour dilation to a fixed point. Vectorised over the whole
    # frame, so this is ~600 cheap array passes rather than a per-pixel BFS.
    while True:
        filled = bg.sum()
        bg[:, 1:] |= bg[:, :-1] & ok_h
        bg[:, :-1] |= bg[:, 1:] & ok_h
        bg[1:, :] |= bg[:-1, :] & ok_v
        bg[:-1, :] |= bg[1:, :] & ok_v
        if bg.sum() == filled:
            break

    out = im.convert("RGBA")
    out.putalpha(Image.fromarray((~bg * 255).astype(np.uint8)))
    return out


def assert_no_leak(state: str, im: Image.Image) -> None:
    """Fail the import if keying drained the character instead of just the background."""
    kept = (np.asarray(im.getchannel("A")) > 0).mean()
    if kept < MIN_KEPT_AREA:
        sys.exit(
            f"error: keying {state}.png kept only {kept * 100:.1f}% of the canvas "
            f"(floor {MIN_KEPT_AREA * 100:.0f}%). The flood almost certainly leaked "
            f"through an outline. Lower KEY_TOLERANCE (currently {KEY_TOLERANCE}) and "
            f"re-run, or hand-key this master."
        )


def load_masters() -> dict[str, Image.Image]:
    """Load every master as RGBA, keying the background of any that arrived opaque."""
    images: dict[str, Image.Image] = {}
    for state, filename in STATES.items():
        path = SRC / filename
        if not path.exists():
            sys.exit(f"error: missing master {path}")
        im = Image.open(path)
        if needs_keying(im):
            im = key_background(im)
            assert_no_leak(state, im)
            print(f"  keyed background: {filename}")
        else:
            im = im.convert("RGBA")
            print(f"  alpha already present: {filename}")
        images[state] = im
    return images


def report_boxes(images: dict[str, Image.Image], label: str) -> None:
    """Print each master's opaque bbox as a percentage of its canvas, plus the union."""
    print(f"\n{label}")
    for state, im in images.items():
        w, h = im.size
        box = im.getchannel("A").getbbox()
        if box is None:
            print(f"  {state:<9} fully transparent")
            continue
        bw, bh = (box[2] - box[0]) / w, (box[3] - box[1]) / h
        print(
            f"  {state:<9} canvas {w}x{h}  opaque box {bw * 100:5.1f}% x {bh * 100:5.1f}% "
            f"of canvas  (L{box[0] / w * 100:.1f}% T{box[1] / h * 100:.1f}% "
            f"R{box[2] / w * 100:.1f}% B{box[3] / h * 100:.1f}%)"
        )


def union_trim(images: dict[str, Image.Image]) -> None:
    """
    Crop every master to ONE shared box: the union of each master's alpha bounding
    box, taken in relative coords so masters of differing pixel sizes agree, then
    re-applied per image. Mutates `images`.

    Shared rather than per-image on purpose — a per-image trim would scale each state to
    fill its own canvas and the mascot would visibly change size when the count crossed
    a threshold.
    """
    left = top = 1.0
    right = bottom = 0.0
    for im in images.values():
        box = im.getchannel("A").getbbox()
        if box is None:  # fully transparent master; nothing to contribute
            continue
        w, h = im.size
        left, top = min(left, box[0] / w), min(top, box[1] / h)
        right, bottom = max(right, box[2] / w), max(bottom, box[3] / h)
    if left >= right or top >= bottom:
        return
    print(
        f"\nShared union crop: L{left * 100:.1f}% T{top * 100:.1f}% "
        f"R{right * 100:.1f}% B{bottom * 100:.1f}% "
        f"-> {(right - left) * 100:.1f}% x {(bottom - top) * 100:.1f}% of canvas"
    )
    for state, im in images.items():
        w, h = im.size
        images[state] = im.crop(
            (round(left * w), round(top * h), round(right * w), round(bottom * h))
        )


def target_size(crop: tuple[int, int], base_dp: int, factor: float) -> tuple[int, int]:
    """
    Output pixel size for a `crop`-shaped master in this density bucket: the crop's own
    aspect ratio with its LONGER side at `base_dp` * `factor`.

    Not square. Forcing the (taller-than-wide) union crop into a square would either
    stretch the character or pad it back out with the transparent margin the crop just
    removed — and padding is what made the bubble mascot look small in the first place.
    """
    long_px = round(base_dp * factor)
    cw, ch = crop
    if ch >= cw:
        return max(1, round(long_px * cw / ch)), long_px
    return long_px, max(1, round(long_px * ch / cw))


def main() -> int:
    print("Masters:")
    images = load_masters()

    report_boxes(images, "Opaque bounding box BEFORE trim:")
    union_trim(images)
    report_boxes(images, "Opaque bounding box AFTER trim (this is what ships):")

    print()
    written = 0
    for family, cfg in FAMILIES.items():
        for state in cfg["states"]:
            master = images[state]
            sub = cfg.get("sub")
            if sub:
                master = sub_crop(master, sub)
            for bucket, factor in DENSITIES.items():
                size = target_size(master.size, cfg["base_dp"], factor)
                target = RES / f"drawable-{bucket}"
                target.mkdir(parents=True, exist_ok=True)
                dest = target / f"{out_name(family, state)}.png"
                master.resize(size, Image.LANCZOS).save(dest, optimize=True)
                written += 1
            w, h = target_size(master.size, cfg["base_dp"], 1.0)
            print(f"  {out_name(family, state):<28} {w}x{h}dp x5 buckets")

    print(f"\nWrote {written} drawables under {RES.relative_to(REPO)}/drawable-*dpi/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
