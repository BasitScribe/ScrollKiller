"""The platforms a count can belong to — the server's half of a value shared with the client.

## Why this is its own module, before there is a database
`docs/SCHEMA.md` declares `daily_counts.platform` as a Postgres ENUM, and 3b will turn the members
below into exactly that type. But the members are not really a database concern: they are a WIRE
contract with the Android app, which posts a `platform` string in every sync batch. Defining them
here — in stdlib, with no SQLAlchemy import — means the parity guard in
`tests/test_platform_parity.py` can run today, on a machine with no database and no ORM installed,
which is the state both this repo and CI are actually in.

## The failure this exists to prevent, stated plainly
The client sends `"instagram"`. If the server's enum ever says `"ig"`, or gains `"threads"` the
client does not know about, or loses one the client still sends, the mismatch does not raise
anything a user or an operator would see. `POST /sync` rejects or drops that batch, the device's
queue never gets its ack, and the count silently stops going up **for one platform only** — on a
scoreboard nobody is watching closely, because the number on the phone (which is the source of
truth, and correct) still moves. That is the worst shape a bug can have here: invisible, partial,
and indistinguishable from the user simply having used that app less.

So the two lists are pinned to each other by a test rather than by a comment, and the test reads
the KOTLIN SOURCE rather than a copy of it. See `tests/test_platform_parity.py` for why that
matters more than it sounds.

## Order is not a contract; values are
The Kotlin enum's declaration order drives its chooser and registry ordering. Nothing here depends
on it, and the parity test compares SETS for that reason — pinning order too would fail the build
on a purely cosmetic client reshuffle, which teaches people to edit the test rather than to read it.
"""

from __future__ import annotations

from enum import StrEnum


class Platform(StrEnum):
    """A platform a reel count can be attributed to.

    `StrEnum`, so a member IS its wire value: `Platform.INSTAGRAM == "instagram"` is true and
    `json.dumps` needs no encoder. That removes the class of bug where a `.value` is forgotten on
    one code path and a batch is filed under `"Platform.INSTAGRAM"`.

    ⚑ **These strings are a wire contract with a SHIPPED Android client and are append-only in
    practice.** A released app keeps sending the value it was built with, and old versions live on
    devices for a long time. Renaming a member is therefore a data migration plus a compatibility
    window, never a one-line edit — and removing one silently discards counts from every device
    that has not updated. Add freely; change and delete deliberately.
    """

    INSTAGRAM = "instagram"
    YOUTUBE = "youtube"
    SNAPCHAT = "snapchat"
    FACEBOOK = "facebook"
    TIKTOK = "tiktok"


#: Name for the Postgres ENUM type 3b creates from [Platform].
#:
#: Named explicitly rather than left to SQLAlchemy's default, because the type name ends up in
#: migrations, in `pg_type`, and in every error message about a bad value — and a generated name is
#: one nobody can grep for. Lives here beside the members so the type and its name cannot drift.
PLATFORM_ENUM_NAME = "platform"


def parse_platform(value: str) -> Platform | None:
    """Return the [Platform] for a wire `value`, or None if it is not one we know.

    None rather than an exception, deliberately. The caller is a sync endpoint handling a batch
    from a client that may be NEWER than this server — a phone updated before a deploy finished, or
    a staged rollout — and an unknown platform in one batch must not fail the whole request and
    take the four valid batches beside it down with it. The endpoint's job is to ack what it
    understood and leave the rest unacked, which is safe precisely because sync is idempotent
    (invariant 1): the device keeps the batch and retries it after the deploy lands.

    Raising here would turn a forward-compatibility event into a sync outage.
    """
    try:
        return Platform(value)
    except ValueError:
        return None
