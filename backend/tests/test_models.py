"""Properties of the mapped schema that are invisible in a diff.

Every assertion here is for a failure that produces no error: a naive timestamp column, an enum
storing member names instead of wire values, a foreign key that leaves credentials behind after an
account is deleted. Each of those reads as ordinary code and shows up much later as wrong data.
"""

from __future__ import annotations

from datetime import date as date_type

import pytest
from sqlalchemy import CheckConstraint, DateTime, Enum

from app.models import Base, DailyCount, Device, DevicePlatform, Friendship, SyncBatch
from app.models.base import NAMING_CONVENTION
from app.models.columns import platform_enum
from app.platforms import PLATFORM_ENUM_NAME, Platform

ALL_TABLES = Base.metadata.tables


def test_the_platform_enum_stores_wire_values_not_member_names() -> None:
    """⚑ The single most consequential line in `app/models/columns.py`.

    SQLAlchemy's `Enum` persists member NAMES by default, so without `values_callable` the database
    would hold `INSTAGRAM` while the client, the wire contract and `test_platform_parity.py` all
    speak `instagram`. Nothing raises — `Platform.INSTAGRAM` round-trips through its own name
    perfectly well. It surfaces as a Phase 4 query that filters on the documented value and returns
    nothing.
    """
    assert set(platform_enum.enums) == {member.value for member in Platform}
    assert "INSTAGRAM" not in platform_enum.enums


def test_the_platform_type_has_the_name_the_client_contract_declares() -> None:
    """The type name ends up in migrations, in `pg_type`, and in every error message about a bad
    value. `app/platforms.py` owns it so it cannot drift from the members."""
    assert platform_enum.name == PLATFORM_ENUM_NAME


def test_daily_counts_and_sync_batches_share_ONE_platform_type() -> None:
    """Two `Enum` instances with one name are two Python objects claiming one `pg_type` row, and the
    duplicate only announces itself during `CREATE TYPE`."""
    assert ALL_TABLES["daily_counts"].c.platform.type is ALL_TABLES["sync_batches"].c.platform.type


def test_a_device_platform_is_not_a_scroll_platform() -> None:
    """`docs/SCHEMA.md` calls both columns `platform`. If they ever became one type, `android` would
    be a legal value for `daily_counts.platform`."""
    device_type = ALL_TABLES["devices"].c.platform.type

    assert isinstance(device_type, Enum)
    assert device_type.name == "device_platform"
    assert set(device_type.enums) == {member.value for member in DevicePlatform}
    assert set(device_type.enums).isdisjoint({member.value for member in Platform})


@pytest.mark.parametrize(
    ("table_name", "column_name"),
    [
        (table.name, column.name)
        for table in Base.metadata.sorted_tables
        for column in table.columns
        if isinstance(column.type, DateTime)
    ],
)
def test_every_timestamp_is_timezone_aware(table_name: str, column_name: str) -> None:
    """A naive column in Postgres does not store "no timezone", it stores "some timezone,
    unrecorded" — and attributing a count to a date in the user's zone (invariant 2) is this
    service's entire job.

    Parametrised per column rather than asserted in a loop so a failure names the offender.
    """
    column = ALL_TABLES[table_name].c[column_name]

    assert isinstance(column.type, DateTime)
    assert column.type.timezone is True, f"{table_name}.{column_name} is a naive timestamp"


def test_the_day_boundary_column_is_a_date_not_a_timestamp() -> None:
    """A calendar day in somebody's life, not an instant. Storing "today" as a timestamp would
    reintroduce the question of which zone to read it back in — the question the column exists to
    have already answered."""
    assert DailyCount.__table__.c.date.type.python_type is date_type


@pytest.mark.parametrize(
    "table_name",
    ["sessions", "devices", "daily_counts", "friendships", "sync_batches"],
)
def test_deleting_an_account_deletes_everything_that_references_it(table_name: str) -> None:
    """Without CASCADE, "delete my account" leaves live refresh tokens in `sessions` and counts in
    `daily_counts` — an account deletion that deletes the row a user can see and none of the rows
    they cannot."""
    foreign_keys = [
        fk for fk in ALL_TABLES[table_name].foreign_keys if fk.column.table.name == "users"
    ]

    assert foreign_keys, f"{table_name} does not reference users"
    for fk in foreign_keys:
        assert fk.ondelete == "CASCADE", f"{table_name}.{fk.parent.name} does not cascade"


