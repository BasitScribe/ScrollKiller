"""SQLAlchemy models — every table in `docs/SCHEMA.md`.

## Importing this package is not optional bookkeeping
`Base.metadata` only knows about a table once the module defining it has been imported. Alembic's
autogenerate compares the database against that metadata, so a model in a file nobody imports is
invisible to it — and the symptom is not an error, it is a migration that quietly proposes to DROP
the table. Every model is therefore re-exported here, and `migrations/env.py` imports this package
rather than any individual module.

## Note for whoever adds the next one
Importing anything from here into `app/routers/health.py` fails the build on purpose. See that
module's docstring.
"""

from __future__ import annotations

from app.models.auth_session import AuthSession
from app.models.base import Base
from app.models.columns import platform_enum
from app.models.daily_count import DailyCount
from app.models.device import Device, DevicePlatform, device_platform_enum
from app.models.friendship import Friendship, FriendshipStatus, friendship_status_enum
from app.models.sync_batch import SyncBatch
from app.models.user import User

__all__ = [
    "AuthSession",
    "Base",
    "DailyCount",
    "Device",
    "DevicePlatform",
    "Friendship",
    "FriendshipStatus",
    "SyncBatch",
    "User",
    "device_platform_enum",
    "friendship_status_enum",
    "platform_enum",
]
