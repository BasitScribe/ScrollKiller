# Decisions (append-only)

D1. Android-first, iOS deferred until app self-funds $99/yr. iOS interception = Shortcuts automation (one-sec style), no per-reel count on iOS. Mac never purchased; iOS builds via GitHub Actions macOS runner.
D2. Detection fully on-device (AccessibilityService). Server stores counts only — privacy is a product pitch.
D3. Sync = delta events with client UUID batch_id, server dedup. Rejected: absolute-count POSTs (BrainPal's concurrency bug class).
D4. History kept as daily aggregates forever; raw events pruned 7d. Rejected: 24h wipe (kills streaks/graphs).
D5. Realtime battles = 30s polling + FCM push. Rejected for free phase: WebSockets (RAM per connection kills free instance).
D6. GitHub Actions is primary CI. Ephemeral Jenkins (webhook→Lambda→EC2 start/stop→S3) is a separate learning track, never release-blocking. Rejected: fixed 2h scheduled Jenkins window (breaks per-push feedback; EBS bills while stopped; doesn't cover iOS).
D7. Free tiers: Render/Fly + Neon + Upstash + FCM. First paid upgrade expected ~5-10K DAU.
D8. Monetization: free 3 months, then ₹100/mo Pro. Self-funding at ~2.5-3K DAU @ 2% conversion.
D9. Guilt pack = remote-updatable JSON, categorized/weighted; global-first English now, language packs later. Mix guilt with pride/streak lines to avoid uninstall-from-shame.
D10. Day boundary computed server-side from users.timezone. Client clock never trusted for dates.
