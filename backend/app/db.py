"""The database engine, and the four settings that make it survive its hosting.

## Two URLs, and they are NOT interchangeable
Neon puts PgBouncer in front of the database in *transaction* pooling mode. That gives us a
connection budget a free tier can actually serve, and takes away session state: a connection is
handed to whoever needs it next at the end of every transaction, so anything that lives on the
CONNECTION rather than in the transaction may not be there next time.

- `DATABASE_URL` — the **pooled** endpoint (`...-pooler...`). The app runs here. Many short
  transactions, no session state, which is exactly what a request handler does.
- `DATABASE_URL_DIRECT` — the **direct** endpoint. Alembic runs here, and only Alembic
  (`migrations/env.py`). DDL under a transaction pooler is where the confusing failures live:
  advisory locks are connection-scoped, so Alembic's version lock can be taken on one backend and
  released against another.

This module reads the POOLED one and nothing else. The direct URL is deliberately not importable
from here — see `migrations/env.py` for the other half.

## Why the connect args below are not optional decoration
asyncpg prepares every statement it runs and caches the result **per connection**, naming them in
numeric order (`__asyncpg_stmt_1__`). Behind PgBouncer neither half holds:

1. The connection you cached against is not the one you get back, so a cached plan can be replayed
   against a backend that never prepared it — `InvalidCachedStatementError`, and it appears under
   load rather than in testing.
2. The numeric names collide across clients sharing a backend, which fails as
   `DuplicatePreparedStatementError` on a statement that is perfectly valid.

So: both caches off, and names made unique per statement. `NullPool` is the part that is easiest to
mistake for a pessimisation — it is not. SQLAlchemy's own asyncpg documentation escalates it to a
warning, because a pooled connection behind PgBouncer accumulates prepared statements that nothing
will ever discard. Pooling twice is the bug; PgBouncer *is* the pool, and it is a process closer to
the database than we are.

`NullPool` also happens to answer Neon's autosuspend for free: there is no idle connection to go
stale, so nothing needs `pool_pre_ping` to notice that it did.
"""

from __future__ import annotations

from typing import Any
from uuid import uuid4

from sqlalchemy import text
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.pool import NullPool

from app.config import get_settings

#: The only URL scheme this service accepts. A bare `postgresql://` selects the SYNC psycopg
#: dialect, which is not installed, and the resulting error names psycopg — a package nobody here
#: has ever heard of — instead of naming the missing `+asyncpg`.
ASYNCPG_SCHEME = "postgresql+asyncpg://"


def _prepared_statement_name() -> str:
    """A unique name for every prepared statement.

    asyncpg's default is a per-connection counter, which produces the same handful of names in
    every client process. Behind a transaction pooler those processes share backends, so two
    clients preparing their own first statement both ask for `__asyncpg_stmt_1__` and the second
    one fails.
    """
    return f"__asyncpg_{uuid4()}__"


def connect_args_for(url: str) -> dict[str, Any]:
    """Driver-specific connect args for `url`.

    Keyed off the driver rather than applied unconditionally, because every argument here is an
    asyncpg argument and would be a `TypeError` on any other driver. The tests run against
    `sqlite+aiosqlite`, which is the reason this function is reachable with a non-asyncpg URL — but
    it is not a test hook: a URL naming a driver these args do not apply to genuinely must not
    receive them.
    """
    if not url.startswith(ASYNCPG_SCHEME):
        return {}
    return {
        # asyncpg's own per-connection cache.
        "statement_cache_size": 0,
        # SQLAlchemy's dialect-level cache, popped from connect_args by the dialect before asyncpg
        # ever sees it. Two independent caches, two separate switches — turning off one and
        # assuming the other followed is the trap.
        "prepared_statement_cache_size": 0,
        "prepared_statement_name_func": _prepared_statement_name,
    }


def create_engine(url: str) -> AsyncEngine:
    """Build the application engine for `url`.

    Constructing an engine performs no I/O — SQLAlchemy connects lazily, on first use. That is what
    lets this be called at import time on a machine with no database, and it is why `/readyz` has
    to issue a real statement to learn anything (a healthy-looking engine object proves nothing).
    """
    return create_async_engine(
        url,
        # PgBouncer is the pool. See the module docstring.
        poolclass=NullPool,
        connect_args=connect_args_for(url),
        # Never `echo=True` here, even temporarily: SQL logging prints bound parameters, and the
        # bound parameters on this service include a Google subject id and an email address (D60).
        echo=False,
    )


_engine: AsyncEngine | None = None
_sessionmaker: async_sessionmaker[AsyncSession] | None = None


def get_engine() -> AsyncEngine | None:
    """The process-wide engine, or None when no database is configured.

    None rather than an exception. A service with no `DATABASE_URL` is a real, supported state —
    it is what this repo has been in for every commit until this one, and what a contributor
    running the test suite is in — and `/readyz` reports it honestly as "not configured". Raising
    would turn that into a crash at import.
    """
    global _engine
    if _engine is None:
        url = get_settings().database_url
        if url is None:
            return None
        _engine = create_engine(url)
    return _engine


def get_sessionmaker() -> async_sessionmaker[AsyncSession] | None:
    """The session factory, or None when no database is configured.

    `expire_on_commit=False` because the alternative, on an async session, is an implicit lazy
    reload on the first attribute touched after a commit — which raises `MissingGreenlet` rather
    than loading, since there is no await point at attribute access. Handlers that return a model
    they just committed are the normal shape here, so the default would be a trap laid for 3d.
    """
    global _sessionmaker
    if _sessionmaker is None:
        engine = get_engine()
        if engine is None:
            return None
        _sessionmaker = async_sessionmaker(engine, expire_on_commit=False)
    return _sessionmaker


async def ping(engine: AsyncEngine) -> None:
    """Round-trip the smallest possible statement. Raises if the database cannot serve it.

    Takes the engine as an argument rather than fetching it, so the "is anything configured?"
    question and the "does it answer?" question stay separate — `/readyz` has to tell those two
    states apart, and a single function returning a bool could not.
    """
    async with engine.connect() as connection:
        await connection.execute(text("SELECT 1"))


async def dispose_engine() -> None:
    """Drop the engine and its pool. Called on application shutdown.

    Cheap under `NullPool` (there is no idle pool to drain), and kept anyway because the guarantee
    is about the next line of code, not this one: the day someone reintroduces a real pool, a
    process that exits without disposing leaves connections held open on a free tier that counts
    them.
    """
    global _engine, _sessionmaker
    if _engine is not None:
        await _engine.dispose()
    _engine = None
    _sessionmaker = None
