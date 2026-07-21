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

## Docs map (read only what the task needs)
- docs/architecture.mermaid — system graph
- docs/SCHEMA.md — DB tables + sync flow
- docs/ROADMAP.md — phases, current status
- docs/DECISIONS.md — why-log (append-only ADRs)

## Session protocol
One phase per session. Start: read ROADMAP current phase. End: update ROADMAP status + append any new decision to DECISIONS.md. /clear between phases.

## Conventions
- Kotlin: package com.scrollkiller.*; ViewModel + Repository; no God-classes in the AccessibilityService — it only emits events.
- Python: ruff + mypy, async everywhere, routers/ services/ models/ layout, pytest.
- Commits: conventional (feat:, fix:, chore:).
