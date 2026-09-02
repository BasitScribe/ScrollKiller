# Platforms

← [[ScrollKiller]]

## Mind map

```mermaid
mindmap
  root((Platforms))
    Instagram
      com.instagram.android
      clips_viewer
      ENFORCED
      DELTA_Y_FORWARD 200ms
      STABLE
      blocksAtLimit true
    YouTube
      com.google.android.youtube
      app.revanced.android.youtube
      reel_recycler
      ENFORCED
      IDENTITY_CHANGE plus pulse
      absorb 2s D91
      BETA blocks via D73
    TikTok
      com.zhiliaoapp.musically
      EVENT_PULSE D91
      SHADOW
      BETA no block
    Snapchat
      com.snapchat.android
      EVENT_PULSE D91
      spotlight SHADOW
      BETA overcounts Chat Stories Map
```

## Rule
`blocksAtLimit = blockEnabled && (STABLE || blocksWhileUncalibrated)` — IG is STABLE; YT blocks via the D73 override while staying BETA. TikTok/Snapchat stay SHADOW and must not get the override.

Related: [[Detection]] · [[Overlay and Block]]

#platforms
