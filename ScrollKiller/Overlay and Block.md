# Overlay and Block

← [[ScrollKiller]]

## Mind map

```mermaid
mindmap
  root((Overlay and Block))
    Bubble
      Attach once
      Hide alpha not GONE
      Mascot plus grand total
      Tap expand panel
    Block
      Full screen overlay
      User daily limit
      Exit always
      5 more minutes
      Earn your way out
    Three panels one window
      block_panel
      chooser_panel
      challenge_panel
      Child swaps never a rebuild
      Each carries its own Exit
    Limits
      Default 100
      Slider 20 to 300
      Grace 5 min
      Challenge 15 min
      Retry cooldown 30s
    Permissions
      canDetect vs canBlock
      Banner plus notification
      overlayRuntimeDenied
```

## Invariant 6
Exit and Back leave from **every** state. **All three panels** carry their own Exit, listed **first**
so TalkBack reaches the way out before any way to keep scrolling. A chooser or challenge screen
offering only "Back" would be a second screen to escape before you can escape — which is exactly
what the invariant forbids.

The root view never goes GONE: that frees the window's surface, and it would also drop the focus the
Back-key listener depends on.

Related: [[Challenges]] · [[Guilt]] · [[Detection]]

#block #overlay
