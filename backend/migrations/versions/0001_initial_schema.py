"""initial schema — every table in docs/SCHEMA.md

Revision ID: 0001
Revises:
Create Date: 2026-08-10

## Why this file does not import app.models
A migration is a historical fact: it describes the database as it was changed on one day. The models
describe the database as it should be *now*. Importing the models here would fuse the two, so a
column added next year would silently rewrite what this revision claims to have done — and a
migration whose meaning changes retroactively cannot be replayed onto a fresh database with any
confidence. Everything below is therefore spelled out, including the enum values, and that
duplication is the point rather than an oversight.

`tests/test_migrations.py` closes the loop the other way: it renders this chain to SQL and checks
the result against the models, so the two can be independent without being allowed to disagree.

## The enum types are created explicitly, before the tables
`sa.Enum` emits its own `CREATE TYPE` as a side effect of the first table that uses it — which makes
the DDL order-dependent, and breaks outright when two tables share one type (the second table tries
to create it again). Both `daily_counts` and `sync_batches` use `platform`, so the types are made up
front with plain SQL and every column below says `create_type=False`. Explicit, order-independent,
and greppable.

## Every constraint name is wrapped in `op.f()`
Alembic re-applies the naming convention it finds on `target_metadata` to names given here, so a
plain `name="ck_daily_counts_count_non_negative"` goes through the `ck_` rule twice and comes out as
`ck_daily_counts_ck_daily_counts_count_non_negative`. Not an error — a database whose
constraint names no later migration can predict. `op.f()` marks a name as already final. The primary
and foreign keys survive without it only by coincidence — their conventions do not interpolate
`%(constraint_name)s` — which is exactly why it is applied uniformly rather than where it showed up.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | None = None
depends_on: str | None = None

#: Values frozen as of this revision. Adding a platform later is a NEW revision doing
#: `ALTER TYPE platform ADD VALUE ...`, never an edit to this line.
PLATFORM_VALUES = ("instagram", "youtube", "snapchat", "facebook", "tiktok")
DEVICE_PLATFORM_VALUES = ("android", "ios")
FRIENDSHIP_STATUS_VALUES = ("pending", "accepted", "blocked")


def _enum(name: str, values: tuple[str, ...]) -> postgresql.ENUM:
    """Reference an already-created type. `create_type=False` is the load-bearing argument."""
    return postgresql.ENUM(*values, name=name, create_type=False)


def _create_type(name: str, values: tuple[str, ...]) -> None:
    rendered = ", ".join(f"'{value}'" for value in values)
    op.execute(f"CREATE TYPE {name} AS ENUM ({rendered})")


def upgrade() -> None:
    _create_type("platform", PLATFORM_VALUES)
    _create_type("device_platform", DEVICE_PLATFORM_VALUES)
    _create_type("friendship_status", FRIENDSHIP_STATUS_VALUES)

    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("google_sub", sa.String(length=255), nullable=False),
        sa.Column("email", sa.String(length=320), nullable=False),
        sa.Column("display_name", sa.String(length=128), nullable=True),
        sa.Column("avatar_url", sa.Text(), nullable=True),
        sa.Column("timezone", sa.String(length=64), server_default="UTC", nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
        sa.UniqueConstraint("google_sub", name=op.f("uq_users_google_sub")),
    )

    op.create_table(
        "sessions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("refresh_token_hash", sa.String(length=64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_sessions_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_sessions")),
        sa.UniqueConstraint("refresh_token_hash", name=op.f("uq_sessions_refresh_token_hash")),
    )
    op.create_index(op.f("ix_sessions_user_id"), "sessions", ["user_id"])

    op.create_table(
        "devices",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("fcm_token", sa.Text(), nullable=True),
        sa.Column("platform", _enum("device_platform", DEVICE_PLATFORM_VALUES), nullable=False),
        sa.Column("last_seen", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_devices_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_devices")),
    )
    op.create_index(op.f("ix_devices_user_id"), "devices", ["user_id"])

    op.create_table(
        "daily_counts",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("platform", _enum("platform", PLATFORM_VALUES), nullable=False),
        sa.Column("count", sa.Integer(), server_default="0", nullable=False),
        sa.CheckConstraint("count >= 0", name=op.f("ck_daily_counts_count_non_negative")),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_daily_counts_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("user_id", "date", "platform", name=op.f("pk_daily_counts")),
    )

    op.create_table(
        "friendships",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("friend_id", sa.Uuid(), nullable=False),
        sa.Column(
            "status",
            _enum("friendship_status", FRIENDSHIP_STATUS_VALUES),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint("user_id <> friend_id", name=op.f("ck_friendships_no_self_friendship")),
        sa.ForeignKeyConstraint(
            ["friend_id"],
            ["users.id"],
            name=op.f("fk_friendships_friend_id_users"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_friendships_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("user_id", "friend_id", name=op.f("pk_friendships")),
    )
    op.create_index(op.f("ix_friendships_friend_id"), "friendships", ["friend_id"])

    op.create_table(
        "sync_batches",
        sa.Column("batch_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("platform", _enum("platform", PLATFORM_VALUES), nullable=False),
        sa.Column("delta", sa.Integer(), nullable=False),
        sa.Column("client_ts", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint("delta > 0", name=op.f("ck_sync_batches_delta_positive")),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_sync_batches_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("batch_id", name=op.f("pk_sync_batches")),
    )
    op.create_index(op.f("ix_sync_batches_user_id"), "sync_batches", ["user_id"])
    # The prune in invariant 4 is a range delete on this column. Unindexed, the nightly job scans
    # the busiest table in the schema.
    op.create_index(op.f("ix_sync_batches_received_at"), "sync_batches", ["received_at"])


def downgrade() -> None:
    # Reverse creation order: every table below references `users`, and the enum types cannot be
    # dropped while a column still uses them.
    op.drop_table("sync_batches")
    op.drop_table("friendships")
    op.drop_table("daily_counts")
    op.drop_table("devices")
    op.drop_table("sessions")
    op.drop_table("users")

    op.execute("DROP TYPE friendship_status")
    op.execute("DROP TYPE device_platform")
    op.execute("DROP TYPE platform")
