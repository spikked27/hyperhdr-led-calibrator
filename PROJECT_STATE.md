# HyperHDR LED Calibrator — Project State

_Last updated: 2026-08-23_

This is the durable continuation record for the Android calibration app and its synchronized calibration video. Read this before making changes.

## Project identity

- Repository: `spikked27/hyperhdr-led-calibrator`
- Prior ChatGPT thread: **Splitter Firmware and Android Calibration app**
- Shared-chat URL: `https://chatgpt.com/share/6a8b82e3-cdcc-83ea-8b02-cf37e5bf6985?ogimg=plain`
- Archived visible transcript: `docs/chat-history/Splitter-Firmware-and-Android-Calibration-App-2026-08-23.md`
- Recovery skill: `skills/project-continuation/SKILL.md`

The transcript was extracted from the MHT supplied by the user. It preserves all user-visible turns present in that saved page. Collapsed tool/reasoning panels are preserved only by their visible summary labels because hidden panel contents were not embedded in the saved DOM.

## Current source of truth

**Latest field-test candidate: `0.1.0-beta.9.2`.**

Beta 8 was the recovered baseline. Beta 9/9.1 were field-tested and exposed orientation/overlay/synchronization problems. Beta 9.2 is the current corrective candidate on branch `beta9-reliable-tracking` / PR #9.

- Beta 9.2 validated code head: `dbd25e1c8a8c600de0bcc77f1c34bc0255d79ea1`
- Android CI run: `32686135190` / run #51 — **success**
- Unit tests: **success**
- Android lint: **success**
- Debug APK build: **success**
- Artifact upload: **success**
- APK artifact ID: `9505748640`
- CI artifact digest: `sha256:39dbfa0a4a2d5469f6b59c06744f5ccc232d131a77c13939501bc8fe8d8fdc1b`
- Extracted APK SHA-256: `3cbc98de5642436afb1878415fc616dfde0929123c348fd65475980bb867ddd3`
- Matching Beta 9.2 video: 1920×1080 H.264, 240 s (4:00)
- Video SHA-256: `f012dfc90d7541797e8bf0adb3ef19f80810c449143fe45bb2c5c818dedce789`
- Detailed Beta 9 field notes / Beta 9.2 response: `docs/BETA9_FIELD_NOTES.md`

## Product goal

Use an Android phone camera as a relative color comparator to calibrate HyperHDR RGBW bias-light output to the TV screen. The phone is not treated as a laboratory colorimeter. Accuracy comes from measuring TV and reflected LED light with the same camera, locked controls, RAW when available, black subtraction, white referencing, robust spatial sampling, and relative color math.

The current candidate presents an explicit **Commit calibration values to HyperHDR** action at the end rather than automatically overwriting persistent settings during the measurement process.

## Recovered beta chronology

### Initial beta / Beta 1

Established the first Android implementation: HyperHDR SSDP discovery, JSON control, TV/LED measurement, calibration solver, CI/tests, and debug APK artifact. PR #2 records field feedback from Beta 1.

### Beta 2 — guided Material 3 flow

PR #2 / commit `2ce3a2cb156840c35216980d39741c09a2ba06c6`.

- Select discovered HyperHDR server/instance before camera opens.
- Lock calibration to the selected instance.
- Guided TV-reference capture and TV→LED transition.
- Automatic LED test-color setting/confirmation.
- Fixed HyperHDR color-command schema/origin issue.
- Surfaced API errors.
- Material 3/dynamic-color UI and safe system-bar insets.
- Added protocol/instance tests.

### Beta 3 — camera lifecycle + TV blackout

PR #3 / merge `5a9afd935c0c882b0ab5e08559dc350c9478a76d`.

- Stable preview through TV→LED transition.
- Reopen/rebind Camera2 after TextureView/SurfaceTexture recreation.
- Ignore stale camera callbacks.
- Force HyperHDR BLACK throughout TV-reference measurement so LEDs cannot contaminate the screen reference.
- Retry/error handling for blackout and clean camera shutdown.

### Beta 4 — robust HyperHDR commands

PR #4 / merge `715b37e3cc3c3eb08468d16009e2b95b36da20be`.

Field problem was getting stuck at “waiting to turn the LEDs off” due to stale JSON/TCP sessions.

- Fresh socket for each color/blackout/clear command.
- Re-select locked instance for each command.
- Retry one transient network failure.
- Shorter network timeouts.

