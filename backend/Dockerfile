# ScrollKiller API — multi-stage, non-root, digest-pinned.
#
# Written before the first endpoint exists, on purpose (D60). Twenty lines now;
# a migration with file-permission surprises if it is retrofitted onto a running
# service.
#
# TAG PINS ARE NOT PINS. `python:3.13-slim-bookworm` is a moving target — the
# same tag serves different bytes next week, so a build that passed Trivy today
# can ship a different image tomorrow with nobody having changed a line. The
# digest is the actual pin; the tag beside it is a comment for humans.
# Bump with:  docker pull python:3.13-slim-bookworm
#             docker inspect --format='{{index .RepoDigests 0}}' python:3.13-slim-bookworm
# Dependabot keeps this honest once the repo is public.

# ---------------------------------------------------------------------------
# Stage 1 — builder. Compilers and headers live and die here.
# ---------------------------------------------------------------------------
FROM python:3.13-slim-bookworm@sha256:9d7f287598e1a5a978c015ee176d8216435aaf335ed69ac3c38dd1bbb10e8d64 AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /build

# Only the lockfile, so this layer is cached until dependencies actually change.
COPY requirements.txt ./

# --require-hashes: every package, including transitive ones, must match the
# exact artifact hash recorded in requirements.txt. A version pin trusts the
# index to keep serving the same bytes; a hash pin does not.
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --require-hashes -r requirements.txt

# ---------------------------------------------------------------------------
# Stage 2 — runtime. No compilers, no pip cache, no test runner, no linters.
# Every package present is attack surface Trivy has to scan and someone has to
# keep patched, so the dev dependencies deliberately never reach this stage.
# ---------------------------------------------------------------------------
FROM python:3.13-slim-bookworm@sha256:9d7f287598e1a5a978c015ee176d8216435aaf335ed69ac3c38dd1bbb10e8d64 AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH"

# ⚑ tzdata IS A HARD RUNTIME DEPENDENCY OF THIS SERVICE, not an optional nicety.
#
# This is a *-slim base, which strips the IANA timezone database. `zoneinfo` does
# not carry its own copy — it reads the system one — and this service's entire job
# is filing every count against a date in the USER's timezone (invariant 2). With
# no database, every user's timezone is unresolvable and every count is misdated.
#
# The nasty part is that nothing crashes on its own: the container starts, the
# health probe passes, and it quietly serves wrong dates. So `app/timezones.py`
# asserts the database is present at startup and REFUSES TO BOOT without it —
# which means that without this line the deploy fails outright rather than
# subtly. That is the intended trade (loud beats subtle), and this line is the
# fix the exception message names.
#
# Installed in the RUNTIME stage, not the builder: the venv is what gets copied
# forward, and /usr/share/zoneinfo is not in it.
#
# Combined with the useradd into one layer, and the apt lists removed in the same
# RUN, so no package index survives into the shipped image for Trivy to scan or
# for anyone to reuse.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/* \
    # A fixed high UID, not a name lookup. Kubernetes and some scanners assert on
    # the numeric UID, and `runAsNonRoot` cannot verify a username.
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /srv/app

COPY --from=builder --chown=10001:10001 /opt/venv /opt/venv
COPY --chown=10001:10001 app ./app

# Drop privileges BEFORE the process starts. Nothing below this line runs as root.
USER 10001:10001

EXPOSE 8000

# Liveness only — this must never grow a database check. Neon's free tier
# autosuspends, so a DB-backed health check reports a sleeping database as a
# dead app and the orchestrator restarts a healthy service (D60).
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).status==200 else 1)"]

# No secret is ever passed as a build ARG — build args land in image history and
# `docker history` reads them straight back out. Runtime env only.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
