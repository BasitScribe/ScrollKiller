"""`daily_counts` — the scoreboard. One row per user, per local day, per platform."""

from __future__ import annotations

from datetime import date
from uuid import UUID

from sqlalchemy import CheckConstraint, ForeignKey, Integer
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.columns import platform_enum
from app.platforms import Platform


class DailyCount(Base):
    """A running total, only ever moved by `+= delta` (invariant 1).

    ## The composite primary key is the idempotency
    `PK(user_id, date, platform)` is what makes `POST /sync` expressible as a single
    `INSERT ... ON CONFLICT (user_id, date, platform) DO UPDATE SET count = count + EXCLUDED.count`.
    That statement is atomic, needs no read-modify-write, and cannot lose an increment to a
    concurrent one — which matters because two devices on one account is a supported case, not an
    exotic one.

    Note what the key does NOT include: nothing identifying a batch. Deduplication is the job of
    `sync_batches`, one table over, and keeping the two separate is what lets a retried batch be
    recognised *before* this row is touched.
    """

    __tablename__ = "daily_counts"
    __table_args__ = (
        # A count that can go down is a count that has been overwritten rather than incremented,
        # which is invariant 1 violated. The database is the last place that can say so.
        CheckConstraint("count >= 0", name="count_non_negative"),
    )

    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )

    #: The user's LOCAL date, computed server-side from `users.timezone` at receive time
    #: (`app.timezones.local_date_for`) — invariant 2.
    #:
    #: A `DATE`, deliberately, not a timestamp. "Today" here is a calendar day in somebody's life,
    #: not an instant, and storing it as a timestamp would reintroduce the question of which
    #: timezone to read it back in — the exact question this column exists to have already answered.
    date: Mapped[date] = mapped_column(primary_key=True)

    platform: Mapped[Platform] = mapped_column(platform_enum, primary_key=True)

    count: Mapped[int] = mapped_column(Integer, server_default="0")