### Beta 5 — automatic final LED BLACK

PR #5 / merge `7a96e436d509c8df55d298970919cd8a1e4d5d586`.

- Automatically switch LEDs to BLACK after the last visible LED color.
- Automatically capture wall-black baseline.
- Dedicated analyzing screen.
- Manual retry if automatic black capture fails.
- Live status/error messages.
- Solver failure restores HyperHDR control and shows exact captured values/error.

### Beta 6 — main 1× camera + RAW manual measurements

PR #6 / merge `a882ee9861dd7a3e5b6acb124db5200bc85ea6a3`.

- Deterministically prefer the normal rear camera near ~24 mm equivalent.
- Force 1.0× zoom where Camera2 supports it.
- Prefer RAW-capable main camera.
- Use `RAW_SENSOR` Bayer measurements with black-level subtraction and white-level normalization when possible.
- Median-combine 5–7 RAW frames.
- Lock exact shutter/ISO after a white reference for each calibration half.
- Separate TV and LED-wall exposure locks.
- Reduce exposure if RAW clipping exceeds 1%; reject persistently clipped data.
- YUV fallback on devices without usable RAW/manual support.
- Lock patch order to **WHITE → RED → GREEN → BLUE → CYAN → MAGENTA → YELLOW → BLACK**.

### Beta 7 — fixed-camera spatial calibration

PR #7 / final head `167e14056a40c3459d3078bacfd60ed33155d0f6`.

- Keep the phone aimed at the TV + wall for the entire run.
- Detect TV rectangle from initial WHITE reference while HyperHDR LEDs are off.
- LED phase leaves TV BLACK and measures surrounding wall halo.
- Sample full RAW frame into a spatial grid; median-combine 5 frames per measurement.
- Build one wall reference from LED WHITE and use same retained wall tiles for every color.
- Subtract BLACK per tile and channel-wise divide by WHITE per tile to cancel brightness gradient, lens shading, wall reflectance variation, and TV/LED exposure difference.
- Reject colored/shadowed wall outliers without rejecting normal brightness gradients.
- White-reference TV and LED math while preserving relative primary strength.
- Preserve WHITE `[255,255,255]` so threshold 1.0 keeps using dedicated W diode.
- Enumerate physical rear camera sensors and provide **Switch rear camera** before capture.

Compatibility fixes immediately before Beta 8:

- `a8f036111979c0250d246c08291c8d5b90531eea` — physical Camera2 request-key compatibility.
- `167e14056a40c3459d3078bacfd60ed33155d0f6` — physical-camera result API guard.

### Beta 8 — synchronized automatic video calibration

PR #8 / main commit `4bf6f083cfe0e73408a8b1c51851679adba9e53a`.

Implemented the first automatic synchronized app/video pass:

- Automatic TV sequence: WHITE, RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW, BLACK.
- Preview-based TV/color recognition for synchronization.
- RAW/spatial capture for actual measurements.
- Automatic final-BLACK transition to LED-wall calibration.
- Spatial wall alignment for handheld drift.
- Dynamic TV outline.

### Beta 8 real-device feedback

The user reported:

- Camera preview still heavily distorted / “smushed.”
- Exiting and re-entering while the app was running could freeze with a camera error.
- Automatic TV/backlight color recognition was unreliable and could miss RED, leaving the app hung waiting for a color while the video continued.
- TV 16:9 geometry should help border detection.
- Backlights should be ON while detecting the TV against a BLACK screen, then turn OFF before calibration.
- Add an end-of-run button to commit calibration values.

### Beta 9 — marker synchronization + TV-border acquisition

PR #9 initial candidate.

- Replaced apparent-RGB synchronization with an explicit high-contrast machine-readable marker in the companion video.
- Added BLACK-TV + WHITE-backlight border acquisition.
- Added 16:9 TV geometry constraint.
- Added direct HyperHDR commit button.
- Added lifecycle cancellation/Camera2 cleanup.

### Beta 9 / 9.1 real-device feedback

The user reported:

- The desired order should be: point camera at TV → press READY → app tells user to start video → countdown gives time to finish framing → border detection begins while TV is BLACK and LEDs are ON.
- Once the TV border is detected and locked, the guide should stop changing shape/position; the user will keep the TV inside the box.
- 16:9 should be a TV-likelihood clue, not an exact hard-coded box/orientation.
- Portrait operation worked, but TV detection effectively only worked in landscape.
- After lock the app never recognized any colors and remained at **0/8**.

