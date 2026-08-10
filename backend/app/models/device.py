"""`devices` — a push target. Created unused, on purpose.

Nothing writes this table in Phase 3. Its consumer is Phase 4's FCM taunt (D66), and it is here
anyway because of the line that ADR draws: **schema may lead its consumer, services may not.** A
table costs one CREATE TABLE in a migration that is being written regardless; a Redis client with no
friend groups to serve is infrastructure with nothing to do and a free-tier command budget to spend.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from uuid import UUID, uuid4

from sqlalchemy import Enum, ForeignKey, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.columns import wire_values


class DevicePlatform(StrEnum):
    """The OS a device runs.

    ⚑ **Not `app.platforms.Platform`, and the collision is the point of this comment.** That enum
    answers "which app was the reel watched in" — `instagram`, `youtube`. This one answers "what
    kind of phone is this". `docs/SCHEMA.md` calls both columns `platform`, so the two are one
    careless import away from being confused, and a `daily_counts.platform` of `android` would be
    filed against a platform that does not exist while reading like a typo.
    """

    ANDROID = "android"
    IOS = "ios"


#: Postgres ENUM for `devices.platform`. Named `device_platform` rather than `platform` for the
#: reason above — one of the two types has to say which it is, and it should be the one whose values
#: are not a wire contract with a shipped client.
device_platform_enum = Enum(
    DevicePlatform,
    name="device_platform",
    values_callable=wire_values,
    native_enum=True,
)


class Device(Base):
    """One installation of the app, and the token that can reach it."""

    __tablename__ = "devices"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)

    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
    )

    #: Nullable, because a device exists before it has a push token and may lose one: notification
    #: permission is optional on both platforms, and FCM rotates tokens on its own schedule. A NULL
    #: here means "known device, cannot be pushed to", which is a state Phase 4 must handle rather
    #: than a row that should not exist.
    #:
    #: `Text` rather than a bounded string — FCM registration tokens have no documented maximum and
    #: have grown before. A length limit on an opaque third-party token buys nothing and truncates
    #: silently on the day it is wrong.
    fcm_token: Mapped[str | None] = mapped_column(Text)

    platform: Mapped[DevicePlatform] = mapped_column(device_platform_enum)

    last_seen: Mapped[datetime]
