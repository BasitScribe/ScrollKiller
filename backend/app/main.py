"""FastAPI application factory.

There are deliberately no domain endpoints here yet. 3a builds the pipeline and
the security posture first, because every item in that baseline is cheap on an
empty service and expensive to retrofit onto a running one (D60). Endpoints
arrive in 3c (auth) and 3d (sync).
"""

from __future__ import annotations

from fastapi import FastAPI

from app.config import get_settings
from app.logging_config import configure_logging
from app.routers import health, readiness
from app.timezones import assert_tzdata_available


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
    )
    app.include_router(health.router)
    app.include_router(readiness.router)
    return app


app = create_app()
