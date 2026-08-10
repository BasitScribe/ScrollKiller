"""IANA timezones, and the server-side day boundary they exist to compute.

## This module IS invariant 2
`CLAUDE.md`'s second invariant: *"Day boundary = user's timezone (stored on users), computed
server-side. Client trusts server on app open."* Every count the backend ever files has to land on
a date, and that date is a function of `users.timezone` and the server's receive time — never of
anything the device says. `local_date_for` is that function. It is written before the models
because it is the thing the models exist to feed, and because it is pure, so it can be nailed down
completely on a machine with no database.

The client currently uses `LocalDate.now()` as an interim (D14), which is why today's streaks are
marked PROVISIONAL and may shift when this lands (D81). When 3d starts filing counts, this is what
they shift to.

## THE DEPLOYMENT TRAP, and why `assert_tzdata_available` exists
`zoneinfo` does not carry a timezone database. It reads the SYSTEM one, and a slim container image
frequently has no `/usr/share/zoneinfo` at all — Debian-slim and Alpine both drop it. In that
environment every single lookup below raises `ZoneInfoNotFoundError`, which means **every user's
timezone is rejected and no count can be dated**, on a service whose entire job is dating counts.

The failure is nastier than it sounds: it does not appear in local development (a developer's
machine has tzdata), it does not appear in CI (the runner has tzdata), and it appears in production
on the first real request. So the presence of the database is asserted at STARTUP rather than
discovered per request — a container that cannot resolve `UTC` must fail loudly and immediately,
not serve traffic and reject its users one at a time. The fix, when it fires, is a one-line
addition of the `tzdata` package to `requirements.in`; the point of this note is that the next
person should not have to work out what happened first.
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

#: The zone assumed when a user has none recorded.
#:
#: UTC, and it is a fallback rather than a default anyone should hit: a user without a timezone is
#: a user whose day boundary is wrong for them, so the right response is to record one at sign-up
#: (3c) rather than to be relaxed about it here. UTC is chosen because it is the one zone that is
#: obviously not a guess about where somebody lives — a wrong-but-neutral answer is easier to spot
#: in data than a plausible one like `Asia/Kolkata`.
FALLBACK_TIMEZONE = "UTC"


class TimezoneDatabaseMissing(RuntimeError):
    """The interpreter cannot resolve even `UTC`, so no day boundary can be computed."""


def is_valid_timezone(name: str) -> bool:
    """True if `name` is a timezone this interpreter can actually resolve.

    Deliberately "can resolve" rather than "is in `available_timezones()`". The two differ, and the
    difference matters: `available_timezones()` enumerates the on-disk database and is empty in the
    very container this module's docstring warns about, whereas `ZoneInfo(...)` also picks up the
    `tzdata` PyPI package when it is installed. Validating against the enumeration would therefore
    reject every zone on a correctly-configured slim image that solved the problem the recommended
    way — a validator that fails when the deployment is fixed is worse than no validator.
    """
    if not name:
        return False
    try:
        ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        # ValueError covers the paths that never reach the database at all — an absolute path, a
        # `..` traversal attempt, a NUL byte. `zoneinfo` treats the key as a relative path into the
        # tz database, so rejecting these is a security property and not only a validation one.
        return False
    return True


def normalise_timezone(name: str | None) -> str:
    """A storable timezone for `name`, falling back to [FALLBACK_TIMEZONE].

    Used at the WRITE boundary — sign-up and profile update — so the database only ever holds zones
    that resolve. That is the cheap half of the guarantee. It matters because the read side runs on
    the sync hot path, where discovering a bad zone means a request that cannot be completed, and
    the user who suffers it is not the one who set it.

    Note this does not attempt to correct near-misses (`Asia/Calcutta`, `IST`, a UTC offset). It
    could, and it should not: silently reinterpreting somebody's timezone moves their midnight, and
    a day boundary that shifts without being asked to is exactly the kind of quiet wrongness
    invariant 2 exists to prevent. An unrecognised zone is a client bug worth surfacing, not
    smoothing over.
    """
    if name is not None and is_valid_timezone(name):
        return name
    return FALLBACK_TIMEZONE


def local_date_for(timezone: str, instant: datetime | None = None) -> date:
    """The calendar date it is in `timezone` at `instant` — the day a count belongs to.

    **Invariant 2, as a function.** The client never decides this.

    `instant` defaults to now in UTC. A naive `instant` is rejected rather than assumed to be UTC:
    a naive datetime reaching here means somebody called `datetime.now()` instead of
    `datetime.now(UTC)`, and quietly treating the server's local wall clock as UTC would put counts
    on the wrong date for exactly as long as the deployment's own timezone differed from UTC —
    which is the kind of bug that is invisible in a UTC container and appears the day one is
    provisioned differently.

    An unresolvable `timezone` falls back rather than raising, because this runs on the sync path
    and dating a count in UTC is far better than failing the batch: the count is the product, the
    date is metadata, and a rejected batch is retried forever by an idempotent client that can
    never succeed.
    """
    if instant is None:
        instant = datetime.now(UTC)
    elif instant.tzinfo is None:
        raise ValueError(
            "local_date_for requires an aware datetime; got a naive one. "
            "Use datetime.now(UTC), not datetime.now()."
        )

    zone = timezone if is_valid_timezone(timezone) else FALLBACK_TIMEZONE
    return instant.astimezone(ZoneInfo(zone)).date()


def assert_tzdata_available() -> None:
    """Fail startup if this process cannot resolve timezones at all.

    Called from application startup, NOT from a request handler and NOT from `/health` — liveness
    must never grow dependencies (D60), and a per-request check would pay this cost forever to
    detect a condition that can only be true at boot.

    Raises [TimezoneDatabaseMissing] with the fix in the message, because the person reading that
    line in a container log at 2am is not the person who wrote this module.
    """
    try:
        ZoneInfo(FALLBACK_TIMEZONE)
    except (ZoneInfoNotFoundError, ValueError) as exc:  # pragma: no cover - needs a broken image
        raise TimezoneDatabaseMissing(
            "no IANA timezone database is available to this interpreter, so no user's day "
            "boundary can be computed (invariant 2). This is normally a slim base image with no "
            "/usr/share/zoneinfo. Fix: add `tzdata` to requirements.in and regenerate the "
            "lockfile, or install the OS tzdata package in the runtime stage of the Dockerfile."
        ) from exc
