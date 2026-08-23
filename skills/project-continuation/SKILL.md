---
name: hyperhdr-led-calibrator-project-continuation
description: Recover the complete state of the HyperHDR LED Calibrator / Splitter Firmware and Android Calibration app project before making changes, using conversation history when available and GitHub as the durable source of truth.
---

# HyperHDR LED Calibrator Project Continuation Skill

Use this skill whenever resuming work on the HyperHDR LED Calibrator, the Android calibration app, its synchronized calibration video, or related splitter firmware work.

## Objective

Do not restart the design from assumptions or from an old README. Reconstruct the latest known state first, identify the newest delivered/tested beta, preserve the user's prior decisions, and continue from the exact unresolved issue.

## Known project identity

- GitHub repository: `spikked27/hyperhdr-led-calibrator`
- Prior ChatGPT thread title: `Splitter Firmware and Android Calibration app`
- Durable project state file: `/PROJECT_STATE.md`
- User-facing beta names (for example `Beta 8`) may be newer than README/version strings in source. Treat them separately until verified.

## Recovery procedure

1. **Read `PROJECT_STATE.md` first.** Treat explicit user-tested results, known bugs, and latest-delivered-version notes there as the primary continuation anchors.
2. **Recover prior conversation context when accessible.** Search for the exact thread title and these anchors rather than broad LED-calibration terms:
   - `Splitter Firmware and Android Calibration app`
   - `Beta 8`
   - `fully synchronized app/video workflow`
   - `camera-preview geometry`
   - `automatic TV tracking/capture`
   - `handheld-motion tolerance`
   - `long final-black sequence`
3. If a ChatGPT share URL is supplied, attempt to read the actual shared conversation only through available supported URL/web access. If the environment cannot fetch `chatgpt.com/share/...`, **do not claim the full chat was read** and do not invent missing messages.
4. If the user provides a ChatGPT export, copied transcript, HTML, JSON, PDF, or screenshots of the conversation, ingest that material and extract the complete chronological decision history.
5. **Inspect GitHub before changing code:**
   - root README and docs
   - Gradle/app version metadata
   - current Android source and resources
   - GitHub Actions workflow
   - commit history, tags/releases, issues/PRs when available
   - build artifacts/workflow artifacts when available
6. Determine which documentation is stale. Source code plus explicit later user test feedback outrank an older README. Never regress a later beta to an earlier manual workflow merely because README text has not caught up.
7. Build a short continuation map before coding:
   - latest delivered beta/app
   - matching calibration-video version
   - what was verified on a real phone/TV
   - what failed or remained uncertain
   - code paths responsible
   - next smallest testable change
8. Continue from the latest known beta and preserve working behavior unless the change explicitly requires replacing it.

## Conversation-to-project-state extraction

When a full or partial transcript becomes available, extract and append/update in `PROJECT_STATE.md`:

- chronological beta/version history
- APK or artifact filenames and links/commit SHAs if known
- matching video filename/version and exact timing sequence
- app/video synchronization rules
- camera geometry/cropping/orientation decisions
- TV detection/tracking algorithm and confidence thresholds
- exposure/white-balance/focus lock behavior
- handheld motion compensation/tolerance behavior
- color patch order, dwell times, transition times, and final-black duration
- HyperHDR discovery/control behavior and temporary priority
- calibration math and output mapping
- real-device test results from the user
- regressions and fixes
- unresolved bugs and next requested change

Do not compress away numbers, timings, thresholds, filenames, or explicit user observations. Those are often the critical implementation details.

## Truth precedence

When sources conflict, use this order unless there is a clear reason not to:

1. Latest explicit user test result or requirement.
2. Latest delivered app/video source or artifact that can be verified.
3. Latest repository source code and commit history.
4. `PROJECT_STATE.md`.
5. README/docs.
6. Earlier design discussion or untested proposals.

Call out unresolved conflicts instead of silently choosing a value.

## Required checks before a new beta

- Confirm the app compiles and automated tests pass where build tooling is available.
- Confirm camera preview coordinates and analysis coordinates use the same rotation/crop/aspect transform.
- Confirm TV ROI tracking remains stable through realistic handheld movement.
- Confirm capture is synchronized to the intended video patch, not a transition frame.
- Confirm exposure/white-balance behavior cannot drift between samples in a way that invalidates relative color comparison.
- Confirm the final-black interval is long enough for the app's end-of-run detection and final processing.
- Confirm HyperHDR temporary color priority is always cleared on completion, cancellation, error, or app exit.
- Do not automatically overwrite persistent HyperHDR calibration unless the user has explicitly approved that behavior.

## Beta delivery discipline

For each delivered beta:

1. Give it an unambiguous beta/version label.
2. Keep the app and calibration video version-matched.
3. Record what changed from the previous beta.
4. Record what was actually tested versus what remains theoretical.
5. Update `PROJECT_STATE.md` with the new state and remaining issues.
6. Preserve a rollback path to the previous known-good beta.

## Important limitation

This skill cannot bypass network or permission restrictions imposed by the current ChatGPT environment. A public share URL may still be unreadable from a given session. The skill exists to ensure that failure does not cause project amnesia: GitHub and `PROJECT_STATE.md` provide durable continuity, and any later-accessible transcript can be merged into that state without restarting the project.