def test_daily_counts_is_keyed_for_an_idempotent_upsert() -> None:
    """The composite key IS the idempotency: it is what lets a sync be one
    `INSERT ... ON CONFLICT DO UPDATE SET count = count + EXCLUDED.count`, which cannot lose an
    increment to a concurrent one from the user's second device."""
    key = [column.name for column in DailyCount.__table__.primary_key.columns]

    assert key == ["user_id", "date", "platform"]


def test_the_batch_id_is_the_whole_primary_key() -> None:
    """Deduplication is `ON CONFLICT (batch_id) DO NOTHING`. A wider key would let the same batch in
    twice under a different user or platform, and double-count it (invariant 1)."""
    key = [column.name for column in SyncBatch.__table__.primary_key.columns]

    assert key == ["batch_id"]


def test_the_prune_column_is_indexed() -> None:
    """Invariant 4's nightly prune is a range delete on `received_at`. Unindexed, it is a full scan
    of the busiest table here."""
    indexed = {tuple(c.name for c in index.columns) for index in SyncBatch.__table__.indexes}

    assert ("received_at",) in indexed


@pytest.mark.parametrize(
    ("table_name", "constraint_name"),
    [
        ("daily_counts", "count_non_negative"),
        ("sync_batches", "delta_positive"),
        ("friendships", "no_self_friendship"),
    ],
)
def test_the_database_refuses_states_the_product_cannot_produce(
    table_name: str, constraint_name: str
) -> None:
    """Counts only go up, a delta of zero is a round trip that changed nothing, and nobody is their
    own friend. The database is the last place that can say so."""
    checks = {
        constraint.name
        for constraint in ALL_TABLES[table_name].constraints
        if isinstance(constraint, CheckConstraint)
    }

    assert f"ck_{table_name}_{constraint_name}" in checks


def test_constraints_are_named_by_convention_not_by_postgres() -> None:
    """A migration that needs to DROP a constraint has to name it, and `daily_counts_user_id_fkey1`
    is a name nobody can predict from the model."""
    assert Base.metadata.naming_convention == NAMING_CONVENTION

    unnamed = [
        f"{table.name}.{constraint}"
        for table in Base.metadata.sorted_tables
        for constraint in table.constraints
        if constraint.name is None
    ]
    assert not unnamed


def test_a_composite_key_is_not_named_after_its_first_column() -> None:
    """`%(column_0_name)s` would name a three-column primary key after `user_id` alone, and
    `daily_counts` and `friendships` would then collide on their foreign keys."""
    assert DailyCount.__table__.primary_key.name == "pk_daily_counts"
    assert Friendship.__table__.primary_key.name == "pk_friendships"


def test_the_refresh_token_column_holds_a_hash_of_the_documented_width() -> None:
    """A database dump containing refresh tokens is an account takeover for every user at once. The
    width is pinned so a change of algorithm has to notice this column rather than truncate into
    it."""
    column = ALL_TABLES["sessions"].c.refresh_token_hash

    assert column.type.length == 64, "not SHA-256 hex any more — was that deliberate?"
    assert column.unique, "reuse detection (D63) needs this to be a database property"


def test_no_column_anywhere_could_hold_watched_content() -> None:
    """The promise that no content leaves the device is a property of the SCHEMA, not of the
    endpoints: there is no column it could go in. This test is the cheapest way to keep it that
    way."""
    suspicious = {"url", "title", "caption", "content", "video_id", "post_id", "author", "text"}

    offenders = [
        f"{table.name}.{column.name}"
        for table in Base.metadata.sorted_tables
        for column in table.columns
        if column.name in suspicious
    ]
    assert not offenders, (
        f"{offenders} looks like content, and no content ever reaches this service — only counts "
        f"(CLAUDE.md). If this is a false positive, rename the column rather than the test."
    )


def test_devices_and_friendships_exist_but_nothing_uses_them_yet() -> None:
    """D66's line: schema may lead its consumer, services may not. Their consumer is Phase 4."""
    assert Device.__tablename__ in ALL_TABLES
    assert Friendship.__tablename__ in ALL_TABLES
