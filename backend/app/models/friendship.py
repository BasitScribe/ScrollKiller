"""`friendships` — Phase 4's edge list. Created unused (see `device.py` for why that is allowed)."""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from uuid import UUID

from sqlalchemy import CheckConstraint, Enum, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.columns import utc_now_column, wire_values


class FriendshipStatus(StrEnum):
    """Where a friend request has got to.

    Unlike `Platform`, these strings are **not** a wire contract with a shipped client — nothing
    sends or stores them yet. They can be renamed with a migration and no compatibility window,
    which is a freedom worth noting explicitly so nobody treats this enum as being as frozen as
    that one.
    """

    PENDING = "pending"
    ACCEPTED = "accepted"
    BLOCKED = "blocked"


friendship_status_enum = Enum(
    FriendshipStatus,
    name="friendship_status",
    values_callable=wire_values,
    native_enum=True,
)


class Friendship(Base):
    """A directed edge. Two rows make a mutual friendship.

    Directed rather than symmetric, because `blocked` is inherently one-way: A blocking B is not B
    blocking A, and a single symmetric row could not express it. The cost is that "are these two
    friends" is a two-row question — which Phase 4 pays once, in a query, instead of paying forever
    in a schema that cannot represent a block.
    """

    __tablename__ = "friendships"
    __table_args__ = (
        # Self-friendship is not a state with a meaning; it is a bug that would put a user on their
        # own leaderboard twice.
        CheckConstraint("user_id <> friend_id", name="no_self_friendship"),
    )

    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )

    #: Indexed because the reverse question — "who has added me?" — is the one an incoming-requests
    #: screen asks, and the composite primary key cannot serve it: a PK index is only usable from
    #: its leading column.
    friend_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
        index=True,
    )

    status: Mapped[FriendshipStatus] = mapped_column(friendship_status_enum)

    created_at: Mapped[datetime] = utc_now_column()
