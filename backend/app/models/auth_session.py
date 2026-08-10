"""`sessions` — refresh-token records.

## Why the class is `AuthSession` and the table is `sessions`
`Session` is SQLAlchemy's own unit-of-work class, and this codebase is full of `AsyncSession`. A
model named `Session` would make `session: Session` ambiguous in every signature it appeared in, and
the two meanings — "a database conversation" and "a user's logged-in period" — are close enough to
read past. The table keeps the name `docs/SCHEMA.md` gives it; only the Python identifier moves.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.columns import utc_now_column


class AuthSession(Base):
    """One refresh token's life: issued, maybe revoked, eventually expired."""

    __tablename__ = "sessions"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)

    #: `ondelete="CASCADE"` — deleting an account must not leave live credentials behind. This is
    #: the difference between "delete my account" and "delete my account, but anything already
    #: holding a refresh token keeps working".
    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
    )

    #: A SHA-256 hex digest, never the token.
    #:
    #: 64 chars because that is what hex-encoded SHA-256 is, and the length is pinned so a future
    #: change of algorithm has to notice this column rather than silently truncate into it. Hashing
    #: is not belt-and-braces here: a refresh token is a bearer credential with a long life, and a
    #: database dump that contained them would be an account takeover for every user at once.
    #:
    #: Unique so that reuse detection (D63) is a database property rather than a query someone has
    #: to remember to run.
    refresh_token_hash: Mapped[str] = mapped_column(String(64), unique=True)

    expires_at: Mapped[datetime]

    #: NULL means live. A revoked session is kept rather than deleted, because the reuse detection
    #: in D63 works by recognising a token it has ALREADY retired — delete the row and a replayed
    #: token becomes indistinguishable from one that never existed, which is the case that must
    #: revoke the whole family.
    revoked_at: Mapped[datetime | None]

    created_at: Mapped[datetime] = utc_now_column()
