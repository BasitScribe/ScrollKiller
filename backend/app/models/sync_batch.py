"""`sync_batches` — the dedupe ledger. This table IS invariant 1."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import CheckConstraint, ForeignKey, Integer
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.columns import platform_enum, utc_now_column
from app.platforms import Platform


class SyncBatch(Base):
    """One delta the client asked to be counted, recorded so it can only be counted once.

    ## How the idempotency actually works
    `POST /sync` does `INSERT INTO sync_batches ... ON CONFLICT (batch_id) DO NOTHING`, and applies
    the `daily_counts` increment **only if that insert reported a row**. The primary key is the
    entire mechanism: a retried batch — a network timeout, a Neon cold start, a device that never
    saw its ack — conflicts, inserts nothing, increments nothing, and is acked anyway. Retrying is
    therefore always safe, which is the property that lets the client be simple.

    `batch_id` is generated on the DEVICE (`uuid4`), before the first send attempt, and reused for
    every retry of that batch. A server-generated id could not deduplicate anything: the second
    attempt would get a second id and be counted twice.

    ## Why the rows are kept at all, and why not for long
    They are kept because a device can retry days later — a queue drained by a dead network goes out
    when one returns. They are pruned past 7 days (invariant 4) because after that the retry window
    is long closed and the row is only storage. The daily aggregate it produced lives forever in
    `daily_counts`; this is the receipt, not the record.
    """

    __tablename__ = "sync_batches"
    __table_args__ = (
        # Counts only ever go up, so a delta that is zero or negative did not come from the client
        # this schema is written for. Zero is included deliberately: it is not harmless, it is a
        # batch that consumed a round trip to change nothing, and it means whoever produced it has
        # a bug worth failing loudly over.
        CheckConstraint("delta > 0", name="delta_positive"),
    )

    #: Client-generated `uuid4`, and the primary key. See the class docstring.
    batch_id: Mapped[UUID] = mapped_column(primary_key=True)

    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
    )

    platform: Mapped[Platform] = mapped_column(platform_enum)

    #: Scrolls since the last ACKED batch. **Never an absolute count** — invariant 1.
    delta: Mapped[int] = mapped_column(Integer)

    #: The device's clock when the batch was assembled. Recorded, never trusted: it is diagnostic
    #: only, and no date is ever derived from it. `received_at` and `users.timezone` decide the day
    #: (invariant 2), because a device clock is user-settable and a scoreboard people compete on is
    #: exactly the thing somebody will try setting it for.
    client_ts: Mapped[datetime]

    #: Server clock at receipt, and the input to the day boundary.
    #:
    #: Indexed because the nightly prune (invariant 4) is a range delete on this column, and an
    #: unindexed one turns a scheduled job into a full scan of the busiest table here.
    received_at: Mapped[datetime] = utc_now_column(index=True)
