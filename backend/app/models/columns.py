"""Column types shared across tables — chiefly the Postgres ENUM the client's platform ids become.

Defined once, in one module, for a reason that is specific to `Enum`: a `sqlalchemy.Enum` with a
`name` is a *database type*, not a column decoration. Two tables each constructing their own
`Enum(Platform, name="platform")` are two Python objects claiming one `pg_type` row, and the
duplicate only announces itself as a `DuplicateObjectError` during `CREATE TYPE`. Sharing the
instance is what makes it one type.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Any

from sqlalchemy import DateTime, Enum, func
from sqlalchemy.orm import MappedColumn, mapped_column

from app.platforms import PLATFORM_ENUM_NAME, Platform


def wire_values(enum: type[StrEnum]) -> list[str]:
    """Return the enum's VALUES, which is what gets stored.

    ⚑ **This is the whole reason `values_callable` is passed below, and it is not a style
    preference.** SQLAlchemy's `Enum` persists member *names* by default, so without this the
    database would hold `INSTAGRAM` while the client, the wire contract and
    `tests/test_platform_parity.py` all speak `instagram`. Nothing would raise: `Platform.INSTAGRAM`
    round-trips through the name perfectly well. It would surface much later, as a hand-written
    query or a Phase 4 leaderboard read that filters on the documented value and returns nothing —
    the same invisible-partial failure shape `app/platforms.py` exists to prevent, arrived at from
    the other side.
    """
    return [member.value for member in enum]


#: The Postgres ENUM for `daily_counts.platform` and `sync_batches.platform`.
#:
#: `native_enum=True` (the default, stated for the record) makes this a real `CREATE TYPE`, so the
#: database rejects an unknown platform rather than storing it. That is deliberate even though
#: `parse_platform` already filters unknown values at the edge: the edge check keeps one bad batch
#: from failing a whole sync request, and this one keeps a bug anywhere else from writing a platform
#: that does not exist. Adding a member later is `ALTER TYPE ... ADD VALUE`, which needs its own
#: migration.
platform_enum = Enum(
    Platform,
    name=PLATFORM_ENUM_NAME,
    values_callable=wire_values,
    native_enum=True,
)


def utc_now_column(**kwargs: Any) -> MappedColumn[Any]:
    """A `TIMESTAMPTZ` column defaulting to the SERVER's clock.

    `func.now()` renders as Postgres `now()` and is evaluated by the database, not by this process.
    That is the point: invariant 2 says the server owns the day boundary, and a default computed in
    Python would hand that authority to whichever container happened to serve the request — plus
    its clock drift.
    """
    return mapped_column(DateTime(timezone=True), server_default=func.now(), **kwargs)
