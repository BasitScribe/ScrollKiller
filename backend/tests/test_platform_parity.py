"""The client and the server must agree, exactly, on what a platform is called.

## Why this test reads Kotlin
The obvious version of this test writes the five strings out again and asserts the Python enum
matches them. That test passes forever and catches nothing: it pins the server against a COPY of
the contract, so the day somebody adds `THREADS("threads")` to the Android enum, this file is still
green and the server still drops every Threads batch on the floor.

So it parses `app/src/main/java/com/scrollkiller/service/PlatformSpec.kt` and compares against what
the client ACTUALLY declares. The Android tree is in the same repo (the monorepo decision, D59) and
CI checks out the whole thing, so this costs nothing and is the only version of the test with any
power. It is the same instinct as `GuiltTextTest` walking the Kotlin source for hardcoded guilt
lines, and as the test that walks `health.py`'s import graph (D60): where a rule spans two things
that can drift, the test has to look at both.

## What a failure means, in each direction
- **Client has one the server lacks** — that platform's counts are being posted and silently
  discarded. Add the member to `app/platforms.py`, and in 3b add a migration extending the Postgres
  ENUM (`ALTER TYPE ... ADD VALUE`, which cannot run inside a transaction on older Postgres — worth
  knowing before you write it).
- **Server has one the client lacks** — usually harmless but usually also a mistake: either a
  platform was removed from the app without the server being told, or somebody added one here
  first. Removing it from the server is only safe once no shipped build sends it, which is a
  release-timing question, not a code question.

Either way the fix is a decision, which is why this fails the build instead of warning.
"""

from __future__ import annotations

import re

import pytest

from app.platforms import Platform, parse_platform
from tests.conftest import REPO_ROOT

#: The Kotlin file declaring the client's platform identifiers.
PLATFORM_SPEC_KT = (
    REPO_ROOT / "app/src/main/java/com/scrollkiller/service/PlatformSpec.kt"
)

#: `INSTAGRAM("instagram"),` — a member of the Kotlin enum, capturing its wire id.
#:
#: Anchored to the enum BODY by `_kotlin_platform_ids` rather than run over the whole file, because
#: `PlatformSpec.kt` also declares AdvanceStrategy, GatingMode, Maturity and SurfaceMatcher, and a
#: file-wide scan would happily collect members of whichever of those grew a string argument next.
_MEMBER = re.compile(r'^\s*([A-Z][A-Z0-9_]*)\("([^"]+)"\)', re.MULTILINE)

_ENUM_HEADER = re.compile(r"enum\s+class\s+Platform\s*\(\s*val\s+id\s*:\s*String\s*\)\s*\{")


def _kotlin_platform_ids() -> dict[str, str]:
    """Kotlin member name → wire id, read from the client's `Platform` enum."""
    source = PLATFORM_SPEC_KT.read_text(encoding="utf-8")

    header = _ENUM_HEADER.search(source)
    assert header is not None, (
        f"could not find `enum class Platform(val id: String)` in {PLATFORM_SPEC_KT}. "
        "If the client's enum was renamed or its constructor changed, this parser has to change "
        "with it — do NOT delete the test, or the wire contract stops being checked at all."
    )

    body_start = header.end()
    body_end = source.index("}", body_start)
    body = source[body_start:body_end]

    found = {name: wire_id for name, wire_id in _MEMBER.findall(body)}
    assert found, f"parsed the Platform enum in {PLATFORM_SPEC_KT} but found no members"
    return found


def test_the_kotlin_source_is_actually_readable():
    """Guard the guard.

    Every assertion below is only as good as the parse, and a parser that quietly returns an empty
    set makes every other test in this file pass vacuously — the exact failure mode that makes
    source-scanning tests worth distrusting. So the parse is asserted on its own, with a count that
    has to be updated deliberately.
    """
    ids = _kotlin_platform_ids()
    assert len(ids) == len(Platform), (
        f"client declares {len(ids)} platforms {sorted(ids.values())}, server has {len(Platform)}. "
        "See this module's docstring for which direction is which."
    )


def test_wire_ids_match_the_client_exactly():
    """THE test. A mismatch here is a silent, per-platform sync drop."""
    client_ids = set(_kotlin_platform_ids().values())
    server_ids = {p.value for p in Platform}

    missing_on_server = client_ids - server_ids
    missing_on_client = server_ids - client_ids

    assert not missing_on_server, (
        f"the Android app posts {sorted(missing_on_server)} and this server does not accept it. "
        "Those counts are being discarded silently — the phone's number keeps rising and the "
        "scoreboard's does not. Add the member to app/platforms.py and extend the Postgres ENUM."
    )
    assert not missing_on_client, (
        f"this server accepts {sorted(missing_on_client)} and no shipped client sends it. "
        "Removing it is only safe once no released build can still post it."
    )


def test_member_names_match_too():
    """Names as well as values, because a swap would pass a values-only check.

    `INSTAGRAM("youtube")` and `YOUTUBE("instagram")` produce an identical SET of wire ids, so a
    test comparing only values is green while every count is filed under the wrong platform. That
    is a worse outcome than dropping them: the data is wrong rather than absent, and it looks
    plausible on a dashboard.
    """
    client = _kotlin_platform_ids()
    server = {p.name: p.value for p in Platform}
    assert client == server


def test_order_is_deliberately_not_compared():
    """A note in executable form.

    The client's declaration order is load-bearing for ITS chooser and registry (effort-first
    ordering is pinned by `ChallengeRegistryTest`, and the platform list feeds UI ordering). Nothing
    on the server depends on it. Asserting order here would fail the build on a cosmetic client
    reshuffle and teach the next person to edit this file rather than read it — so the parity checks
    above compare sets and mappings, and this test states that the omission is a choice.
    """
    client_order = list(_kotlin_platform_ids())
    server_order = [p.name for p in Platform]
    assert sorted(client_order) == sorted(server_order)


@pytest.mark.parametrize("wire_id", [p.value for p in Platform])
def test_every_member_round_trips_through_the_parser(wire_id: str):
    parsed = parse_platform(wire_id)
    assert parsed is not None
    assert parsed.value == wire_id
    # StrEnum: the member IS the string, so no call site can forget a `.value`.
    assert parsed == wire_id


def test_an_unknown_platform_is_none_and_not_an_exception():
    """Forward compatibility, and the reason it is not an error.

    A phone updated before a deploy finishes will post a platform this server has never heard of.
    Raising would fail the whole `/sync` request and take the valid batches in it down too; None
    lets the endpoint ack what it understood and leave the rest unacked, which is safe because sync
    is idempotent (invariant 1) — the device keeps the batch and retries after the deploy lands.
    """
    assert parse_platform("threads") is None
    assert parse_platform("") is None
    assert parse_platform("INSTAGRAM") is None  # case matters; the wire values are lowercase


def test_the_enum_type_name_is_pinned():
    """The Postgres type name appears in migrations and in every bad-value error.

    Pinned so 3b's migration and the model cannot disagree about it, and so it stays greppable
    rather than becoming whatever SQLAlchemy would have generated.
    """
    from app.platforms import PLATFORM_ENUM_NAME

    assert PLATFORM_ENUM_NAME == "platform"
