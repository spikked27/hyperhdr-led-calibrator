# HyperHDR LED Calibrator — Project State

_Last updated: 2026-08-23_

This is the durable continuation record for the Android calibration app and its synchronized calibration video. Read this before making changes.

## Project identity

- Repository: `spikked27/hyperhdr-led-calibrator`
- Prior ChatGPT thread title: **Splitter Firmware and Android Calibration app**
- Shared-chat URL: `https://chatgpt.com/share/6a8b82e3-cdcc-83ea-8b02-cf37e5bf6985?ogimg=plain`
- Archived visible transcript: `docs/chat-history/Splitter-Firmware-and-Android-Calibration-App-2026-08-23.md`
- Recovery skill: `skills/project-continuation/SKILL.md`

The archived transcript was extracted from the user-supplied MHT saved from the shared ChatGPT page. It preserves every user-visible turn present in that saved page. Collapsed tool/reasoning panels are represented only by their visible summary labels because their hidden contents were not embedded in the MHT DOM.

## Current source of truth

**Latest implemented version: `0.1.0-beta.8`**

- Beta 8 PR: **#8 — Beta 8: synchronized automatic video calibration**
- PR head: `74aabfbf73848ee336023bccf18bb4dae5a48880`
- Main Beta 8 commit: `4bf6f083cfe0e73408a8b1c51851679adba9e53a`
- Beta 8 Android CI run: `32656393303` / run #27 — **success**
- CI artifact: `hyperhdr-led-calibrator-beta`
- Artifact ID: `9497566184`
- Artifact size: 9,211,677 bytes
- Artifact digest: `sha256:9fa33e55a84eafacab1f3b16d1da25b0c017fb0996d6dfd34dc183823ba5a7f8`
- Artifact created: 2026-08-23 17:57:33 UTC
- Artifact expires: 2026-11-21 17:55:31 UTC

The CI workflow for this build runs JVM unit tests, Android lint, `assembleDebug`, and uploads `app-debug.apk`; the Beta 8 PR-head run completed successfully.

No code changes have been made after Beta 8 in this recovery session; only documentation/continuation files were added.

## Original product goal

Use an Android phone camera as a relative color comparator to match HyperHDR RGBW bias-light output to the TV screen. The phone is not treated as a laboratory colorimeter. Accuracy comes from measuring TV and reflected LED light with the same sensor/pipeline, using locked camera controls, RAW where available, ambient/black subtraction, and relative/white-referenced color math.

The app should discover/select the correct HyperHDR instance, measure TV references, automatically drive the LED colors, solve the full ICE calibration anchors, show diagnostics/error improvement, and leave persistent HyperHDR settings untouched unless explicitly approved.

## Recovered beta chronology

### Initial beta / Beta 1

Initial working Android beta established:

- HyperHDR SSDP discovery.
- JSON-server connection and temporary color control.
- TV and LED measurement workflow.
- calibration solver and ICE anchor suggestions.
- Android CI, tests, APK artifact, and validation checklist.

The exact Beta 1 version-label commit is not separately named in Git, but PR #2 explicitly records field feedback from Beta 1.

### Beta 2 — guided Material 3 calibration flow

PR #2 / commit `2ce3a2cb156840c35216980d39741c09a2ba06c6`.

- Show discovered HyperHDR servers/instances before opening camera.
- Explicitly connect and lock session to selected HyperHDR instance.
- Guided TV reference-color capture.
- Dedicated TV→LED transition.
- Automatically set/confirm each LED test color.
- Fix HyperHDR color-command `origin` length/schema issue.
- Surface HyperHDR API errors.
- Material 3 UI with dynamic color.
- Safe system-bar/drawing insets.
- Protocol and instance-selection tests.

### Beta 3 — camera lifecycle and TV blackout

PR #3 / merge `5a9afd935c0c882b0ab5e08559dc350c9478a76d`.

- Keep one stable preview through TV→LED transition.
- Rebind/reopen Camera2 when TextureView/SurfaceTexture is recreated.
- Ignore stale camera callbacks.
- Force HyperHDR to BLACK for the whole TV-reference phase so backlights cannot contaminate TV measurements.
- Keep LEDs black through transition.
- Retry/error handling for blackout failures.
- Clean camera close on results.
- Test verifies blackout is true RGB 0,0,0.

