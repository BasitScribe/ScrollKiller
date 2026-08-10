"""`docs/SCHEMA.md` and the models must describe the same database.

## Why a doc gets a test
`SCHEMA.md` is not decoration. It is where the shape of this service is agreed before it is built —
the sync flow beside it reads columns by name, and every ADR that argues about storage argues in its
terms. A design doc that has quietly stopped describing the code is worse than no doc: it is
confidently wrong, and it is the first thing a reader trusts.

The failure mode is entirely undramatic. Somebody adds a column in a migration, the models follow,
the doc does not, and six weeks later a question about what the server stores is answered from a
file that no longer knows. Nothing breaks. Nobody finds out.

So the doc is parsed and compared, in both directions, like `test_platform_parity.py` parses
`PlatformSpec.kt`. Same instinct: where a rule spans two things that can drift, the test has to look
at both.

## What to do when this fails
Update whichever one is wrong — usually the doc, because the code was the thing being changed. Do
not relax the comparison to "the doc is a subset", which is the tempting fix and the one that
removes all of the value: an under-specified doc passing forever is exactly the state this prevents.
"""

from __future__ import annotations

import re

import pytest

from app.models import Base
from tests.conftest import REPO_ROOT

SCHEMA_MD = REPO_ROOT / "docs/SCHEMA.md"

#: `users(id PK, google_sub UNIQUE, ...)` — a table declaration and its parenthesised body. Anchored
#: to the start of a line so prose mentioning `daily_counts(...)` in passing is not picked up.
TABLE_DECLARATION = re.compile(r"^(?P<table>\w+)\((?P<body>.+)\)\s*(?:--.*)?$", re.MULTILINE)


def _split_top_level(body: str) -> list[str]:
    """Split on commas that are not inside brackets.

    Needed because two of the six declarations carry commas the split must not see:
    `platform ENUM[instagram,youtube,...]` and the trailing `PK(user_id,date,platform)`.
    """
    parts: list[str] = []
    depth = 0
    current = ""

    for char in body:
        if char in "([":
            depth += 1
        elif char in ")]":
            depth -= 1

        if char == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char

    parts.append(current)
    return [part.strip() for part in parts if part.strip()]


def _documented_columns(body: str) -> set[str]:
    """Column names from a declaration body, skipping the trailing composite-key clause."""
    columns = set()
    for part in _split_top_level(body):
        if part.upper().startswith("PK("):
            continue  # a constraint, not a column
        columns.add(part.split()[0])
    return columns


def _documented_schema() -> dict[str, set[str]]:
    text = SCHEMA_MD.read_text(encoding="utf-8")
    return {
        match["table"]: _documented_columns(match["body"])
        for match in TABLE_DECLARATION.finditer(text)
    }


DOCUMENTED = _documented_schema()
MAPPED = {name: set(table.columns.keys()) for name, table in Base.metadata.tables.items()}


def test_the_doc_was_actually_parsed() -> None:
    """A parser that silently matches nothing turns this whole file into a set of tests that pass
    over empty collections — the classic way a guard stops guarding while staying green."""
    assert DOCUMENTED, f"parsed no table declarations out of {SCHEMA_MD}. Did its format change?"
    assert len(DOCUMENTED) >= 6


def test_the_same_tables_exist_in_both() -> None:
    assert set(DOCUMENTED) == set(MAPPED), (
        f"docs/SCHEMA.md and app/models/ disagree about which tables exist.\n"
        f"  documented but not mapped: {sorted(set(DOCUMENTED) - set(MAPPED))}\n"
        f"  mapped but not documented: {sorted(set(MAPPED) - set(DOCUMENTED))}"
    )


@pytest.mark.parametrize("table_name", sorted(DOCUMENTED))
def test_the_same_columns_exist_in_both(table_name: str) -> None:
    documented = DOCUMENTED[table_name]
    mapped = MAPPED.get(table_name, set())

    assert documented == mapped, (
        f"docs/SCHEMA.md and app/models/ disagree about {table_name}.\n"
        f"  documented but not mapped: {sorted(documented - mapped)}\n"
        f"  mapped but not documented: {sorted(mapped - documented)}"
    )


def test_the_documented_platform_enum_is_the_wire_contract() -> None:
    """`SCHEMA.md` spells the platform values out inline. They are the client's, so the same drift
    that `test_platform_parity.py` catches between Kotlin and Python can happen here in prose."""
    from app.platforms import Platform

    body = re.search(r"platform ENUM\[(?P<values>[^\]]+)\]", SCHEMA_MD.read_text(encoding="utf-8"))

    assert body is not None, "docs/SCHEMA.md no longer spells out the platform enum"
    documented = {value.strip() for value in body["values"].split(",")}
    assert documented == {member.value for member in Platform}