Root cause found for the portrait/0-of-8 mismatch: Beta 9/9.1 still called `TextureView.getBitmap(320, 180)` unconditionally. A portrait preview was therefore internally resampled into a landscape analysis frame, squashing geometry used by both border detection and marker decoding.

### Beta 9.2 — current candidate

Beta 9.2 directly addresses the Beta 9/9.1 field feedback:

- New launcher activity: `Beta92CalibrationActivity`.
- Phone remains portrait.
- Live analysis preserves the actual TextureView aspect ratio; only the long edge is reduced to 320 px.
- New user sequence:
  1. Open camera and point at TV.
  2. Press **READY — START VIDEO**.
  3. HyperHDR backlights turn WHITE.
  4. App displays **START VIDEO NOW** and a **5-second countdown**.
  5. Border detection begins after the countdown while the companion video is still BLACK.
  6. TV candidate uses BLACK screen + WHITE wall halo.
  7. 16:9 is a **soft scoring prior**, not an exact geometric requirement.
  8. Stable candidate freezes into a GREEN guide.
  9. Guide position and shape are not changed again during TV calibration.
  10. HyperHDR backlights turn OFF.
  11. Marker-driven TV capture starts automatically.
- Marker decoder searches a small neighborhood around the frozen guide to tolerate normal hand drift while the user keeps the TV inside the box.
- Missing colors remain recoverable on video replay without discarding completed captures.
- RAW_SENSOR/YUV spatial measurement, wall normalization/alignment, solver, cleanup behavior and end-of-run HyperHDR commit remain intact.

## Beta 9.2 app/video protocol

- Initial unmarked BLACK video lead-in: **15 s**.
- App framing countdown after READY: **5 s**.
- Approximate border-detection budget after countdown: **10 s** before WHITE begins.
- Each WHITE/RED/GREEN/BLUE/CYAN/MAGENTA/YELLOW TV patch: **15 s**.
- Final BLACK: **120 s**.
- Total companion video duration: **240 s / 4:00**.
- Marker sequence: WHITE, RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW, BLACK.
- Preview analysis interval: **100 ms**.
- Stable marker detections required: **3**.
- Stable border frames required: **6**.
- Preview sampling preserves displayed aspect ratio; maximum analysis dimension is **320 px**.

## Beta 9.2 automated validation

CI run #51 passed all configured gates on code head `dbd25e1c8a8c600de0bcc77f1c34bc0255d79ea1`:

1. JVM unit tests — **PASS**.
2. Android lint — **PASS**.
3. Debug APK build — **PASS**.
4. APK artifact upload — **PASS**.

Tests specifically include:

- portrait-frame TV detection,
- a TV whose apparent aspect is not exactly 16:9,
- all eight marker IDs in portrait analysis geometry,
- marker recovery when the actual TV shifts slightly inside the frozen guide,
- sufficient black-lead timing after the READY countdown.

## Current real-device validation priority

Beta 9.2 needs a real phone/TV run. Highest-value checks:

1. READY/countdown workflow is intuitive and gives enough framing time.
2. Portrait TV-border detection works without rotating the phone.
3. Candidate box selects the real TV and does not require an exact 16:9 apparent bounding box.
4. Green guide freezes completely once locked.
5. Backlights remain WHITE during countdown/acquisition and shut OFF at lock.
6. Marker capture advances from **0/8** through the TV sequence.
7. Small handheld drift is tolerated as long as the TV remains inside the green guide.
8. Final BLACK starts LED-wall measurement automatically.
9. Commit calibration values button persists the solved ICE anchors when HyperHDR permissions allow it.

## Continuation rules

- **Do not restart from README/Beta 0.1. Beta 9.2 is the current field-test candidate.**
- Use `docs/BETA9_FIELD_NOTES.md` for the detailed latest device feedback.
- Preserve portrait-safe aspect handling; never force a portrait TextureView into a fixed 320×180 analysis bitmap again.
- Treat 16:9 as a TV detection prior, not a required apparent rectangle.
- After border lock, keep the visible guide fixed unless the user explicitly requests tracking again.
- Keep app and companion video protocol/version matched.
- Update this file after every material real-device test.
