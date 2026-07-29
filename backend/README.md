# ScrollKiller API

Scoreboard only. The device is the source of truth (offline-first Room); this
service stores **counts, never content**. See `CLAUDE.md` for the invariants and
`docs/SCHEMA.md` for the tables.

**Status: 3a.** Skeleton, quality gates and security baseline. There are no
domain endpoints yet — only `/health` and `/readyz`. Auth arrives in 3c, sync in
3d.

## Setup

```bash
cd backend
py -3.13 -m venv .venv                      # Windows;  python3.13 -m venv .venv elsewhere
.venv/Scripts/python -m pip install --require-hashes -r requirements.txt -r requirements-dev.txt
cp .env.example .env                        # optional; every value has a safe default
```

## The gates — all four block, none is advisory

```bash
ruff check .            # lint + import order + flake8-bandit (the `S` rules)
ruff format --check .   # formatting
mypy app                # strict
pytest                  # tests + 100% coverage floor
```

Run them before pushing. They are identical to what `backend.yml` runs, and
Python is pinned to **3.13** specifically so local and CI cannot disagree (D69).

## Changing dependencies

Edit the `.in` file, never the `.txt` — the `.txt` files are generated and carry
per-artifact hashes.

```bash
pip install pip-tools
pip-compile --generate-hashes --strip-extras --output-file=requirements.txt requirements.in
pip-compile --generate-hashes --strip-extras --output-file=requirements-dev.txt requirements-dev.in
```

Runtime and dev are separate so the container never ships a test runner, a linter
or a type checker — every package in an image is attack surface someone has to
keep patched.

## Container

```bash
docker build -t scrollkiller-api:dev .
docker run --rm -p 8000:8000 scrollkiller-api:dev
curl localhost:8000/health   # {"status":"ok"}
curl -i localhost:8000/readyz # 503 — no database configured, which is true in 3a
```

Multi-stage, digest-pinned base, non-root `UID 10001`. To verify the last one
actually applied rather than merely being declared:

```bash
docker run --rm scrollkiller-api:dev id -u   # 10001
```

## Two rules that are enforced by tests, not by review

- **`/health` must never touch the database.** Neon's free tier autosuspends, so
  a DB-backed liveness check reports a *sleeping database* as a *dead app* and
  the platform restarts a healthy service. `tests/test_health_has_no_db.py` walks
  the import graph from `app/routers/health.py` and fails the build on
  `sqlalchemy`, `asyncpg`, `app.models`, `app.db` or `redis`.
  `tests/test_health.py` proves the same thing at runtime against an unroutable
  database. Readiness work goes in `app/routers/readiness.py`, which is allowed
  to do exactly this. (D60)
- **The backend is not a Gradle module.** This repo is a monorepo so that a sync
  contract change lands as one atomic PR (D59), but the Android tree must still
  build with no Python installed and this one with no JDK.
  `tests/test_repo_layout.py` asserts it.
