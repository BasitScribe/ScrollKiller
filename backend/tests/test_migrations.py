"""The migration and the models must produce the same database.

## Why this is not paranoia
`migrations/versions/0001_initial_schema.py` deliberately does not import `app.models` — a migration
is a historical fact and must not change meaning when the models do. The cost of that independence
is that nothing stops the two from drifting: a column added to a model with no migration behind it
works perfectly in every test that uses `create_all`, and fails only against a real database that
was built the way production is built.

So the whole revision chain is rendered to SQL and compared against the mapped metadata. No database
is involved — Alembic's offline mode (`upgrade head --sql`) compiles the DDL a real run would emit,
which is also the exact artifact a reviewer should be reading before a migration touches anything.
"""

from __future__ import annotations

import ast
import io
import os
import re
from contextlib import redirect_stdout

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory

from app.models import Base
from app.platforms import Platform
from tests.conftest import BACKEND_ROOT

ALEMBIC_INI = BACKEND_ROOT / "alembic.ini"

#: Offline rendering compiles DDL without connecting, but the dialect still has to be the real one:
#: `sa.Uuid` renders as `UUID` on Postgres and `CHAR(32)` elsewhere, so a SQLite stand-in here would
#: check the wrong SQL.
OFFLINE_URL = "postgresql+asyncpg://user:password@localhost:5432/scrollkiller"


def _config() -> Config:
    config = Config(str(ALEMBIC_INI))
    config.set_main_option("script_location", str(BACKEND_ROOT / "migrations"))
    return config


@pytest.fixture(scope="module")
def rendered_sql() -> str:
    """The DDL `alembic upgrade head` would emit, as a string."""
    previous = os.environ.get("DATABASE_URL_DIRECT")
    os.environ["DATABASE_URL_DIRECT"] = OFFLINE_URL
    buffer = io.StringIO()
    try:
        with redirect_stdout(buffer):
            command.upgrade(_config(), "head", sql=True)
    finally:
        if previous is None:
            del os.environ["DATABASE_URL_DIRECT"]
        else:
            os.environ["DATABASE_URL_DIRECT"] = previous
    return buffer.getvalue()


def test_there_is_exactly_one_head() -> None:
    """Two heads means two people branched the migration history, and `upgrade head` stops being a
    well-defined instruction. Cheap to detect, confusing to discover during a deploy."""
    heads = ScriptDirectory.from_config(_config()).get_heads()

    assert len(heads) == 1, f"the migration history has branched: {heads}"


@pytest.mark.parametrize("table_name", sorted(Base.metadata.tables))
def test_every_mapped_table_is_created_by_a_migration(table_name: str, rendered_sql: str) -> None:
    """A model with no migration behind it works in every test that calls `create_all` and exists
    nowhere in production."""
    assert f"CREATE TABLE {table_name} " in rendered_sql


@pytest.mark.parametrize(
    ("table_name", "column_name"),
    [
        (table.name, column.name)
        for table in Base.metadata.sorted_tables
        for column in table.columns
    ],
)
def test_every_mapped_column_is_created_by_a_migration(
    table_name: str, column_name: str, rendered_sql: str
) -> None:
    body = _create_table_body(rendered_sql, table_name)

    assert re.search(
        rf"^\s*{column_name}\s", body, re.MULTILINE
    ), f"{table_name}.{column_name} is mapped but no migration creates it"


def _create_table_body(sql: str, table_name: str) -> str:
    match = re.search(rf"CREATE TABLE {table_name} \((?P<body>.*?)\n\);", sql, re.DOTALL)
    assert match is not None, f"no CREATE TABLE for {table_name}"
    return match["body"]


