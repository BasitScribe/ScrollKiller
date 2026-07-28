"""Readiness. This is the endpoint that IS allowed to touch the database.

Only readiness gates traffic. Splitting it from `/health` is what lets the
liveness endpoint's import closure be checked mechanically (see `health.py`), so
please keep DB work on this side of the line.

In 3a there is no database layer yet, so this returns 503 with an honest reason
rather than a stubbed 200. A readiness probe that reports ready before the
service can serve is worse than none: it is the signal a load balancer uses to
start sending real traffic.
"""

from __future__ import annotations

from typing import TypedDict

from fastapi import APIRouter, Response, status

from app.config import get_settings

router = APIRouter(tags=["ops"])


class ReadinessResponse(TypedDict):
    ready: bool
    detail: str


@router.get("/readyz")
async def readyz(response: Response) -> ReadinessResponse:
    """Readiness probe: can this process actually serve a request end to end?"""
    settings = get_settings()

    if settings.database_url is None:
        # The true state of the service in 3a. 3b replaces this branch with a
        # real `SELECT 1` round trip against the pooled connection.
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"ready": False, "detail": "database not configured"}

    response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {"ready": False, "detail": "database configured but no connectivity check until 3b"}
