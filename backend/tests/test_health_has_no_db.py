"""Static guard: nothing reachable from `/health` may import the database.

This is the same posture as the client's GuiltPackTest (D45) — an invariant a
reviewer cannot check by eye gets checked by the build. Nobody catches a new
`from app.models import User` in review, because it looks like an ordinary
import; it only shows up later as a restart loop against a suspended database.

The walk is over the FIRST-PARTY import graph only. Third-party imports are
recorded and checked by name but not descended into: the question is what *our*
code chose to depend on, and `fastapi` pulling in something transitively is not
a decision this test has an opinion about.
"""

from __future__ import annotations

import ast
from pathlib import Path

from tests.conftest import BACKEND_ROOT

#: Import prefixes that mean "this touches the database".
#: `redis` is here for the same reason, one phase early: D66 defers it to Phase 4,
#: and this is the cheapest possible tripwire if it arrives sooner.
FORBIDDEN_PREFIXES: tuple[str, ...] = (
    "alembic",
    "app.db",
    "app.models",
    "asyncpg",
    "psycopg",
    "redis",
    "sqlalchemy",
)

ENTRY_MODULE = "app.routers.health"


def _module_to_path(module: str) -> Path | None:
    """Resolve a first-party dotted module name to its source file."""
    base = BACKEND_ROOT / Path(*module.split("."))
    if (file := base.with_suffix(".py")).is_file():
        return file
    if (pkg := base / "__init__.py").is_file():
        return pkg
    return None


def _imports_of(path: Path, module: str) -> set[str]:
    """Every module name imported by `path`, with relative imports resolved."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    package = module.rsplit(".", 1)[0] if path.name != "__init__.py" else module
    found: set[str] = set()

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            if node.level:  # relative import: `from .x import y`
                parts = package.split(".")
                root = ".".join(parts[: len(parts) - node.level + 1])
                base = f"{root}.{node.module}" if node.module else root
            else:
                base = node.module or ""
            found.add(base)
            # `from app.models import X` and `from app import models` must both
            # be caught, so record the submodule form of each name too.
            found.update(f"{base}.{alias.name}" for alias in node.names)
    return {name for name in found if name}


def _import_closure(entry: str) -> set[str]:
    """Transitively collect every module reachable from `entry`."""
    seen: set[str] = set()
    queue = [entry]
    while queue:
        module = queue.pop()
        if module in seen:
            continue
        seen.add(module)
        path = _module_to_path(module)
        if path is None:
            continue  # third-party or stdlib: recorded, not descended into
        for imported in _imports_of(path, module):
            if imported not in seen:
                queue.append(imported)
    return seen


def test_entry_module_exists() -> None:
    """If health.py is ever renamed, this test must fail loudly rather than pass
    vacuously over an empty closure — the classic way a guard stops guarding."""
    assert _module_to_path(ENTRY_MODULE) is not None, (
        f"{ENTRY_MODULE} not found. If liveness moved, update ENTRY_MODULE — do not "
        f"delete this test."
    )


def test_health_import_closure_never_reaches_the_database() -> None:
    closure = _import_closure(ENTRY_MODULE)
    offenders = sorted(
        name
        for name in closure
        if any(name == p or name.startswith(f"{p}.") for p in FORBIDDEN_PREFIXES)
    )
    assert not offenders, (
        f"/health can reach {offenders} through its imports.\n"
        f"Liveness must not touch the database: Neon's free tier autosuspends, so a "
        f"DB-backed health check reports a SLEEPING DATABASE as a DEAD APP and the "
        f"platform restarts a healthy service (D60). Put the work in "
        f"app/routers/readiness.py, which is allowed to do exactly this."
    )


def test_the_guard_can_actually_fail() -> None:
    """A guard nobody has ever seen fail is a guard nobody knows works.

    3a could only prove the matcher was not vacuous, against a list of made-up module names. Since
    3b there is a real positive control: `/readyz` genuinely reaches the database, through the same
    walk, from a router next to the one being guarded. If this comes back empty, the detector has
    stopped detecting and the green test above means nothing.
    """
    closure = _import_closure("app.routers.readiness")
    offenders = sorted(
        name
        for name in closure
        if any(name == p or name.startswith(f"{p}.") for p in FORBIDDEN_PREFIXES)
    )

    assert offenders, (
        "readiness no longer reaches the database through its imports. Either /readyz stopped "
        "doing a real query, or this walk stopped working — and if it is the second one, the "
        "test above is passing vacuously."
    )
    assert any(name.startswith("sqlalchemy") for name in offenders)
