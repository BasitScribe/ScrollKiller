"""Liveness. This module must never reach the database.

READ THIS BEFORE ADDING AN IMPORT HERE.

`/health` answers one question: is this process alive and serving? It must not
touch the database, and the reason is not performance — it is that Neon's free
tier AUTOSUSPENDS an idle database. A health check that queries the DB reports a
*sleeping database* as a *dead application*, and the platform then restarts a
perfectly healthy service. The check causes the outage it exists to detect (D60).

Readiness is the other question and lives in `readiness.py`, deliberately in a
separate module so this one's import closure can be checked mechanically. That
check is `tests/test_health_has_no_db.py`, and it walks the import graph from
here: adding `from app.models import ...` to this file fails the build. Same
posture as the client's GuiltPackTest source walk (D45) — an invariant a reviewer
cannot verify by eye gets verified by the build.
"""

from __future__ import annotations

from typing import Literal, TypedDict

from fastapi import APIRouter

router = APIRouter(tags=["ops"])


class HealthResponse(TypedDict):
    status: Literal["ok"]


@router.get("/health")
async def health() -> HealthResponse:
    """Liveness probe. Static by design — no I/O of any kind."""
    return {"status": "ok"}