### Beta 4 — robust HyperHDR control sessions

PR #4 / merge `715b37e3cc3c3eb08468d16009e2b95b36da20be`.

Field problem: calibration could become stuck at “waiting to turn the LEDs off” because a long-lived JSON/TCP connection had gone stale.

- Each color/blackout/clear command opens a fresh socket.
- Select the locked HyperHDR instance, send command, close socket.
- Retry one transient network failure.
- Shorter connect/read timeouts.
- Keep selected instance locked for the session.

### Beta 5 — automatic final LED-black completion

PR #5 / merge `7a96e436d509c8df55d298970919cd8a1e4d5d586` is **not** the Beta 5 merge; correct Beta 5 merge is `7a96e436d509c8df55d298970919cd8a1e4d5d586` only if verified. Repository history identifies the Beta 5 PR merge as `7a96e436d509c8df55d298970919cd8a1e4d5d586`; if this SHA conflicts with GitHub metadata, re-read PR #5 before relying on it.

Known Beta 5 behavior:

- After final visible LED color, automatically switch HyperHDR to BLACK.
- Capture wall-black baseline automatically.
- Dedicated analyzing screen.
- Manual retry path if automatic black capture fails.
- Live status/error messages.
- Solver rejection restores HyperHDR control and shows captured TV/LED values plus exact error.
- Flow test verifies automatic BLACK transition.

> Recovery note: PR #5 should be re-queried before using its merge SHA in release automation; behavior above is verified from the PR body/history.

### Beta 6 — main 1× camera + RAW manual measurements

PR #6 / merge `a882ee9861dd7a3e5b6acb124db5200bc85ea6a3`.

- Deterministically choose rear camera closest to ~24 mm-equivalent rather than first camera ID.
- Force 1.0× zoom where Camera2 allows it.
- Prefer RAW-capable main camera.
- Use `RAW_SENSOR` Bayer data when RAW + manual sensor controls are available.
- Subtract sensor black level and normalize to sensor white level.
- Median-combine 5–7 RAW frames per measurement.
- After each white reference, lock exact `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` for that calibration half.
- Separate TV and LED-wall exposure locks.
- Automatically reduce white-reference exposure if RAW clipping exceeds 1%; reject if clipping remains excessive.
- YUV fallback for devices without usable RAW/manual support.
- Tests cover main-camera selection, Bayer arrangements, clipping, and exact patch order.
- Patch order locked to: **White → Red → Green → Blue → Cyan → Magenta → Yellow → Black**.

### Beta 7 — fixed-camera spatial calibration

PR #7 / head `167e14056a40c3459d3078bacfd60ed33155d0f6`.

Major accuracy change:

- Keep phone aimed at TV+wall for the entire run; no longer move phone between TV and wall.
- Detect TV rectangle from initial WHITE reference with HyperHDR backlights off.
- LED phase keeps TV BLACK and samples the wall halo around the detected TV.
- Capture the full RAW frame into a spatial grid rather than one center patch.
- Capture 5 RAW frames per measurement and median-combine.
- Build one fixed wall mask/model from LED WHITE and use the same reference tiles for all LED colors.
- Subtract BLACK per tile, then channel-wise white-reference each tile.
- This cancels much of wall brightness falloff, lens shading, wall reflectance variation, and TV/LED exposure difference without requiring a uniform wall image.
- Reject colored/shadowed wall outliers while retaining ordinary brightness gradients.
- Solve in white-referenced TV and LED space while preserving relative R/G/B primary strength.
- Preserve WHITE `[255,255,255]` so threshold 1.0 continues to use the dedicated W diode rather than silently mixing RGB into neutral white.
- Enumerate physical rear camera sensors and attempt to pin the chosen physical Camera2 stream.
- Manual **Switch rear camera** option before first capture.
- Added synthetic tests for TV detection, strong gradients, outliers, physical-camera selection, and median suppression.

Post-Beta-7 compatibility fixes before Beta 8:

- `a8f036111979c0250d246c08291c8d5b90531eea` — physical Camera2 request-key compatibility.
- `167e14056a40c3459d3078bacfd60ed33155d0f6` — physical camera result API guard.

### Beta 8 — synchronized automatic video calibration

PR #8 / main commit `4bf6f083cfe0e73408a8b1c51851679adba9e53a`.

