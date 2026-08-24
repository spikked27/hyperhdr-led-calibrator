# Beta 9 field-reliability changes

Beta 9 responds to real-device feedback from Beta 8.

## Beta 8 field findings

- Camera preview remained heavily distorted/squashed.
- Exiting and returning while calibration was running could leave the app frozen with a camera error.
- Color-based video synchronization could miss a patch (notably RED) and then wait forever while the video continued.
- The TV overlay did not reliably snap to the physical screen border.
- TV geometry should make explicit use of the known 16:9 display aspect ratio.
- Backlights should illuminate the wall during initial TV-border recognition while the TV is black; after the border is locked, backlights must turn off before measurement.
- Results need a direct **Commit calibration values** action.

## Beta 9 design

- Calibration activity runs landscape and applies an explicit Camera2 TextureView transform.
- The initial video frame is unmarked BLACK and is paused at 0:00.
- HyperHDR is temporarily set to WHITE during border acquisition, producing a bright wall halo around the black TV.
- Border detection searches for a dark central rectangle surrounded by brighter wall, fits a physical 16:9 rectangle, and requires multiple stable detections.
- The overlay is the fitted screen border and is locally refined while the phone moves.
- Backlights are set to BLACK immediately after the border is locked.
- Video synchronization no longer depends on recognizing the apparent RGB color from Android's processed preview.
- Each calibration patch carries four black/white Manchester-like marker pairs near the top-left edge. Pair 0 is a fixed sync bit; pairs 1–3 encode step 0–7.
- The marker lies outside the inset calibration-measurement area, so it does not contaminate RAW TV color measurements.
- Preview analysis increases from 72×40 to 320×180 and runs at 100 ms intervals.
- Marker must be stable for three frames before capture.
- TV patch dwell increases from 10 s to 15 s to leave margin for RAW exposure lock and five-frame bursts.
- Capture state is keyed by observed marker ID, not one expected-next-color variable. Already captured patches are retained and ignored on subsequent passes.
- A failed capture retries automatically if the same patch is still displayed. If a patch was completely missed, replaying the video fills only the missing values.
- Final BLACK starts LED automation only when all TV references are present; otherwise the UI explicitly lists missing patches.
- Leaving the app invalidates/cancels in-flight calibration work, clears the temporary HyperHDR priority, and closes Camera2 before a later session can reopen it.
- Results include a **Commit calibration values to HyperHDR** button. It applies the full eight-anchor ICE adjustment and then attempts to persist those values via `config/getconfig` + `config/setconfig` while preserving unrelated configuration.

## Why live RAW is not used for synchronization

RAW_SENSOR remains the preferred calibration measurement source, but it is intentionally not the live video-state detector. Continuous RAW is expensive, device-dependent, and the five-frame measurement burst can consume a large fraction of a short patch. Synchronization only needs a robust state identifier, so a high-resolution processed preview plus an explicit binary marker is faster and more reliable. RAW is still used after synchronization for the actual color values whenever the selected camera supports it.
