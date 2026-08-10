"""Readiness. This is the endpoint that IS allowed to touch the database.

Only readiness gates traffic. Splitting it from `/health` is what lets the liveness endpoint's
import closure be checked mechanically (see `health.py`), so please keep DB work on this side of the
line.

Since 3b this does a real round trip. The engine object proves nothing on its own — SQLAlchemy
connects lazily, so a perfectly healthy-looking engine can be pointed at a host that does not exist.
The only way to answer "can this process serve a request end to end" is to make it serve one.
"""

from __future__ import annotations

import asyncio
import logging
from typing import TypedDict

from fastapi import APIRouter, Response, status

from app.db import get_engine, ping

logger = logging.getLogger(__name__)

router = APIRouter(tags=["ops"])

#: How long the probe waits before calling the database unreachable.
#:
#: Neon's free tier autosuspends and takes seconds to wake, so a cold start can legitimately blow
#: this budget and report not-ready. That is the correct answer: during a wake-up the service
#: genuinely cannot serve, and the orchestrator retries. What must not happen is the probe HANGING —
#: an unroutable host has no timeout of its own worth relying on, and a readiness check that never
#: returns is indistinguishable from one that fails, except that it also occupies a worker.
READINESS_TIMEOUT_S = 5.0


class ReadinessResponse(TypedDict):
    ready: bool
    detail: str


@router.get("/readyz")
async def readyz(response: Response) -> ReadinessResponse:
    """Readiness probe: can this process actually serve a request end to end?"""
    engine = get_engine()

    if engine is None:
        # Not a failure to connect — nothing was ever configured. Worth distinguishing in the
        # response, because the two have completely different fixes and this string is the only
        # thing an operator sees.
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"ready": False, "detail": "database not configured"}

    try:
        await asyncio.wait_for(ping(engine), timeout=READINESS_TIMEOUT_S)
    except Exception:
        # Deliberately broad. A probe's job is to answer, and every failure mode here — a refused
        # connection, a DNS miss, an auth rejection, a timeout, a driver raising something we have
        # not thought of — has exactly one correct answer: not ready. Letting an exception escape
        # would return 500, which reads as "the application is broken" rather than "the database is
        # not there yet", and on a platform that restarts unhealthy services the difference matters.
        #
        # `exception` so the traceback reaches the log even though the response deliberately does
        # not carry it: the reason a database is unreachable frequently contains the host, and this
        # endpoint is unauthenticated (D60).
        logger.exception("readiness probe failed")
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"ready": False, "detail": "database unreachable"}

    return {"ready": True, "detail": "ok"}
