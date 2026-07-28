# ScrollKiller (working name)

Anti-doomscroll app. Detects reel scrolling on-device, blocks at limits, unlocks via physical challenges, friend scroll-battles. Competitor teardown target: BrainPal (com.brainrot.android).

## Stack
- Android: Kotlin, Jetpack Compose, AccessibilityService, Room, min SDK 26
- iOS (later): SwiftUI + Shortcuts-automation interception; built via GitHub Actions macOS runner only (no local Mac)
- Backend: Python 3.12, FastAPI, SQLAlchemy 2.x async, Postgres (Neon, pgbouncer), Upstash Redis, FCM
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
- docs/DECISIONS.md — why-log (append-only ADRs); open the specific Dn, not the whole file
- docs/STORE_COPY.md — claims the listing may NOT make, and why (gate before Play submission)
- HANDOFF.md — current on-device checklist only when verifying or writing device steps

## Session protocol
One phase per session. Start: read **docs/PROJECT_MAP.md**, then ROADMAP current phase only. End: update ROADMAP status + PROJECT_MAP audit bullets + append any new decision to DECISIONS.md. /clear between phases.

## Conventions
- Kotlin: package com.scrollkiller.*; ViewModel + Repository; no God-classes in the AccessibilityService — it only emits events.
- Python: ruff + mypy, async everywhere, routers/ services/ models/ layout, pytest.
- Commits: conventional (feat:, fix:, chore:).