def test_the_migrated_platform_type_holds_exactly_the_wire_values(rendered_sql: str) -> None:
    """Written against the WHOLE rendered chain rather than against revision 0001, so it keeps
    working when a platform is added: an `ALTER TYPE platform ADD VALUE` in a later revision shows
    up in this same SQL. A test pinned to the initial revision would have to be edited instead,
    which teaches people to edit tests."""
    created = re.search(r"CREATE TYPE platform AS ENUM \((?P<values>[^)]+)\)", rendered_sql)
    assert created is not None, "no CREATE TYPE for the platform enum"

    values = {value.strip().strip("'") for value in created["values"].split(",")}
    values |= set(re.findall(r"ALTER TYPE platform ADD VALUE '([^']+)'", rendered_sql))

    assert values == {member.value for member in Platform}, (
        "the Postgres enum and the wire contract disagree. Adding a platform needs a NEW revision "
        "running `ALTER TYPE platform ADD VALUE ...` — note that on older Postgres this cannot run "
        "inside a transaction."
    )


def test_the_constraint_names_match_the_convention(rendered_sql: str) -> None:
    """The whole point of the naming convention is that a later migration can name what it drops. A
    migration that hardcodes different names produces a database the convention cannot describe."""
    mapped = {
        constraint.name
        for table in Base.metadata.sorted_tables
        for constraint in table.constraints
        if constraint.name is not None
    }
    missing = sorted(name for name in mapped if f"CONSTRAINT {name} " not in rendered_sql)

    assert (
        not missing
    ), f"the migration creates these under a different name (or not at all): {missing}"


def test_migrations_refuse_to_run_without_the_direct_url(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """⚑ The rule this whole sub-phase turns on.

    Alembic takes a CONNECTION-SCOPED advisory lock so two deploys cannot migrate at once. Under
    PgBouncer's transaction pooling, "the connection" is whatever backend the pooler hands out per
    transaction, so the lock can be taken on one backend and released against another — and the
    mutual exclusion silently is not there. It works on a small schema, in testing, most of the
    time, and fails during a concurrent deploy against a large table.

    A fallback to `DATABASE_URL` would make the wrong endpoint the convenient one. There is none,
    and this is the test that says so.
    """
    monkeypatch.delenv("DATABASE_URL_DIRECT", raising=False)
    monkeypatch.setenv("DATABASE_URL", "postgresql+asyncpg://user:pass@ep-x-pooler.neon.tech/db")

    with pytest.raises(RuntimeError, match="DATABASE_URL_DIRECT"), redirect_stdout(io.StringIO()):
        command.upgrade(_config(), "head", sql=True)


def test_the_migration_environment_cannot_see_the_pooled_url() -> None:
    """Read as source rather than behaviour, because the failure being prevented is somebody adding
    a convenience fallback — which would still let the test above pass while reintroducing the bug.

    Parsed rather than grepped: the module explains at length why it does NOT fall back to the
    pooled URL, so a plain text search finds the prose arguing against the thing it is looking for.
    Only string literals in CODE count, docstrings excluded.
    """
    source = (BACKEND_ROOT / "migrations/env.py").read_text(encoding="utf-8")
    tree = ast.parse(source)

    prose = {
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant)
    }
    literals = {
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and node not in prose
    }
    imported = {
        name
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom) and node.module
        for name in [node.module]
    }

    assert "DATABASE_URL_DIRECT" in literals, "env.py no longer reads the direct endpoint at all"
    assert "DATABASE_URL" not in literals, (
        "migrations/env.py reads the POOLED url. Alembic's version lock is connection-scoped and a "
        "transaction pooler does not preserve it — migrations run on the direct endpoint only."
    )
    assert "app.config" not in imported, (
        "env.py imports app.config, which exposes database_url. The two URLs are kept reachable "
        "from two different places on purpose."
    )


def test_timestamps_are_migrated_as_timestamptz(rendered_sql: str) -> None:
    """The naive/aware distinction survives being written out by hand in a migration, or it does
    not survive at all — this is the one place the model's `type_annotation_map` cannot help."""
    assert "TIMESTAMP WITH TIME ZONE" in rendered_sql
    assert not re.search(r"TIMESTAMP(?! WITH TIME ZONE)", rendered_sql)
