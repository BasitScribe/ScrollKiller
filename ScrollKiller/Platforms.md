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
      IDENTITY_CHANGE 500ms
      BETA no block
    TikTok
      com.zhiliaoapp.musically
      SHADOW
      BETA no block
    Snapchat
      com.snapchat.android
      spotlight SHADOW
      BETA overcounts
```

## Rule
`blocksAtLimit = blockEnabled && Maturity.STABLE` — only IG today.

Related: [[Detection]] · [[Overlay and Block]]

#platforms
