"""The redaction denylist, tested before there is anything real to redact.

That ordering is the point of D60. By 3c this module is standing between Google
id_tokens, our own access JWTs and rotating refresh tokens on one side and a log
aggregator on the other. Writing the tests then means writing them against code
that is already running in production.
"""

from __future__ import annotations

import json
import logging

import pytest

from app.logging_config import REDACTED, JsonFormatter, configure_logging, is_sensitive, redact


@pytest.mark.parametrize(
    "field",
    [
        "Authorization",
        "access_token",
        "cookie",
        "email",
        "google_id_token",
        "password",
        "refresh_token",
        "session_id",
        "X-Refresh-Token",
        "client_secret",
    ],
)
def test_credential_fields_are_sensitive(field: str) -> None:
    assert is_sensitive(field)


@pytest.mark.parametrize(
    "field",
    ["user_id", "batch_id", "platform", "reel_count", "timezone", "day", "status"],
)
def test_domain_fields_are_not_sensitive(field: str) -> None:
    """The counts and ids this service exists to move must stay loggable, or the
    denylist becomes the thing people switch off (same argument as D47's guilt
    guard: a blunt rule everyone trusts beats a clever one with exceptions)."""
    assert not is_sensitive(field)


def test_redact_replaces_values_and_keeps_shape() -> None:
    cleaned = redact({"user_id": 7, "refresh_token": "rt_live_abc123"})
    assert cleaned == {"user_id": 7, "refresh_token": REDACTED}


def test_redact_recurses_into_nested_dicts() -> None:
    cleaned = redact({"request": {"headers": {"authorization": "Bearer x"}, "path": "/sync"}})
    assert cleaned == {"request": {"headers": {"authorization": REDACTED}, "path": "/sync"}}


def test_formatter_emits_one_json_object_with_extras_redacted() -> None:
    record = logging.LogRecord(
        name="app.sync",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="batch accepted",
        args=None,
        exc_info=None,
    )
    record.batch_id = "b-1"
    record.id_token = "eyJhbGciOi.SHOULD_NEVER_APPEAR"

    payload = json.loads(JsonFormatter().format(record))

    assert payload["level"] == "INFO"
    assert payload["logger"] == "app.sync"
    assert payload["message"] == "batch accepted"
    assert payload["batch_id"] == "b-1"
    assert payload["id_token"] == REDACTED
    assert "SHOULD_NEVER_APPEAR" not in json.dumps(payload)


def test_formatter_includes_exception_text() -> None:
    try:
        raise ValueError("boom")
    except ValueError:
        record = logging.LogRecord(
            name="app",
            level=logging.ERROR,
            pathname=__file__,
            lineno=1,
            msg="failed",
            args=None,
            exc_info=(ValueError, ValueError("boom"), None),
        )
    payload = json.loads(JsonFormatter().format(record))
    assert "ValueError" in payload["exception"]


def test_formatter_serialises_values_json_cannot_handle() -> None:
    """`default=str` is what stops a log call raising. A logger that can throw is
    a logger people wrap in try/except and then stop using."""
    record = logging.LogRecord(
        name="app",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="x",
        args=None,
        exc_info=None,
    )
    record.when = object()
    payload = json.loads(JsonFormatter().format(record))
    assert isinstance(payload["when"], str)


def test_configure_logging_installs_the_json_formatter() -> None:
    root = logging.getLogger()
    original = root.handlers[:]
    original_level = root.level
    try:
        configure_logging("warning")
        assert len(root.handlers) == 1
        assert isinstance(root.handlers[0].formatter, JsonFormatter)
        assert root.level == logging.WARNING
    finally:
        root.handlers = original
        root.setLevel(original_level)
