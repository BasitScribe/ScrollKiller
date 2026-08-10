#!/usr/bin/env python3
"""Author new guilt lines behind a MIRROR of the app's own build-time guard.

Why this file exists
--------------------
D85 established the rule and this makes it repeatable: candidate lines are validated
BEFORE the asset is touched, against a Python restatement of
``GuiltText.offendingNumber`` plus the pack's structural rules. If anything fails,
nothing is written. A bad line therefore cannot reach a diff, let alone a build — which
matters because the guard's whole purpose is to stop a *baked-in magnitude* shipping,
and the last time the pack grew, the new content's register (Hinglish) found a hole in
the guard that English content never would have (``lakh``/``crore``).

⚑ This is a MIRROR, not the source of truth. ``GuiltText.kt`` is, and the test in
``GuiltTextTest`` is what actually fails the build. If the two ever disagree, the Kotlin
wins and this file is the one that is wrong. Keep the regexes below character-identical
to the Kotlin ones; they are copied deliberately rather than generated, because a
generator would be a third thing to keep in step.

Usage:  python3 tools/guilt_author.py [--write]
        (without --write it validates and reports, changing nothing)
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

PACK = Path(__file__).resolve().parent.parent / "app/src/main/assets/guilt_pack.json"

COUNT_TOKEN = "{count}"
MINUTES_TOKEN = "{minutes}"
TOKEN_MARKER = "NUMTOKEN"

# --- mirrors of GuiltText.kt ------------------------------------------------------
DIGITS = re.compile(r"\d")
SCALE_WORDS = re.compile(
    r"\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|"
    r"century|dozen|lakh|lakhs|crore|crores)\b",
    re.IGNORECASE,
)
BARE_TIME_UNIT = re.compile(rf"(?<!{TOKEN_MARKER} )\b(minute|minutes|hour|hours)\b", re.IGNORECASE)
UNKNOWN_TOKEN = re.compile(r"\{[^}]*\}")

# --- pack rules the Kotlin tests also assert ---------------------------------------
MAX_CHARS = 110  # panel width cap (D85)
CATEGORIES = {"roast", "existential", "reverse_psych", "pride"}
ACCESS = {"free", "premium"}


def offending_number(text: str) -> str | None:
    """Exactly what GuiltText.offendingNumber does, in Python."""
    stripped = text.replace(COUNT_TOKEN, TOKEN_MARKER).replace(MINUTES_TOKEN, TOKEN_MARKER)
    for pattern in (UNKNOWN_TOKEN, DIGITS, SCALE_WORDS, BARE_TIME_UNIT):
        m = pattern.search(stripped)
        if m:
            return m.group(0)
    return None


def validate(lines: list[dict], existing_ids: set[str]) -> list[str]:
    problems: list[str] = []
    seen: set[str] = set()
    for line in lines:
        lid = line.get("id", "<no id>")
        text = line.get("text", "")
        if lid in existing_ids:
            problems.append(f"{lid}: id already in the pack")
        if lid in seen:
            problems.append(f"{lid}: duplicate id within this batch")
        seen.add(lid)
        if line.get("category") not in CATEGORIES:
            problems.append(f"{lid}: bad category {line.get('category')!r}")
        if line.get("access", "free") not in ACCESS:
            problems.append(f"{lid}: bad access {line.get('access')!r}")
        if not isinstance(line.get("weight"), int):
            problems.append(f"{lid}: weight must be an int")
        if len(text) > MAX_CHARS:
            problems.append(f"{lid}: {len(text)} chars, over the {MAX_CHARS} panel cap")
        bad = offending_number(text)
        if bad:
            problems.append(f"{lid}: guard rejects {bad!r} — {text!r}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="append the batch to the pack")
    args = parser.parse_args()

    pack = json.loads(PACK.read_text(encoding="utf-8"))
    existing = {line["id"] for line in pack["lines"]}

    problems = validate(NEW_LINES, existing)
    if problems:
        print(f"REFUSING TO WRITE — {len(problems)} problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1

    by = Counter((line["category"], line.get("access", "free")) for line in NEW_LINES)
    print(f"{len(NEW_LINES)} candidate lines, all clean.")
    for (cat, acc), n in sorted(by.items()):
        print(f"  {cat:14} {acc:8} {n}")

    if not args.write:
        print("\n(dry run — pass --write to append)")
        return 0

    # ⚑ Appended as TEXT, not re-serialised.
    #
    # `json.dump` would reformat all 155 existing lines and turn a pure addition into a
    # whole-file rewrite — the diff would show every line as changed, and a reviewer could no
    # longer see at a glance that no existing content was touched. That property is not
    # cosmetic: "the old lines were untouched" is the argument that lets `access` default to
    # FREE for every pre-D85 line, and it has to stay checkable in a diff. The pack is written
    # one object per line at four spaces, so matching it by hand is trivial and re-serialising
    # is the only way to get it wrong.
    raw = PACK.read_text(encoding="utf-8")
    rendered = ",\n".join(_render(line) for line in NEW_LINES)
    # The marker is the CLOSE OF THE WHOLE DOCUMENT, not just "\n  ]" — the `_comment` array
    # at the top of the pack ends the same way, and the first version of this script happily
    # found it and refused to continue. That refusal is the design: an ambiguous anchor is a
    # script that silently writes lines into a comment block.
    marker = " }\n  ]\n}"
    if raw.count(marker) != 1:
        print("could not find a unique end-of-lines marker; refusing to guess", file=sys.stderr)
        return 1
    raw = raw.replace(marker, " },\n" + rendered + "\n  ]\n}")

    # Revision must move or a hot-swapped pack is ignored by GuiltPackLoader's revision guard.
    old_rev = pack.get("revision", 0)
    raw = raw.replace(f'"revision": {old_rev}', f'"revision": {old_rev + 1}', 1)

    PACK.write_text(raw, encoding="utf-8")
    total = len(pack["lines"]) + len(NEW_LINES)
    print(f"\nwritten. pack is now {total} lines, revision {old_rev + 1}.")
    return 0


def _render(line: dict) -> str:
    """One pack line, in the file's existing one-object-per-line shape."""
    parts = [
        f'"id": {json.dumps(line["id"], ensure_ascii=False)}',
        f'"category": {json.dumps(line["category"], ensure_ascii=False)}',
        f'"intensity": {line["intensity"]}',
        f'"weight": {line["weight"]}',
        f'"text": {json.dumps(line["text"], ensure_ascii=False)}',
    ]
    if "access" in line:
        parts.append(f'"access": {json.dumps(line["access"], ensure_ascii=False)}')
    return "    { " + ", ".join(parts) + " }"


