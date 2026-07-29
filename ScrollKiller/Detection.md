# Detection

← [[ScrollKiller]]

## Mind map

```mermaid
mindmap
  root((Detection))
    Service
      ReelScrollAccessibilityService
      Event only no God class
      Emits to overlay and Room
    Strategies
      DELTA_Y_FORWARD IG
      IDENTITY_CHANGE YT
      EVENT_PULSE unused
    Surface
      SurfaceMatcher
      Marker ancestry
      3s hysteresis
      Hold while blocking
    Detectors
      SwipeDetector
      IdentityAdvanceDetector
      ReelIdentity
    Debug
      SurfaceDiagnostics
      YtProbe
      DIAG_LABEL broadcast
```

## Notes
- Entry credit on surface enter — **off** for YT (identity already counts landing Short)
- Package gate = `PlatformRegistry.forPackage` only
- See also [[Platforms]]

#detection
