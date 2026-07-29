# ScrollKiller (working name)

Anti-doomscroll app. Detects reel scrolling on-device, blocks at limits, unlocks via physical challenges, friend scroll-battles. Competitor teardown target: BrainPal (com.brainrot.android).

## Stack
- Android: Kotlin, Jetpack Compose, AccessibilityService, Room, min SDK 26
- iOS (later): SwiftUI + Shortcuts-automation interception; built via GitHub Actions macOS runner only (no local Mac)
- Backend: Python 3.13 (was 3.12 — D69), FastAPI, SQLAlchemy 2.x async, Postgres (Neon, pgbouncer), Upstash Redis + FCM **from Phase 4 only** (D66)
- CI: GitHub Actions (primary). Ephemeral Jenkins/EC2 = learning track only, never blocks releases.
- Cost constraint: ₹0 until Play Store ($25). Free tiers only. No paid services without explicit decision in DECISIONS.md.

## Architecture
Read docs/architecture.mermaid — full component graph. Summary: device is source of truth (offline-first Room); server is scoreboard only (auth, idempotent delta sync, leaderboards, push). No content data ever leaves device, only counts.

## Non-negotiable invariants
1. Sync is idempotent: every batch has client-generated UUID batch_id; server dedupes. Never POST absolute counts.
2. Day boundary = user's timezone (stored on users), computed server-side. Client trusts server on app open.
3. Leaderboard reads cached 30s. Never hit Redis per scroll.
4. Raw sync events pruned >7d; daily aggregates kept forever.
5. Play policy: AccessibilityService needs disclosure dialog before enabling. Never skip.
6. The block screen is ALWAYS exitable. Exit and Back must leave the blocked app from every block state, and dismissal must never depend on the count, a timer, the network, or a challenge succeeding. The overlay tears down when the user leaves the tracked app. A block that cannot be dismissed is a P0 — no product reason outranks this.

## Docs map (read only what the task needs)
- **docs/PROJECT_MAP.md — START HERE.** Living Obsidian mind map + project audit. Orient from this first so you do not burn tokens re-reading ROADMAP/DECISIONS wholesale. Drill into linked docs only for the slice you need. Update its short audit bullets when phase/platform/challenge/guilt/HANDOFF headlines change.
- docs/architecture.mermaid — system graph
- docs/SCHEMA.md — DB tables + sync flow
- docs/ROADMAP.md — phases, current status (status log is huge — prefer PROJECT_MAP + the CURRENT phase section)
- docs/DECISIONS.md — why-log (append-only ADRs, D1–D79, ~225 KB). **Never open whole — it is the biggest token sink here.** Use PROJECT_MAP's topic→ADR table to get candidate Dn, then read only those
- docs/STORE_COPY.md — claims the listing may NOT make, and why (gate before Play submission)
- HANDOFF.md — current on-device checklist only when verifying or writing device steps

## Session protocol
One phase per session. Start: read **docs/PROJECT_MAP.md**, then ROADMAP current phase only. End: update ROADMAP status + PROJECT_MAP audit bullets + append any new decision to DECISIONS.md. /clear between phases.

**Session hygiene (token discipline — the standing rule is fewest tokens, app still working):** read only what the task needs — CLAUDE.md + PROJECT_MAP should be enough to orient, and anything beyond that should be a deliberate choice, not a sweep; don't re-read settled ADRs unless the task actually touches that area; end every session with the HANDOFF + ROADMAP update and a fence-balance check (an unclosed code fence silently swallows the rest of the vault's render — D56; the command is in PROJECT_MAP).

## Conventions
- Kotlin: package com.scrollkiller.*; ViewModel + Repository; no God-classes in the AccessibilityService — it only emits events.
- Python: ruff + mypy, async everywhere, routers/ services/ models/ layout, pytest. Gates live in
  `backend/pyproject.toml`, never in the workflow, so local and CI read the same settings. All four
  block: `ruff check`, `ruff format --check`, `mypy app`, `pytest` (100% coverage floor).
- **`app/routers/health.py` must never reach the database** — liveness and readiness are separate
  modules and a test walks the import graph to keep them that way (D60). Put DB work in
  `readiness.py`.
- Commits: conventional (feat:, fix:, chore:).
