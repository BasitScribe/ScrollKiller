"""The engine's configuration is load-bearing, so it is asserted rather than trusted.

Every setting in `app/db.py` exists to survive PgBouncer in transaction pooling mode, and each one
fails in a way that does not look like a configuration problem: `InvalidCachedStatementError` under
load, `DuplicatePreparedStatementError` on a valid query, prepared statements piling up on a shared
backend. None of those show up in review, and none of them show up on a developer's direct
connection either — which is exactly why they belong in a test rather than in a comment.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy.exc import OperationalError
from sqlalchemy.pool import NullPool

from app import db
from tests.conftest import SQLITE_URL

POOLED_URL = "postgresql+asyncpg://user:pass@ep-x-pooler.neon.tech/db"


def test_asyncpg_url_gets_both_caches_disabled() -> None:
    """Two independent caches, two switches. Disabling one and assuming the other followed is the
    trap this asserts against."""
    args = db.connect_args_for(POOLED_URL)

    assert args["statement_cache_size"] == 0, "asyncpg's own per-connection cache is still on"
    assert args["prepared_statement_cache_size"] == 0, "SQLAlchemy's dialect cache is still on"


def test_asyncpg_url_gets_unique_prepared_statement_names() -> None:
    """asyncpg's default names are a per-connection counter, so two clients sharing a pooled backend
    both ask for `__asyncpg_stmt_1__` and the second one fails."""
    name_func = db.connect_args_for(POOLED_URL)["prepared_statement_name_func"]

    names = {name_func() for _ in range(100)}

    assert len(names) == 100, "prepared statement names collide"
    assert all(name.startswith("__asyncpg_") for name in names)


def test_a_non_asyncpg_url_gets_no_asyncpg_arguments() -> None:
    """Every argument above is an asyncpg argument and would be a TypeError on another driver."""
    assert db.connect_args_for(SQLITE_URL) == {}


def test_the_engine_does_not_pool() -> None:
    """`NullPool` is the part that reads like a pessimisation and is not.

    PgBouncer IS the pool, and it runs closer to the database than we do. Pooling on top of it
    accumulates prepared statements on connections nothing will ever discard — SQLAlchemy's own
    asyncpg documentation escalates this to a warning rather than a suggestion.
    """
    engine = db.create_engine(POOLED_URL)

    assert isinstance(engine.pool, NullPool)


def test_building_an_engine_opens_no_connection() -> None:
    """Constructing an engine must be pure — it happens at import, on machines with no database.

    This is also why `/readyz` cannot simply check that an engine exists: a healthy-looking engine
    object is not evidence of anything.
    """
    engine = db.create_engine("postgresql+asyncpg://user:pass@203.0.113.1:5432/nope")

    assert engine.url.host == "203.0.113.1"


def test_sql_echo_is_off() -> None:
    """`echo=True` prints bound parameters, and the bound parameters here include a Google subject
    id and an email address (D60)."""
    assert db.create_engine(POOLED_URL).echo is False


def test_no_engine_without_a_configured_database(no_db_env: None) -> None:
    """A service with no DATABASE_URL is a supported state, not a crash: it is what every commit in
    this repo before 3b was in, and what a contributor running this suite is in."""
    assert db.get_engine() is None
    assert db.get_sessionmaker() is None


def test_the_engine_is_memoised(sqlite_db_env: None) -> None:
    assert db.get_engine() is db.get_engine()
    assert db.get_sessionmaker() is db.get_sessionmaker()


def test_sessions_do_not_expire_on_commit(sqlite_db_env: None) -> None:
    """On an async session the default would be a trap: the implicit reload at the first attribute
    touched after a commit raises `MissingGreenlet`, because attribute access has no await point."""
    sessionmaker = db.get_sessionmaker()

    assert sessionmaker is not None
    assert sessionmaker.kw["expire_on_commit"] is False


async def test_ping_round_trips_against_a_real_database(sqlite_db_env: None) -> None:
    engine = db.get_engine()

    assert engine is not None
    await db.ping(engine)  # raises if the database cannot serve `SELECT 1`


async def test_ping_raises_when_the_database_cannot_be_reached(tmp_path: Path) -> None:
    """The failure has to propagate out of `ping`. `/readyz` is the only place allowed to decide
    that a database which cannot answer is a 503 rather than an exception — burying it here would
    make every future caller inherit that decision."""
    unreachable = tmp_path / "no-such-directory" / "scrollkiller.db"
    engine = db.create_engine(f"sqlite+aiosqlite:///{unreachable}")

    with pytest.raises(OperationalError):
        await db.ping(engine)


async def test_dispose_releases_the_engine(sqlite_db_env: None) -> None:
    """Disposal has to clear the memo as well as the pool, or the next `get_engine()` hands back an
    engine whose connections have already been returned."""
    engine = db.get_engine()
    assert engine is not None

    await db.dispose_engine()

    assert db._engine is None
    assert db._sessionmaker is None


async def test_disposing_an_unconfigured_service_is_a_no_op(no_db_env: None) -> None:
    """Shutdown runs whether or not anything was ever configured."""
    await db.dispose_engine()

    assert db._engine is None