This implements the user’s final visible request from the archived chat:

- Fix heavily stretched/distorted camera preview.
- Make the on-preview box fit the actual TV.
- Automatically capture the TV when the calibration video switches colors.
- Automatically transition to LED capture when the video reaches BLACK.
- Tolerate ordinary handheld movement.
- Rebuild/match the video to the app, including a long final BLACK.
- UI explicitly tells user **START VIDEO NOW** to begin the automatic sequence.

Beta 8 implementation:

- `AutoCalibrationActivity` becomes launcher; old `MainActivity` remains internal.
- Camera preview UI is fixed to 16:9, matching a 1280×720 Camera2 preview buffer instead of stretching the image into an arbitrary box.
- Fixed fake TV guide removed.
- White outline is generated from the detected TV rectangle and follows tracked movement.
- Pressing **START VIDEO NOW** first forces HyperHDR backlights BLACK, then arms video detection.
- App watches actual preview colors rather than relying on elapsed video time.
- TV sequence is recognized automatically: **WHITE → RED → GREEN → BLUE → CYAN → MAGENTA → YELLOW → BLACK**.
- Each detected TV patch is automatically measured using 5-frame spatial camera sampling.
- First WHITE establishes the TV rectangle and camera reference.
- Later colored RAW frames reacquire/track the TV around the previous rectangle; if reacquisition fails, the prior rectangle is retained rather than aborting immediately.
- BLACK keeps the last TV rectangle because there is no bright screen field to segment.
- On final TV BLACK, the app starts the complete LED measurement phase automatically.
- LED WHITE establishes a separately locked wall exposure.
- LED BLACK is captured both before and after the color sequence; those two frames are median-combined into the ambient/black baseline.
- LED RED/GREEN/BLUE/CYAN/MAGENTA/YELLOW are controlled and captured automatically while the TV remains BLACK.
- Wall-light fields are spatially aligned to the WHITE reference before channel-wise white normalization.
- Alignment searches translations of **±3 spatial tiles** so small handheld shifts do not corrupt gradient cancellation.
- Solver produces full ICE anchor values and estimated relative before/after validation error.
- Results include per-patch spatial diagnostics: tiles used, gradient ratio, alignment dx/dy, and chroma spread.
- HyperHDR temporary priority is cleared on success, cancellation/error, rerun, disconnect, and app destruction paths.

## Beta 8 synchronized video protocol

Defined in `CalibrationProtocol.kt`:

- Initial BLACK lead-in: **6 seconds**.
- Each normal TV patch: **10 seconds**.
- Final BLACK: **120 seconds**.
- Sequence: WHITE, RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW, BLACK.
- Preview analysis size: **72 × 40**.
- Preview analysis cadence: **120 ms**.
- Required stable preview matches before capture: **4 frames**.
- LED settle time: **700 ms**.
- LED WHITE exposure settle: **1200 ms**.

The app/video contract deliberately does **not** require exact wall-clock synchronization after starting. The video provides long stable patches; the app recognizes the actual patch currently visible on the TV and waits for four stable preview matches before taking the high-quality measurement. This makes it tolerant of user start timing and small playback hiccups.

If the companion video contains no additional transition frames, these constants imply a nominal duration of **3:16** (6 s lead-in + seven 10 s non-black patches + 120 s final black). The exact delivered video file itself has not yet been recovered/verified, so do not treat 3:16 as a verified file duration until the MP4 is located.

## Beta 8 preview detection/tracking details

`PreviewAnalyzer` is used only for synchronization and the visible TV outline. Calibration values still come from Camera2 RAW_SENSOR when available.

Initial WHITE detection:

- Estimate dark/background luma from preview borders.
- Estimate foreground from center-region 90th percentile.
- Require foreground ≥ 0.22 and at least 0.08 above background.
- Segment at 48% of foreground-background contrast.
- Require white-balance score ≥ 0.55.
- Sanity-check detected TV area/width/height.
- Measurement sampling uses an 18% inset from the detected preview rectangle.

Handheld preview tracking:

