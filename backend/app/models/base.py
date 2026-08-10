"""The declarative base, and the two conventions every table inherits from it.

## Named constraints, always
`MetaData(naming_convention=...)` makes Postgres name every index, unique, check, foreign key and
primary key by a rule instead of by its own defaults. This matters exactly once, and by then it is
too late to add: a migration that needs to DROP a constraint has to name it, and an
auto-generated name (`daily_counts_user_id_fkey1`) is one nobody can predict from the model. With a
convention, Alembic's autogenerate emits the name it can derive, and the same DDL produces the same
names on a fresh database as on a migrated one.

## Timezone-aware timestamps, always
Every `DateTime` here is `timezone=True` (`TIMESTAMPTZ`). A naive timestamp column in Postgres does
not store "no timezone", it stores "some timezone, unrecorded" — and this service's entire job is
attributing a count to a date in the USER's timezone (invariant 2). The rule is: **store UTC
instants, derive local dates with `app.timezones.local_date_for`**. The instant and the calendar day
are different questions and only one of them is a timestamp.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any, ClassVar

from sqlalchemy import DateTime, MetaData
from sqlalchemy.orm import DeclarativeBase

#: Deterministic constraint names. `%(column_0_N_name)s` spans all columns of a composite
#: constraint, which `daily_counts` and `friendships` both have — `%(column_0_name)s` would name a
#: three-column primary key after its first column and quietly collide.
NAMING_CONVENTION: dict[str, str] = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    """Declarative base for every model in this service."""

    metadata = MetaData(naming_convention=NAMING_CONVENTION)

    #: `Mapped[datetime]` means TIMESTAMPTZ here, everywhere, without anyone remembering to say so.
    #:
    #: Stated as a mapping rather than as a convention in review, because the failure it prevents is
    #: invisible in a diff: `Mapped[datetime]` on its own is a perfectly ordinary-looking line that
    #: produces a naive `TIMESTAMP` column. `tests/test_models.py` asserts the resulting metadata
    #: has no naive timestamp in it, so the rule holds even for a column that bypasses this map by
    #: passing its own type.
    type_annotation_map: ClassVar[dict[Any, Any]] = {datetime: DateTime(timezone=True)}
