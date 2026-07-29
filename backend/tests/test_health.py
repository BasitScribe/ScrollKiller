"""Runtime proof of the liveness/readiness split (D60).

`test_health_has_no_db.py` proves the property statically, by walking imports.
This file proves it dynamically, by pointing the service at a database that
cannot be reached and asserting /health does not care. Two angles on one
invariant, because the static check can be defeated by a runtime import and the
runtime check can be defeated by a lazy connection — neither alone is enough.
"""

from __future__ import annotations

import time

from fastapi.testclient import TestClient


def test_health_is_ok(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_health_survives_an_unreachable_database(unreachable_db_client: TestClient) -> None:
    """The load-bearing test. Neon's free tier autosuspends; if liveness ever
    touches the DB, a sleeping database reads as a dead app and the platform
    restarts a healthy service."""
    started = time.monotonic()
    response = unreachable_db_client.get("/health")
    elapsed = time.monotonic() - started

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    # A connection attempt to an unroutable host takes seconds at minimum before
    # it times out. Anything under a second proves no attempt was made.
    assert elapsed < 1.0, f"/health took {elapsed:.2f}s — it is doing I/O it should not"


def test_readyz_is_503_when_no_database_is_configured(no_db_env: None, client: TestClient) -> None:
    """503 is the honest answer in 3a, not a stub. A readiness probe reporting
    ready before the service can serve is the signal a load balancer uses to
    start sending real traffic."""
    response = client.get("/readyz")
    assert response.status_code == 503
    body = response.json()
    assert body["ready"] is False
    assert body["detail"] == "database not configured"


def test_readyz_is_still_503_when_a_database_is_configured(
    unreachable_db_client: TestClient,
) -> None:
    """3b replaces this branch with a real `SELECT 1`. Until then readiness must
    not claim success merely because a URL string exists."""
    response = unreachable_db_client.get("/readyz")
    assert response.status_code == 503
    assert response.json()["ready"] is False
