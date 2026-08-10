"""FastAPI application factory.

There are deliberately no domain endpoints here yet. 3a builds the pipeline and
the security posture first, because every item in that baseline is cheap on an
empty service and expensive to retrofit onto a running one (D60). Endpoints
arrive in 3c (auth) and 3d (sync).
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.db import dispose_engine
from app.logging_config import configure_logging
from app.routers import health, readiness
from app.timezones import assert_tzdata_available


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """Startup and shutdown.

    Nothing happens on the way up — deliberately. Connecting at startup would make the service
    refuse to boot while Neon is waking from autosuspend, turning a few seconds of cold start into a
    crash loop; `/readyz` is the right place to be unable to reach the database, because being
    not-ready is a state it can report and recover from. The engine connects on first use.

    On the way down the engine is disposed, so a redeploy hands its connections back rather than
    leaving them for the free tier's timeout to reap.
    """
    yield
    await dispose_engine()


def create_app() -> FastAPI:
    """Build the application. A factory, not a module-level singleton, so tests
    can construct an app against a different environment without reimporting."""
    settings = get_settings()
    configure_logging(settings.log_level)

    # Fail here, at construction, rather than per request. This service's whole
    # job is filing counts against a date in the USER's timezone (invariant 2),
    # and `zoneinfo` reads the SYSTEM tz database — which slim base images
    # frequently do not ship. Without it every user's timezone is unresolvable
    # and every count is misdated, but nothing crashes: the service starts, the
    # health probe passes, and it serves wrong dates. That is a worse failure
    # than not booting, so it is turned into not booting. See app/timezones.py
    # for the fix the exception message carries.
    assert_tzdata_available()

    app = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        # No content data ever reaches this service — only counts (CLAUDE.md).
        description="ScrollKiller scoreboard API. Counts only; never content.",
        lifespan=lifespan,
    )
    app.include_router(health.router)
    app.include_router(readiness.router)
    return app


app = create_app()
