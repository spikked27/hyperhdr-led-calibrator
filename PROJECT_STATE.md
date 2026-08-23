# HyperHDR LED Calibrator — Project State

_Last updated: 2026-08-23_

This file is the durable continuation record for the Android calibration app / synchronized calibration video project. Update it whenever a beta is delivered or a real-device test materially changes what is known.

## Project identity

- Repository: `spikked27/hyperhdr-led-calibrator`
- Prior ChatGPT thread title: **Splitter Firmware and Android Calibration app**
- Shared-chat URL supplied by user: `https://chatgpt.com/share/6a8b82e3-cdcc-83ea-8b02-cf37e5bf6985?ogimg=plain`
- Recovery skill: `skills/project-continuation/SKILL.md`

## Latest known user-facing state

The user reports that the latest deliverables from the prior thread were:

- **Beta 8 Android calibration app**
- **Matching calibration video**

The exact Beta 8 APK filename, video filename, commit SHA, timing table, and post-delivery test feedback have not yet been recovered into this repository state file. Do not invent them. Recover them from the prior conversation or artifacts when accessible and update this file.

## Confirmed Beta 8 design intent

A message from the prior thread immediately before Beta 8 completion stated:

> “Yes, I’m here. I’ve started the **Beta 8** work for the fully synchronized app/video workflow. I’m still working through the camera-preview geometry, automatic TV tracking/capture, handheld-motion tolerance, and the long final-black sequence so I don’t hand you another partially tested build.”

Therefore Beta 8 was intended to address, at minimum:

1. **Fully synchronized app/video workflow**
   - The app and calibration video should operate as one coordinated measurement sequence rather than relying on fully manual patch advancement.

2. **Camera-preview geometry**
   - The camera analysis ROI must correspond correctly to the visible preview after rotation, crop, scaling, and aspect-ratio handling.

3. **Automatic TV tracking/capture**
   - The app should identify/track the TV region and capture the intended TV measurement automatically rather than requiring perfect manual framing for every patch.

4. **Handheld-motion tolerance**
   - The workflow should tolerate normal phone movement instead of requiring the phone to remain perfectly fixed.

5. **Long final-black sequence**
   - The calibration video includes or was intended to include an extended black tail so the app can reliably recognize the end of the run and perform final processing without sampling playback/UI transitions.

## Repository state versus user-facing beta state

The current repository README still describes the earlier **Beta 0.1** workflow, including manual advancement of TV test patches and a recommendation for no camera movement. That documentation predates the reported Beta 8 synchronized workflow and must not be treated as the latest product behavior without checking source/history.

Current README-level baseline includes:

- HyperHDR SSDP discovery.
- Connection to HyperHDR JSON server.
- TV measurements for white, R/G/B, C/M/Y, and black.
- Camera white-balance/exposure locking concepts.
- Automatic LED color driving during wall measurement.
- Median sampling of a center camera region.
- Ambient/black subtraction.
- 3×3 primary correction and suggested 8-color ICE anchors.
- Temporary HyperHDR color priority and cleanup.
- No automatic persistent HyperHDR calibration writes in the early beta.

These are useful architectural baselines but may be superseded or extended by later beta code.

## Current validation baseline in repository

The existing `docs/BETA_TESTING.md` requires:

- Android build against API 35.
- JVM tests passing.
- Identity-calibration solver behavior.
- Ambient-black subtraction.
- Invalid/singular primary matrix rejection.
- Correct HyperHDR SSDP parsing.
- Debug APK generation in GitHub Actions.
- Real-device checks for camera stability, discovery, white-balance consistency, LED control/cleanup, repeatability, and visible color improvement.

Later Beta 8 validation must additionally cover synchronized video timing, TV tracking, preview/analysis geometry, and motion tolerance.

## Continuation rules

- **Do not restart from Beta 0.1.** The user reports Beta 8 was already delivered.
- Recover the actual latest source/build state before making design changes.
- Preserve prior working behavior unless the user explicitly wants it replaced.
- Keep app and calibration video versions matched.
- Record every future beta here with: version, commit SHA, artifact/video filename, changes, tests, user feedback, regressions, and unresolved items.
- Do not claim the full shared ChatGPT conversation has been read unless its actual messages were successfully retrieved.

## Information still to recover

- [ ] Exact Beta 1–Beta 8 chronology and changes.
- [ ] Exact Beta 8 source commit or source snapshot.
- [ ] Beta 8 APK filename/artifact location.
- [ ] Matching Beta 8 calibration-video filename/artifact location.
- [ ] Exact calibration video patch order and timing/dwell/transition durations.
- [ ] Exact final-black duration.
- [ ] TV detection/tracking implementation and thresholds.
- [ ] Camera preview-to-analysis coordinate transform details.
- [ ] Motion-stability/motion-tolerance algorithm and thresholds.
- [ ] Exposure, white-balance, focus, and frame-selection behavior in Beta 8.
- [ ] User's real-device feedback after receiving Beta 8.
- [ ] Known bugs or regressions after Beta 8.
- [ ] Exact next task requested at the end of the prior thread.

## Next recovery action

Inspect the latest Android source, app version metadata, available repository history/build artifacts, and any accessible copy/export of the prior conversation. Merge recovered facts into this file before issuing a new beta.
