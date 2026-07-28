"""Configuration, read exclusively from the environment.

Every value here comes from an env var. There is deliberately no default that
could ever be a real credential — a placeholder that happens to work in
development is how a secret ends up committed, and this repo goes public after
launch (D68), so anything in it is permanent.

`.env` is read only as a local convenience and is gitignored; `.env.example`
carries the placeholder names and is the tracked file. In CI and in production
values arrive as real environment variables, never as a file (D60).
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["local", "ci", "staging", "production"]


class Settings(BaseSettings):
    """Application settings. Field names map to env vars case-insensitively."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        # Unknown env vars are ignored rather than fatal: the container runs in
        # an environment full of variables that are none of our business.
        extra="ignore",
    )

    app_name: str = "scrollkiller-api"
    environment: Environment = "local"
    log_level: str = "INFO"

    # Not used until 3b. Declared now so the readiness check has something honest
    # to report — `None` means "no database configured", which is the true state
    # of this service today and is why /readyz returns 503 rather than pretending.
    database_url: str | None = Field(default=None)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return the process-wide settings, parsed once.

    Cached because settings are immutable for the life of the process and
    re-reading the environment per request would be pure cost. Tests clear the
    cache via `get_settings.cache_clear()` when they need a different env.
    """
    return Settings()
