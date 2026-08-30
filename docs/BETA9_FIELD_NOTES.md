# Beta 9 Field Notes and Beta 9.2 Response

## Beta 9 / 9.1 field feedback — 2026-08-23

Observed on the real phone/TV setup:

- The desired workflow should begin with the user pointing the phone at the TV and explicitly pressing a **READY** button.
- After READY, the app should tell the user to start the companion video and show a visible countdown before TV-border detection begins, giving time to finish framing.
- HyperHDR backlights should be ON during that countdown and TV-border acquisition; the TV is BLACK during this phase.
- Once the TV border is detected and accepted, the on-screen guide must stop changing shape/position. The user will physically aim the phone to keep the TV inside the fixed guide.
- A 16:9 TV aspect should be used as a detection clue to reject non-TV objects, not as a hard-coded rectangle/orientation requirement.
- Portrait phone operation is desired. Beta 9.1 displayed portrait, but TV detection effectively worked only in landscape.
- After border lock, Beta 9.1 remained at **0/8** and did not register any video colors.

## Root cause identified for 0/8 / orientation mismatch

The Beta 9/9.1 live analysis path always requested `TextureView.getBitmap(320, 180)`. In portrait this forced the displayed portrait camera image into a landscape analysis bitmap, so the detector/marker coordinates were internally squashed even when the UI appeared portrait.

## Beta 9.2 corrective design

- New launcher activity: `Beta92CalibrationActivity`.
- Normal phone use remains portrait.
- Live analysis now preserves the actual TextureView aspect ratio, scaling only the long edge to 320 px; it no longer forces portrait content into 320×180.
- New sequence:
  1. Open camera and frame TV.
  2. Press **READY — START VIDEO**.
  3. HyperHDR backlights turn WHITE.
  4. App displays **START VIDEO NOW** and a 5-second framing countdown.
  5. Beta 9.2 video remains BLACK for 15 seconds total, leaving roughly 10 seconds for TV-border acquisition after the countdown.
  6. Detector finds the BLACK TV against the WHITE backlight halo.
  7. 16:9 is a soft scoring prior only; apparent aspect ratios altered by perspective are allowed.
  8. Once stable, the detected guide is frozen and turns GREEN.
  9. HyperHDR backlights turn OFF.
  10. The app automatically waits for and decodes the video markers, captures TV colors, then performs the automated LED phase on final BLACK.
- Once locked, `refineBorder()` is no longer called during calibration. The guide does not move or reshape.
- Marker decoding searches a small neighborhood around the frozen guide so ordinary handheld drift does not require changing the guide itself.
- Already-captured patches remain recoverable on replay.
- End-of-run **Commit calibration values to HyperHDR** remains present.

## Automated validation

Latest Beta 9.2 validated head:

`dbd25e1c8a8c600de0bcc77f1c34bc0255d79ea1`

Android CI run:

- Run #51 / `32686135190`
- Result: **SUCCESS**
- Unit tests: **SUCCESS**
- Android lint: **SUCCESS**
- Debug APK build: **SUCCESS**
- Artifact upload: **SUCCESS**

Added/updated tests cover:

- portrait-frame black-TV detection,
- non-exact/perspective apparent TV aspect,
- all 8 markers in a portrait analysis frame,
- marker recovery when the actual TV shifts slightly inside a frozen guide,
- timing budget between READY countdown and border acquisition.

CI APK artifact:

- Artifact ID: `9505748640`
- Artifact name: `hyperhdr-led-calibrator-beta`
- Artifact digest: `sha256:39dbfa0a4a2d5469f6b59c06744f5ccc232d131a77c13939501bc8fe8d8fdc1b`
- Extracted APK SHA-256: `3cbc98de5642436afb1878415fc616dfde0929123c348fd65475980bb867ddd3`

Matching Beta 9.2 video:

- 1920×1080 H.264
- Duration: 240 s (4:00)
- 15 s unmarked BLACK lead-in
- 15 s each WHITE/RED/GREEN/BLUE/CYAN/MAGENTA/YELLOW field
- 120 s final BLACK
- SHA-256: `f012dfc90d7541797e8bf0adb3ef19f80810c449143fe45bb2c5c818dedce789`

## Next real-device checks

1. READY button/countdown sequence feels natural.
2. Portrait TV detection acquires the correct TV rather than requiring landscape.
3. White acquisition box fits the physical TV reasonably despite perspective.
4. Green locked guide remains completely fixed after lock.
5. Backlights are WHITE during acquisition and shut OFF at lock.
6. Marker sequence advances from 0/8 to 1/8…8/8.
7. Small normal hand movement does not prevent marker decoding while the user keeps the TV inside the frozen guide.
