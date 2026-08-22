# HyperHDR LED Calibrator

Android app for guided camera-based matching of HyperHDR bias-light colors to a TV/display.

## Beta 0.1 goals

- Automatic HyperHDR discovery on the local network using HyperHDR's native SSDP advertisement.
- Connect to the advertised HyperHDR JSON server.
- Guided TV measurement of white, R/G/B, C/M/Y, and black using the rear camera.
- Lock white balance on a neutral white reference; lock exposure independently for the TV and reflected-wall phases.
- Automatically drive the LEDs through HyperHDR for the wall-measurement stage.
- Median-sample a center region across multiple camera frames.
- Subtract measured black/ambient background before solving.
- Solve a 3x3 primary correction, then derive suggested full 8-color ICE calibration anchors.
- Report an estimated relative before/after validation error.
- Never change the user's saved HyperHDR configuration automatically in this beta.
- Clear the temporary calibration priority when calibration completes or the app exits.

## Accuracy notes

A phone camera is not a spectrophotometer/colorimeter. This beta intentionally treats the phone as a **relative comparator**: it locks camera white balance and compares the screen with reflected LED light using the same sensor/pipeline. Results are most reliable with room lighting off, a neutral/white wall, no camera movement, and a full-screen patch on the TV.

The app calibrates chromaticity, not perceived bias-light brightness. HyperHDR brightness should be tuned separately.

## HyperHDR discovery

HyperHDR advertises over SSDP on `239.255.255.250:1900`. Current HyperHDR includes `HYPERHDR-JSS-PORT`, `HYPERHDR-NAME`, and `LOCATION` fields in its SSDP responses. The app uses these advertised values rather than assuming an IP address or port.

## HyperHDR control

The app talks to HyperHDR's newline-delimited TCP JSON server and sends temporary `color` commands at priority 40. It sends `clear` for the same priority when finished. The beta does **not** write HyperHDR's saved Image Processing/ICE settings.

## Calibration workflow

1. Put the TV on a full-screen test patch matching the color named by the app. White is measured first so camera controls can settle on a neutral reference.
2. Aim the center of the camera preview at the TV and tap **Measure TV** for each patch.
3. After all TV patches are measured, point the phone at a representative area of wall illuminated by the bias lights.
4. The app automatically commands HyperHDR through the same white/R/G/B/C/M/Y/black sequence and measures the reflected light.
5. Copy the suggested white/R/G/B/C/M/Y/black values into HyperHDR's **full / 8-color LED calibration** mode.

## Build and tests

GitHub Actions runs unit tests and builds a debug APK on every push to `main`.

Local build with Gradle 8.11.1 / JDK 17:

```bash
gradle testDebugUnitTest assembleDebug
```

Core math tests cover identity calibration, ambient-black subtraction, primary cross-talk compensation, and rejection of singular/invalid primary measurements. SSDP parser tests cover HyperHDR's native response fields, case-insensitive headers, malformed `LOCATION` fallback, and rejection of unrelated SSDP devices.

## Current beta limitations

- The TV test pattern is advanced manually; a Shield/Android TV companion pattern generator is planned.
- Camera measurements currently use locked Camera2 YUV output rather than RAW Bayer data.
- The reported error is an estimated relative color error, not laboratory-grade Delta E because phone camera spectral response is not standardized.
- Applying calculated settings to HyperHDR is deliberately manual in beta 0.1 for rollback safety.
