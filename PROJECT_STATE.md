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

**Latest implemented app version: `0.1.0-beta.8`.**

- Beta 8 PR: #8 — `Beta 8: synchronized automatic video calibration`
- PR head: `74aabfbf73848ee336023bccf18bb4dae5a48880`
- Main Beta 8 commit: `4bf6f083cfe0e73408a8b1c51851679adba9e53a`
- Android CI run: `32656393303` / run #27 — **success**
- APK artifact: `hyperhdr-led-calibrator-beta`
- Artifact ID: `9497566184`
- Artifact size: 9,211,677 bytes
- Artifact digest: `sha256:9fa33e55a84eafacab1f3b16d1da25b0c017fb0996d6dfd34dc183823ba5a7f8`
- Artifact created: 2026-08-23 17:57:33 UTC
- Artifact expires: 2026-11-21 17:55:31 UTC

The Beta 8 CI workflow runs JVM unit tests, Android lint, `assembleDebug`, and APK upload. The PR-head run completed successfully. No app-code changes have been made after Beta 8 during this recovery session; only project documentation/continuation files were added.

## Product goal

Use an Android phone camera as a relative color comparator to calibrate HyperHDR RGBW bias-light output to the TV screen. The phone is not treated as a laboratory colorimeter. Accuracy comes from measuring TV and reflected LED light with the same camera, locked controls, RAW when available, black subtraction, white referencing, robust spatial sampling, and relative color math.

Persistent HyperHDR ICE settings are not automatically overwritten in the current beta; the app calculates and reports recommended full ICE anchors.

## Recovered beta chronology

### Initial beta / Beta 1

Established the first Android implementation: HyperHDR SSDP discovery, JSON control, TV/LED measurement, calibration solver, CI/tests, and debug APK artifact. PR #2 explicitly records field feedback from Beta 1.

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
- Solver failure restores HyperHDR control and shows the exact captured values/error.

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
- Detect TV rectangle from the initial WHITE reference while HyperHDR LEDs are off.
- LED phase leaves TV BLACK and measures the surrounding wall halo.
- Sample full RAW frame into a spatial grid; median-combine 5 frames per measurement.
- Build one wall reference from LED WHITE and use the same retained wall tiles for every color.
- Subtract BLACK per tile and channel-wise divide by WHITE per tile to cancel much of the brightness gradient, lens shading, wall reflectance variation, and TV/LED exposure difference.
- Reject colored/shadowed wall outliers without rejecting normal brightness gradients.
- White-reference TV and LED math while preserving relative primary strength.
- Preserve WHITE `[255,255,255]` so threshold 1.0 keeps using the dedicated W diode.
- Enumerate physical rear camera sensors and provide **Switch rear camera** before capture.

Compatibility fixes immediately before Beta 8:

- `a8f036111979c0250d246c08291c8d5b90531eea` — physical Camera2 request-key compatibility.
- `167e14056a40c3459d3078bacfd60ed33155d0f6` — physical-camera result API guard.

### Beta 8 — synchronized automatic video calibration

PR #8 / main commit `4bf6f083cfe0e73408a8b1c51851679adba9e53a`.

Beta 8 implements the last visible user request in the archived thread: fix the distorted preview, make the guide fit the actual TV, automatically capture when the video changes colors, automatically begin LED capture on the final black screen, tolerate normal handheld motion, and make the app/video operate as one coordinated workflow with a **START VIDEO NOW** action.

Implemented behavior:

- `AutoCalibrationActivity` becomes the launcher; the former manual activity remains internal.
- Preview UI is 16:9 and Camera2 preview buffer is 1280×720, replacing the stretched arbitrary preview box.
- Fixed fake TV guide removed; a white outline is drawn from the detected/tracked TV rectangle.
- **START VIDEO NOW** first forces HyperHDR backlights BLACK and arms detection.
- App recognizes the actual TV image rather than trusting elapsed time.
- Automatic TV sequence: **WHITE → RED → GREEN → BLUE → CYAN → MAGENTA → YELLOW → BLACK**.
- Each accepted TV patch is measured with 5-frame spatial capture.
- Initial WHITE establishes TV rectangle and camera reference.
- Later colored frames reacquire the TV around the previous rectangle; BLACK retains the last rectangle.
- Final TV BLACK automatically starts the LED phase with no additional button press.
- LED WHITE establishes the wall exposure/reference.
- LED BLACK is captured before and after the color sequence; the two black fields are median-combined.
- RED/GREEN/BLUE/CYAN/MAGENTA/YELLOW LED fields are controlled and captured automatically while the TV stays BLACK.
- Wall fields are spatially translation-aligned to the WHITE reference before channel-wise normalization.
- Alignment searches **±3 tiles** in X/Y for modest handheld drift.
- Solver reports full HyperHDR ICE anchors, estimated relative before/after error, and per-color spatial diagnostics.
- Temporary HyperHDR calibration priority is cleared on success and cleanup/error paths.

## Beta 8 app/video protocol

`CalibrationProtocol.kt` defines:

