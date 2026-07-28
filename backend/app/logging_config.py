"""Structured JSON logging with a redaction denylist.

Established before there are any tokens to leak, which is the whole argument of
D60: this is ten lines now and an audit of shipped log output later. Phase 3c
adds Google id_token verification, our own access JWT and rotating refresh
tokens; by then the redaction has to already exist, because the failure mode is
a token sitting in a log aggregator that someone has to go and purge.
"""

from __future__ import annotations

import json
import logging
from typing import Any

#: Substrings that mark a field as never-loggable. Matched case-insensitively
#: against the field name, so `refresh_token`, `X-Refresh-Token` and
#: `google_id_token` are all covered by one entry. Deliberately a denylist of
#: substrings rather than exact keys: an exact-key list silently misses the next
#: field someone adds, and the cost of over-redacting a log line is nil.
SENSITIVE_FIELD_MARKERS: frozenset[str] = frozenset(
    {
        "authorization",
        "cookie",
        "credential",
        "email",
        "id_token",
        "password",
        "refresh",
        "secret",
        "session",
        "token",
    }
)

REDACTED = "***redacted***"


def is_sensitive(field_name: str) -> bool:
    """True if `field_name` looks like it carries a credential or PII."""
    lowered = field_name.lower()
    return any(marker in lowered for marker in SENSITIVE_FIELD_MARKERS)


def redact(payload: dict[str, Any]) -> dict[str, Any]:
    """Return `payload` with sensitive values replaced, recursing into dicts."""
    cleaned: dict[str, Any] = {}
    for key, value in payload.items():
        if is_sensitive(key):
            cleaned[key] = REDACTED
        elif isinstance(value, dict):
            cleaned[key] = redact(value)
        else:
            cleaned[key] = value
    return cleaned


class JsonFormatter(logging.Formatter):
    """Emit one JSON object per line, with `extra` fields redacted."""

    #: Attributes every LogRecord carries. Anything NOT in here was passed by the
    #: caller as `extra=` and is therefore application data worth emitting.
    _STANDARD_ATTRS: frozenset[str] = frozenset(
        logging.LogRecord("", 0, "", 0, "", None, None).__dict__
    ) | {"message", "asctime", "taskName"}

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        extras = {k: v for k, v in record.__dict__.items() if k not in self._STANDARD_ATTRS}
        if extras:
            payload.update(redact(extras))
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging(level: str) -> None:
    """Install the JSON formatter on the root handler."""
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())
