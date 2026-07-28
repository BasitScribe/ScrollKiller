from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import create_app

#: backend/ — the directory holding pyproject.toml, not the repo root.
BACKEND_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = BACKEND_ROOT.parent


@pytest.fixture(autouse=True)
def _clean_settings_cache() -> Iterator[None]:
    """Settings are process-cached (`lru_cache`), so a test that changes the
    environment would otherwise leak into the next one. Clear on both sides."""
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture
def client() -> Iterator[TestClient]:
    """A client for an app built with the ambient environment."""
    with TestClient(create_app()) as test_client:
        yield test_client


@pytest.fixture
def unreachable_db_client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    """A client whose DATABASE_URL points somewhere that cannot be reached.

    This is what makes the /health test a real proof rather than a restatement:
    if liveness ever grew a database dependency, this fixture is the environment
    in which it would hang or fail. 203.0.113.0/24 is TEST-NET-3 (RFC 5737),
    reserved for documentation and guaranteed not to be routable.
    """
    monkeypatch.setenv("DATABASE_URL", "postgresql+asyncpg://u:p@203.0.113.1:5432/nope")
    get_settings.cache_clear()
    with TestClient(create_app()) as test_client:
        yield test_client


@pytest.fixture
def no_db_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """No DATABASE_URL at all — the true state of the service in 3a."""
    monkeypatch.delenv("DATABASE_URL", raising=False)
    # A developer's local .env would otherwise reintroduce it behind the test's
    # back; point pydantic-settings at a file that does not exist.
    monkeypatch.chdir(os.fspath(BACKEND_ROOT / "tests"))
    get_settings.cache_clear()
    yield