- Initial BLACK lead-in: **6 s**.
- Each non-final TV patch: **10 s**.
- Final BLACK: **120 s**.
- Sequence: WHITE, RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW, BLACK.
- Preview analysis: **72×40** pixels.
- Preview analysis interval: **120 ms**.
- Stable matches required before capture: **4 frames**.
- LED settle: **700 ms**.
- LED WHITE exposure settle: **1200 ms**.

The app does not depend on exact video timing after start. Long stable video patches provide the state; the app recognizes the current patch and waits for four stable preview detections before the high-quality measurement. This is intentionally tolerant of small playback/start delays.

If no additional transition frames exist, these constants imply a nominal video duration of **3:16** (6 s + seven 10 s non-black patches + 120 s final black). The actual delivered MP4 has not yet been recovered, so this is a protocol-derived duration, not a verified file duration.

## Beta 8 preview tracking

`PreviewAnalyzer` is for sync and the on-screen TV outline; calibration values still use RAW_SENSOR when supported.

- Initial WHITE detection compares a bright, neutral center component with the preview border/background.
- Requires foreground ≥0.22 and ≥0.08 above background.
- Segmentation threshold is 48% between background and foreground.
- White-balance score threshold: 0.55.
- TV sample is inset 18% inside the detected rectangle.
- Handheld tracking searches the previous TV rectangle expanded ~45% horizontally and ~55% vertically.
- Expected-color segmentation distance threshold: 0.43.
- Tracked area ratio allowed: ~0.42–1.85 of previous.
- Relative aspect-ratio factor allowed: ~0.62–1.62.
- BLACK uses the previous rectangle.
- BLACK match threshold: ≤10.5% of WHITE-reference luma.
- Expected-patch match limit: 0.27 for WHITE, 0.39 for colors.

RAW/spatial capture has a separate TV-reacquisition pass with its own geometry/chroma sanity checks.

## Beta 8 spatial wall alignment

- Wall reference is an annulus around the detected TV.
- LED WHITE minus LED BLACK establishes the per-tile reference.
- Low-signal and chromatic-outlier tiles are removed.
- The same retained WHITE-reference model is reused for every LED color.
- Current wall-light field is translated against WHITE by up to ±3 grid tiles.
- Shift scoring compares log-luminance field shape after removing global brightness scale.
- Each color is black-subtracted, divided channel-by-channel by aligned WHITE, robustly combined, and filtered for chromatic outliers.
- Results report tiles used, P90/P10 brightness-gradient ratio, alignment dx/dy, and chroma spread.

## Camera/exposure behavior retained in Beta 8

- Physical rear-camera selection from Beta 7.
- Prefer normal/main ~1× camera and RAW-capable sensor.
- RAW_SENSOR where possible; YUV fallback otherwise.
- RAW black-level subtraction and white-level normalization.
- First TV WHITE allows AE/AWB to settle, then locks exposure and white balance for TV references.
- LED WHITE establishes a second exposure state for wall measurements while WB remains locked.
- Manual-sensor path locks explicit shutter and ISO; non-manual path uses AE lock.
- Physical-camera request/result API calls are guarded for OEM/API compatibility.

## Validation status

### Automated

Beta 8 PR-head Android CI **passed**. The workflow runs:

1. JVM unit tests.
2. Android lint.
3. Debug APK build.
4. APK artifact upload.

Beta 8 includes tests for synchronized protocol, preview TV detection/tracking, RAW TV tracking, strong wall gradients, and shifted wall fields in addition to the earlier solver/protocol/camera tests.

### Real device

The archived MHT contains the user’s feedback on the build immediately before Beta 8: it “worked really well,” camera selection “worked great,” but the preview was heavily distorted and the desired workflow was fully automatic/synchronized. Those requests became Beta 8.

The supplied MHT snapshot ends while Beta 8 is being implemented and does not contain the later visible Beta 8 delivery or post-installation feedback. Therefore Beta 8’s source and automated CI/build status are verified, but its real-device behavior after delivery has not yet been recovered.

## Remaining recovery gaps

- Exact companion Beta 8 MP4 filename/location from the prior ChatGPT delivery.
- Verify the delivered MP4 itself matches the 6 s / 10 s / 120 s protocol.
- Exact prior ChatGPT APK filename; the GitHub CI build is recoverable as artifact ID `9497566184`.
- Any user feedback after installing Beta 8.
- Exact next request after the Beta 8 delivery, if there was one.

## Continuation rules

- **Do not restart from README/Beta 0.1. Beta 8 is the current implementation.**
- Use commit `4bf6f083cfe0e73408a8b1c51851679adba9e53a` as the Beta 8 implementation anchor.
- Preserve automatic patch recognition, 16:9 preview geometry, dynamic TV tracking, RAW/spatial capture, wall-field alignment, fixed HyperHDR-instance selection, and cleanup behavior unless intentionally replacing them.
- Keep app and companion video protocol/version matched.
- Highest-value next validation is a complete real-device Beta 8 run: confirm preview geometry/TV outline, full automatic patch sequence, final-BLACK transition, and wall alignment during normal handheld movement.
- Do not automatically write persistent HyperHDR ICE settings without explicit approval.
- Update this file after every beta or material device test.