def R(i, text, weight=3, access=None):
    return _line(f"t4_r{i}", "roast", text, weight, access)


def E(i, text, weight=3, access=None):
    return _line(f"t4_e{i}", "existential", text, weight, access)


def P(i, text, weight=3, access=None):
    return _line(f"t4_p{i}", "reverse_psych", text, weight, access)


def D(i, text, weight=3, access=None):
    return _line(f"t4_d{i}", "pride", text, weight, access)


def _line(lid, category, text, weight, access):
    out = {"id": lid, "category": category, "intensity": 4, "weight": weight, "text": text}
    if access:
        out["access"] = access
    return out


# ---------------------------------------------------------------------------------
# The batch. Tier 4 only — it is the one tier the build still tracks as short (D48's
# committed target is 150 free; T3 was closed permanently at D85).
#
# Voice rules, unchanged from D85: funny-savage, never cruel (D9's anti-uninstall
# principle outranks being funny), Hinglish leaning hard, and the count only ever
# arrives through {count}/{minutes} — which is what the guard above enforces.
# PRIDE lines stay FREE on purpose: the way out must never sit behind a paywall.
# ---------------------------------------------------------------------------------
NEW_LINES = [
    R(24, "{count}. Bhai ye scroll nahi, ye ab lifestyle ban gaya hai.", 4),
    R(25, "{count}. Your thumb has a better cardio routine than you do.", 4),
    R(26, "Arre yaar, even the algorithm is going: bas kar ab."),
    R(27, "{count}. Somewhere a plant you own is dying and it knows exactly why."),
    R(28, "You have scrolled so far the app started showing you reels about scrolling."),
    R(29, "{count}. Bhai tera screen time ab ek career option hai.", 4),
    R(30, "Chalo bas ek aur, you said. That was a while back, boss."),
    R(31, "{count}. NPC behaviour, and the NPC is you."),
    R(32, "Your For You page has officially run out of you."),
    R(33, "{count}. Bas thoda aur, you said. Bas thoda aur, you keep saying.", 4),
    R(34, "This is not timepass any more. Time is passing you."),
    R(35, "{count}. Sasta dopamine, mehenga din.", 4),
    R(36, "Your eyes are open but nobody is home, boss."),
    R(37, "{count}. Even your charger is tired of this relationship."),
    R(38, "Delulu is not the solulu when the solulu is putting the phone down."),
    R(39, "{count}. You are not consuming content. Content is consuming you.", 4),
    R(40, "Theek hai, you win. Nobody can outscroll you. Congratulations, champion."),
    R(41, "{count}. Your brain is buffering and the wifi is fine."),
    R(42, "Bhai the reels are fine. It is the number next to them that is worrying."),
    R(43, "{count}. You are cooked, and you keep asking for another plate.", 4),
    R(44, "Your thumb is the most employed part of you right now.", 3, "premium"),
    R(45, "{count}. Your aura is being deducted in real time.", 3, "premium"),
    R(46, "Somebody replied to you and you scrolled straight past your own notification."),
    R(47, "{count}. Bilkul zero self control, full confidence. Iconic, honestly."),
    R(48, "The app has stopped trying. It knows you are staying.", 3, "premium"),
    R(49, "{count}. This is a hostage situation and you are both parties."),
    E(15, "{minutes} minutes. That is a thing you had and now do not.", 4),
    E(16, "{count}. Nobody will ever ask you about a single one of these."),
    E(17, "You will not remember one of these tomorrow. Not one of them."),
    E(18, "{count} of them, and not one was actually for you.", 4),
    E(19, "This is the part of the day you will not mention to anyone."),
    E(20, "{count}. Somewhere your future self is quietly reading this too."),
    E(21, "The day is not being spent. It is being taken."),
    E(22, "{count}. You did not choose any of this. You just did not stop.", 4),
    E(23, "Arre, the feed does not end. That is the whole design, not a bug."),
    E(24, "{count}. None of this was made for you. It was made at you."),
    E(25, "You could have been bored. Bored is where the ideas live."),
    E(26, "{count}. This is what somebody else's business model feels like from inside.",
      3, "premium"),
    E(27, "Your attention was the price, and nobody showed you a bill.", 3, "premium"),
    E(28, "{count}. Chalo, one honest question: what were you avoiding?", 4),
    P(12, "Bilkul mat ruko. Ek aur. The record is right there, boss."),
    P(13, "{count}. Keep going. Somebody has to be the case study."),
    P(14, "Do not put it down. You have come too far to have a life now."),
    P(15, "{count}. Honestly, commit. Half-hearted scrolling is worse."),
    P(16, "Theek hai, carry on. Your future self will deal with it."),
    P(17, "{count}. No no, finish the feed. It definitely ends somewhere."),
    P(18, "Please continue. The mascot is taking notes for the group chat.", 3, "premium"),
    P(19, "{count}. Do not stop now, you are so close to absolutely nothing."),
    P(20, "Chalo, thoda aur. Sleep is basically optional, na?"),
    P(21, "{count}. Keep scrolling. The app is genuinely proud of you.", 3, "premium"),
    P(22, "Ignore this. That is what you have been doing all along anyway."),
    P(23, "{count}. Go on. Somebody out there is scrolling more, probably."),
    D(11, "You got this far and you are still reading. That already counts."),
    D(12, "{count}. Big number. Closing the app is still allowed, by the way.", 4),
    D(13, "Bas. Put it down and go do the smallest possible useful thing."),
    D(14, "{count}. No lecture. You can stop whenever you decide to."),
    D(15, "Arre, one deep breath. You are still the one holding the phone.", 4),
    D(16, "{count}. Rough day? Fair enough. Tomorrow starts from zero anyway."),
    D(17, "You noticed. That is genuinely the hard part, and you just did it.", 4),
    D(18, "{count}. Theek hai, chalo. Phone down, head up. That is the whole ask."),
    D(19, "Nobody is keeping score except this app, and it forgets every day."),
    D(20, "{count}. Whatever this was really about, it is not on your phone.", 4),
]


if __name__ == "__main__":
    raise SystemExit(main())
