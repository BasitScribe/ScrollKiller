"""`users` — the account, and the timezone that decides what "today" means for it."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.columns import utc_now_column


class User(Base):
    """A signed-in account.

    Rows here are the only place this service stores anything about a person. Counts are counts;
    nothing about *what* was watched exists on the server at all (CLAUDE.md), and that is a property
    of the schema rather than of the endpoints — there is no column it could go in.
    """

    __tablename__ = "users"

    #: Generated in Python with `uuid4`, not by the database.
    #:
    #: A `gen_random_uuid()` server default would need the `pgcrypto` extension on Neon's free tier,
    #: and — more usefully — a client-side id exists BEFORE the INSERT, so a row and the rows that
    #: reference it can be built in one flush without a round trip to learn the parent's key.
    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)

    #: Google's `sub` claim: the stable, opaque subject id from the verified id_token (3c).
    #:
    #: This is the identity, NOT the email. A Google account's email address can change; `sub`
    #: cannot. Keying on email would silently create a second account for the same person, splitting
    #: their history in half at the moment they rename an address.
    google_sub: Mapped[str] = mapped_column(String(255), unique=True)

    #: Stored for display and support only. Never logged — the logging filter redacts it (D60).
    email: Mapped[str] = mapped_column(String(320))

    #: Both optional because Google does not guarantee either, and a leaderboard has to render a
    #: row for someone who has neither.
    display_name: Mapped[str | None] = mapped_column(String(128))
    avatar_url: Mapped[str | None] = mapped_column(Text)

    #: The user's IANA zone — `Asia/Kolkata`, not `+05:30`.
    #:
    #: ⚑ **This column IS invariant 2.** Every date this service files a count against is computed
    #: from it by `app.timezones.local_date_for`, server-side, at receive time. It is validated and
    #: normalised by `app.timezones.normalise_timezone` at the write boundary, so an unresolvable
    #: zone is rejected where it arrives rather than discovered later by every read.
    #:
    #: The `UTC` server default is a fallback that should never be observed: 3c sets the real zone
    #: at sign-in. It exists because NOT NULL needs an answer, and a wrong-but-valid zone misdates
    #: counts by hours where a NULL would fail the query outright — which is the louder failure, and
    #: therefore the one to leave the schema unable to produce.
    timezone: Mapped[str] = mapped_column(String(64), server_default="UTC")

    created_at: Mapped[datetime] = utc_now_column()
