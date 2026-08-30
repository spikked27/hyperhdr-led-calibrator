# Beta 9 validation checklist

## Automated

- [ ] JVM tests pass.
- [ ] Android lint passes.
- [ ] Debug APK builds.
- [ ] Synthetic black-TV/bright-halo detector returns a 16:9 border.
- [ ] Marker decoder identifies all eight steps independent of patch RGB.
- [ ] HyperHDR adjustment payload contains all eight ICE anchors with `classic_config=false`.

## Real device

- [ ] Preview is geometrically correct on the target phone in both landscape directions; no horizontal/vertical squashing.
- [ ] With video paused on initial BLACK, backlights turn WHITE and the overlay snaps to the physical TV border.
- [ ] Border remains aligned during modest handheld movement.
- [ ] After border lock, backlights turn BLACK before video playback starts.
- [ ] Every video marker is detected, especially RED.
- [ ] Deliberately obscure one patch: app continues rather than hanging and later reports the missing patch.
- [ ] Replay video: only missing patch is captured; prior measurements remain.
- [ ] Final BLACK starts LED phase only after all eight TV references exist.
- [ ] Exit app during camera use, reopen it, and confirm a fresh camera session opens without a freeze/error.
- [ ] Complete run returns plausible ICE values and spatial diagnostics.
- [ ] Commit button writes the eight anchors to the selected HyperHDR instance; verify values in HyperHDR UI and after service restart.