- Search around the previous TV rectangle expanded by ~45% horizontally and ~55% vertically.
- Segment based on expected patch chromaticity and relative luma.
- Expected-patch segmentation distance threshold: 0.43.
- Tracked area may vary roughly 0.42×–1.85× previous area.
- Relative aspect-ratio factor accepted roughly 0.62–1.62.
- BLACK reuses previous rectangle.
- Expected BLACK threshold is ≤10.5% of WHITE-reference luma.
- Preview match threshold is tighter for WHITE (0.27) than other colors (0.39).

RAW/spatial TV tracking uses its own sanity checks and reacquires the uniformly colored screen within an expanded window around the prior rectangle.

## Beta 8 wall spatial alignment/gradient cancellation

- Wall reference is an annulus around the detected TV.
- Reference is built from LED WHITE minus LED BLACK on a per-tile basis.
- Too-dark tiles and chromatic outliers are rejected.
- The same retained WHITE-reference model is reused for every LED color.
- Brightness-gradient diagnostics are based on retained wall-tile P90/P10.
- Before color normalization, the current wall field is translated against the WHITE field by up to ±3 tiles in X/Y.
- Shift score is based on log-luminance shape after removing global brightness scale, allowing red/green/blue fields to align with white despite different sensor response.
- Each color is black-subtracted, divided channel-by-channel by the aligned WHITE reference, robustly combined, and chromatic outliers rejected.

This is the core mechanism that allows modest phone movement while retaining Beta 7’s gradient/lens-shading cancellation.

## Camera/exposure behavior retained in Beta 8

- Physical rear-camera enumeration/selection from Beta 7.
- Prefer the normal/main ~1× camera and RAW-capable sensor.
- 1280×720 preview buffer.
- RAW_SENSOR measurements when available; YUV fallback otherwise.
- Sensor black-level subtraction and white-level normalization in RAW path.
- First TV WHITE: exposure and AWB are allowed to settle, then exposure/WB are locked for TV references.
- LED WHITE starts a second exposure-settle/lock phase for LED-wall measurements, while white balance remains locked.
- Manual-sensor path uses explicit shutter and ISO after sampling auto-exposure metadata; non-manual devices fall back to AE lock.
- Camera request/result handling includes OEM/API guards for physical sensors.

## Validation status

### Automated

Beta 8 PR-head Android CI **passed**.

The CI gate runs:

1. JVM unit tests.
2. Android lint.
3. Debug APK build.
4. APK artifact upload.

Beta 8 adds tests for synchronized protocol, preview TV detection/tracking, RAW TV tracking, strong wall gradients, and shifted wall fields in addition to prior solver/protocol/camera tests.

### Real device

The archived chat contains the user’s real-device feedback immediately before Beta 8: the previous build “worked really well,” camera selection worked great, but the preview was distorted and the user requested the fully automatic synchronized workflow. Those requests became Beta 8.

The supplied MHT snapshot ends during Beta 8 implementation/status messages and does **not** contain the later user-visible Beta 8 delivery or any subsequent real-device test result. Therefore Beta 8’s automated build/tests are verified, but post-delivery phone/TV behavior is not yet recovered from the transcript.

## Known remaining recovery gaps

- Exact filename/location of the companion Beta 8 calibration video delivered in ChatGPT.
- Verification that the delivered MP4 exactly matches the 6 s / 10 s / 120 s protocol constants.
- Exact filename used for the Beta 8 APK in the prior ChatGPT delivery; the GitHub CI APK artifact itself is recoverable as artifact ID `9497566184`.
- Any real-device feedback the user gave after installing Beta 8.
- The exact next request after the Beta 8 delivery, if any.

## Important continuation rules

- **Do not restart from the old README/Beta 0.1 workflow. Beta 8 is the current implemented codebase.**
- Treat `4bf6f083cfe0e73408a8b1c51851679adba9e53a` as the Beta 8 implementation anchor.
- Preserve automatic video recognition, dynamic TV tracking, RAW/spatial capture, wall-field alignment, fixed HyperHDR instance selection, and cleanup behavior unless explicitly replacing them.
- Keep app and companion video protocol/version matched.
- Before Beta 9, obtain a real-device Beta 8 run if possible; the highest-value validation target is whether the preview geometry and detected TV outline now match the physical TV on the user’s phone.
- Also verify automatic patch recognition through the full video, final BLACK transition, and the wall-field alignment diagnostics under ordinary handheld movement.
- Do not write persistent HyperHDR ICE settings automatically without explicit user approval.
- Update this file after every beta or material real-device test.
